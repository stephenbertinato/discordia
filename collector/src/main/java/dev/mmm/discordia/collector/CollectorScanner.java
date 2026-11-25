package dev.mmm.discordia.collector;

import com.google.gson.JsonObject;
import dev.mmm.discordia.core.AttributePricing;
import dev.mmm.discordia.core.Flip;
import dev.mmm.discordia.core.FlipConfig;
import dev.mmm.discordia.core.FastAuctionFeed;
import dev.mmm.discordia.core.FastFlipBook;
import dev.mmm.discordia.core.FlipEngine;
import dev.mmm.discordia.core.GlobalSales;
import dev.mmm.discordia.core.HypixelApi;
import dev.mmm.discordia.core.ItemCatalog;
import dev.mmm.discordia.core.MarketSnapshot;
import dev.mmm.discordia.core.MarketTicker;
import dev.mmm.discordia.core.SoldStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Runs the same auction-market loop as the client, but once for every connected consumer. */
public final class CollectorScanner {
    private static final long BAZAAR_TTL_MS = 60_000L;
    private static final long SAVE_INTERVAL_MS = 300_000L;

    private final FlipConfig config;
    private final Path stateDir;
    private final Path salesPath;
    private final SoldStats sold;
    private final GlobalSales globalSales;
    private final FlipEngine engine = new FlipEngine();
    private final Object engineLock = new Object();
    private final FastFlipBook fastFlips = new FastFlipBook();

    private volatile JsonObject snapshot;
    private volatile FastAuctionFeed fastFeed;
    private volatile FlipEngine.Result lastResult = new FlipEngine.Result(List.of(), List.of(), List.of(), List.of());
    private volatile String status = "starting";
    private volatile long lastUpdated;
    private volatile boolean running;
    private volatile MarketSnapshot lastCandidates;
    private volatile MarketSnapshot lastReference;

    private Thread thread;
    private MarketSnapshot reference;
    private long lastTick = Long.MIN_VALUE;
    private long lastFull;
    private long lastSave;
    private JsonObject bazaarCache;
    private long bazaarAt;
    /** Predicts the 60 s market tick so the heartbeat is polled hard only around it. */
    private final MarketTicker ticker = new MarketTicker();
    private Consumer<JsonObject> publisher = ignored -> {
    };
    private Consumer<JsonObject> statusPublisher = ignored -> {
    };

    public CollectorScanner(FlipConfig config, Path stateDir) {
        this.config = config;
        this.stateDir = stateDir;
        try {
            Files.createDirectories(stateDir);
        } catch (IOException ignored) {
            // The service can still run without persistence.
        }
        this.salesPath = stateDir.resolve("discordia-sales.json");
        this.sold = SoldStats.load(salesPath);
        this.globalSales = new GlobalSales(sold, stateDir.resolve("discordia-global-sales.json"));
        Runtime.getRuntime().addShutdownHook(new Thread(this::save, "collector-save"));
    }

    public void start(Consumer<JsonObject> publisher, Consumer<JsonObject> statusPublisher) {
        this.publisher = publisher == null ? ignored -> {
        } : publisher;
        this.statusPublisher = statusPublisher == null ? ignored -> {
        } : statusPublisher;
        running = true;
        globalSales.start();
        if (config.fastAuctions) {
            fastFeed = new FastAuctionFeed(() -> lastReference, this::onFastListing, config);
            fastFeed.start();
        }
        thread = new Thread(this::loop, "collector-market");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        FastAuctionFeed feed = fastFeed;
        fastFeed = null;
        if (feed != null) {
            feed.stop();
        }
        fastFlips.clear();
        if (thread != null) {
            thread.interrupt();
        }
        globalSales.stop();
        save();
    }

    public JsonObject snapshot() {
        return snapshot;
    }

    public String status() {
        return status;
    }

    public long lastUpdated() {
        return lastUpdated;
    }

    private void loop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                scan();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                String message = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage());
                if (message.length() > 180) {
                    message = message.substring(0, 180);
                }
                status = "error: " + message;
                statusPublisher.accept(CollectorJson.status(status));
            }

            try {
                // The heartbeat is ~172 KB, so a flat interval would be hundreds of megabytes an
                // hour. The ticker opens a burst only around the boundary it predicts from the
                // measured 60 s cadence, which is what makes a scan reach chat in under a second.
                Thread.sleep(ticker.nextDelayMs(System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void scan() throws IOException, InterruptedException {
        JsonObject ended = HypixelApi.getJson(HypixelApi.ENDED_URL);
        sold.ingest(ended);
        if (!ItemCatalog.loaded()) {
            ItemCatalog.register(sold);
        }

        long tick = ended.has("lastUpdated") ? ended.get("lastUpdated").getAsLong() : 0L;
        long now = System.currentTimeMillis();
        boolean tickChanged = tick != lastTick;
        ticker.observe(tickChanged, now);
        boolean needBook = reference == null || now - lastFull >= config.fullRefreshMs;
        if (!tickChanged && !needBook) {
            status = "watching";
            // The market did not move, but the client still needs to see that the poll loop is
            // alive. This is a tiny status frame, not another page-0 download.
            statusPublisher.accept(CollectorJson.status(status, lastUpdated));
            return;
        }

        status = "fetching";
        if (bazaarCache == null || now - bazaarAt > BAZAAR_TTL_MS) {
            try {
                bazaarCache = HypixelApi.getJson(HypixelApi.BAZAAR_URL);
            } catch (Exception ignored) {
                if (bazaarCache == null) {
                    bazaarCache = new JsonObject();
                }
            }
            bazaarAt = now;
        }

        MarketSnapshot candidates;
        if (needBook) {
            reference = MarketSnapshot.from(bazaarCache, HypixelApi.auctionPages());
            lastFull = now;
            candidates = reference;
        } else {
            candidates = MarketSnapshot.from(bazaarCache, List.of(HypixelApi.auctionPage(0)));
        }

        requestGlobalSales(candidates);
        requestGlobalSales(reference);
        if (ItemCatalog.loaded()) {
            globalSales.requestCatalog(ItemCatalog.ids());
        }

        lastCandidates = candidates;
        lastReference = reference;
        lastTick = tick;
        computeAndPublish(candidates, reference, tick == 0 ? now : tick);

        if (now - lastSave > SAVE_INTERVAL_MS) {
            sold.save(salesPath);
            lastSave = now;
        }
    }

    /**
     * Applies the client's current filters to the server engine and recomputes immediately from
     * the last fetched book. This is what makes lowering a filter reveal more than the previous
     * server snapshot; it is not just a client-side hide.
     */
    public void applyFilters(JsonObject filters) {
        if (filters == null) {
            return;
        }
        fastFlips.clear();
        synchronized (engineLock) {
            double minMargin = number(filters, "minMarginPct", config.minMarginPct);
            double minSnipeMargin = number(filters, "minSnipeMarginPct", config.minSnipeMarginPct);
            double minProfit = number(filters, "minProfit", config.minProfit);
            double minSnipeProfit = number(filters, "minSnipeProfit", config.minSnipeProfit);
            double budget = number(filters, "budget", config.budget);

            if (minMargin >= 0d && minMargin <= 100d) {
                config.minMarginPct = minMargin;
            }
            if (minSnipeMargin >= 0d && minSnipeMargin <= 100d) {
                config.minSnipeMarginPct = minSnipeMargin;
            }
            if (minProfit >= 0d) {
                config.minProfit = minProfit;
            }
            if (minSnipeProfit >= 0d) {
                config.minSnipeProfit = minSnipeProfit;
            }
            if (budget > 0d) {
                config.budget = budget;
            }
            config.bookOnlyMinMarginPct = Math.max(8d, config.minMarginPct);
            config.bookOnlyMinProfit = Math.max(1_000_000d, config.minProfit);
        }

        MarketSnapshot candidates = lastCandidates;
        MarketSnapshot reference = lastReference;
        if (candidates != null && reference != null) {
            long marketUpdated = lastTick == Long.MIN_VALUE || lastTick == 0L ? lastUpdated : lastTick;
            computeAndPublish(candidates, reference, marketUpdated);
        } else {
            status = "filters updated; waiting for first scan";
        }
    }

    private void computeAndPublish(MarketSnapshot candidates, MarketSnapshot reference, long marketUpdated) {
        Map<String, Double> bazaarBuy = new HashMap<>();
        for (MarketSnapshot.BazaarQuote quote : reference.bazaar.values()) {
            bazaarBuy.put(quote.id(), quote.topAsk());
        }

        JsonObject next;
        synchronized (engineLock) {
            FlipEngine.Result result = engine.compute(candidates, reference, config, sold,
                    new AttributePricing(sold, bazaarBuy), null);
            lastResult = result;
            lastUpdated = marketUpdated;
            status = "ok";
            next = snapshotFor(result);
            snapshot = next;
        }
        publisher.accept(next);
    }

    /** Scores one fast-lane listing against the cached book and republishes immediately. */
    private void onFastListing(MarketSnapshot.Listing listing) {
        MarketSnapshot book = lastReference;
        if (listing == null || book == null || lastCandidates == null) {
            return;
        }
        Map<String, Double> bazaarBuy = new HashMap<>();
        for (MarketSnapshot.BazaarQuote quote : book.bazaar.values()) {
            bazaarBuy.put(quote.id(), quote.topAsk());
        }
        MarketSnapshot candidate = MarketSnapshot.ofListings(List.of(listing),
                System.currentTimeMillis(), book.bazaar);
        globalSales.requestPriority(listing.skyblockId(), listing.variant().marketTag());

        JsonObject next = null;
        synchronized (engineLock) {
            FlipEngine.Result fast = engine.compute(candidate, book, config, sold,
                    new AttributePricing(sold, bazaarBuy), null);
            if (!fast.auctions().isEmpty()) {
                fastFlips.putAll(fast.auctions());
                next = snapshotFor(lastResult);
                snapshot = next;
            }
        }
        if (next != null) {
            publisher.accept(next);
        }
    }

    private JsonObject snapshotFor(FlipEngine.Result result) {
        List<Flip> bin = fastFlips.merge(result.auctions(), config.limit);
        List<Flip> nonBin = result.sniping();
        int pending = 0;
        for (Flip flip : bin) {
            if (flip.detail().contains("global volume pending")
                    || flip.detail().contains("global volume unavailable")) {
                pending++;
            }
        }
        return CollectorJson.snapshot(bin, nonBin, lastUpdated, pending, config.budget);
    }

    private static double number(JsonObject object, String key, double fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            double value = object.get(key).getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void requestGlobalSales(MarketSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        List<MarketSnapshot.Listing> all = new ArrayList<>();
        for (List<MarketSnapshot.Listing> listings : snapshot.listingsByKey.values()) {
            all.addAll(listings);
        }
        for (MarketSnapshot.Listing listing : all) {
            if (!sold.hasLocalSales(listing.skyblockId())) {
                globalSales.requestPriority(listing.skyblockId(), listing.variant().marketTag());
            }
        }
        for (MarketSnapshot.Listing listing : all) {
            if (sold.hasLocalSales(listing.skyblockId())) {
                globalSales.request(listing.skyblockId(), listing.variant().marketTag());
            }
        }
        for (MarketSnapshot.Auction auction : snapshot.endingAuctions) {
            if (!sold.hasLocalSales(auction.skyblockId())) {
                globalSales.requestPriority(auction.skyblockId(), auction.variant().marketTag());
            } else {
                globalSales.request(auction.skyblockId(), auction.variant().marketTag());
            }
        }
    }

    private void save() {
        try {
            sold.save(salesPath);
            globalSales.save();
        } catch (Exception ignored) {
            // Best-effort persistence only.
        }
    }
}
