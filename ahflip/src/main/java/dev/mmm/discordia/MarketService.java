package dev.mmm.discordia;

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
import dev.mmm.discordia.core.NpcPrices;
import dev.mmm.discordia.core.SoldStats;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans the market without wasting bandwidth.
 *
 * <p>Numbers that drive the design:
 * <ul>
 *   <li>one auction page is ~2.4 MB, and the full dump is ~42 pages;</li>
 *   <li>{@code auctions_ended} is ~68 KB and shares the same {@code lastUpdated} tick, so it is a
 *       cheap heartbeat <em>and</em> the realized-sale feed;</li>
 *   <li>page 0 is sorted newest-first, so every newly listed auction appears there.</li>
 * </ul>
 *
 * <p>So: poll the 68 KB heartbeat every few seconds, and when the tick moves pull only page 0
 * (~2.4 MB) to score the new listings against the last full book. The full book is rebuilt only
 * every {@link FlipConfig#fullRefreshMs} to keep reference prices honest.
 */
public final class MarketService {
    private static final long BAZAAR_TTL_MS = 60_000L;
    private static final long SAVE_INTERVAL_MS = 300_000L;

    private final FlipConfig config;
    private final FlipEngine engine = new FlipEngine();
    private final SoldStats sold;
    private final GlobalSales globalSales;
    private final Path statsPath;
    private final Object lock = new Object();

    private volatile FlipEngine.Result result = new FlipEngine.Result(List.of(), List.of(), List.of(), List.of());
    private volatile FlipEngine.Result baseResult = result;
    private final FastFlipBook fastFlips = new FastFlipBook();
    private volatile FastAuctionFeed fastFeed;
    private volatile NpcPrices npcPrices = NpcPrices.empty();
    private long npcAt;
    private volatile String status = "starting";
    private volatile int catalogItems;
    private volatile long lastUpdated;
    private volatile boolean refresh;
    private volatile MarketSnapshot reference;
    private volatile boolean stopped;
    private Thread thread;

    private long lastTick = Long.MIN_VALUE;
    private long lastFull;
    private long lastSave;
    private JsonObject bazaarCache;
    private long bazaarAt;

    public MarketService(FlipConfig config) {
        this.config = config;
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path preferred = configDir.resolve("discordia-sales.json");
        Path legacy = configDir.resolve("ahflip-sales.json");
        boolean preferredEmpty = !Files.exists(preferred);
        if (!preferredEmpty) {
            try {
                preferredEmpty = Files.size(preferred) < 10L;
            } catch (IOException ignored) {
                preferredEmpty = false;
            }
        }
        if (preferredEmpty && Files.exists(legacy)) {
            try {
                Files.copy(legacy, preferred);
            } catch (IOException ignored) {
                // Keep using the legacy file if the copy fails.
                preferred = legacy;
            }
        }
        this.statsPath = preferred;
        this.sold = SoldStats.load(statsPath);
        this.globalSales = new GlobalSales(sold, configDir.resolve("discordia-global-sales.json"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sold.save(statsPath);
            globalSales.save();
        }, "discordia-save"));
    }

    public synchronized void start() {
        if (thread != null && thread.isAlive()) {
            stopped = false;
            startFastFeed();
            return;
        }
        stopped = false;
        globalSales.start();
        startFastFeed();
        thread = new Thread(this::loop, "discordia-market");
        thread.setDaemon(true);
        thread.start();
    }

    private void startFastFeed() {
        if (!config.fastAuctions || fastFeed != null) {
            return;
        }
        fastFeed = new FastAuctionFeed(() -> reference, this::onFastListing);
        fastFeed.start();
    }

    /** Stops local polling while a collector feed is active. */
    public void stop() {
        stopped = true;
        FastAuctionFeed feed = fastFeed;
        fastFeed = null;
        if (feed != null) {
            feed.stop();
        }
        fastFlips.clear();
        Thread current = thread;
        if (current != null) {
            current.interrupt();
        }
        globalSales.stop();
    }

    /** Forces the next cycle to rebuild the full book. */
    public void refreshNow() {
        fastFlips.clear();
        synchronized (lock) {
            refresh = true;
            lock.notifyAll();
        }
    }

    private void loop() {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            boolean savedWork = false;
            try {
                JsonObject ended = HypixelApi.getJson(HypixelApi.ENDED_URL);
                if (!ItemCatalog.loaded()) {
                    catalogItems = ItemCatalog.register(sold);
                }
                long tick = ended.has("lastUpdated") ? ended.get("lastUpdated").getAsLong() : 0L;
                sold.ingest(ended);

                long now = System.currentTimeMillis();
                boolean force = takeRefresh();
                boolean tickChanged = tick != lastTick;
                boolean needBook = reference == null || force || now - lastFull >= config.fullRefreshMs;

                if (needBook || tickChanged) {
                    status = "fetching";
                    if (bazaarCache == null || now - bazaarAt > BAZAAR_TTL_MS) {
                        try {
                            bazaarCache = HypixelApi.getJson(HypixelApi.BAZAAR_URL);
                        } catch (Exception bazaarFailure) {
                            // Bazaar prices improve auction valuation, but they are not required for
                            // the auction scan. Keep any previous book and keep the AH feed alive.
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
                        // The other 41 pages have not changed; only page 0 can hold new listings.
                        candidates = MarketSnapshot.from(bazaarCache, List.of(HypixelApi.auctionPage(0)));
                    }

                    Map<String, Double> bazaarBuy = new HashMap<>();
                    for (MarketSnapshot.BazaarQuote quote : reference.bazaar.values()) {
                        bazaarBuy.put(quote.id(), quote.topAsk());
                    }
                    if (now - npcAt > 3_600_000L) {
                        try {
                            npcPrices = NpcPrices.fetch();
                            npcAt = now;
                        } catch (Exception ignored) {
                            // Keep the previous table; NPC scanning simply stays stale for a while.
                        }
                    }
                    requestGlobalSales(candidates, reference);
                    FlipEngine.Result computed = engine.compute(candidates, reference, config, sold,
                            new AttributePricing(sold, bazaarBuy), npcPrices);
                    baseResult = computed;
                    result = new FlipEngine.Result(computed.bazaar(),
                            fastFlips.merge(computed.auctions(), config.limit),
                            computed.deterministic(), computed.sniping());
                    lastUpdated = now;
                    lastTick = tick;
                    status = "ok";
                    savedWork = true;
                } else {
                    status = "watching";
                }

                if (savedWork && now - lastSave > SAVE_INTERVAL_MS) {
                    sold.save(statsPath);
                    lastSave = now;
                }
            } catch (Exception e) {
                status = "error: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage());
            }

            synchronized (lock) {
                long deadline = System.currentTimeMillis() + Math.max(1_000L, config.scanIntervalMs);
                while (!refresh) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        lock.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        sold.save(statsPath);
                        return;
                    }
                }
            }
        }
    }

    private boolean takeRefresh() {
        synchronized (lock) {
            boolean value = refresh;
            refresh = false;
            return value;
        }
    }

    /** Scores one fast-lane listing against the cached book and merges it into the current result. */
    private void onFastListing(MarketSnapshot.Listing listing) {
        MarketSnapshot book = reference;
        if (listing == null || book == null) {
            return;
        }
        MarketSnapshot candidate = MarketSnapshot.ofListings(List.of(listing),
                System.currentTimeMillis(), book.bazaar);
        requestGlobalSales(candidate);
        Map<String, Double> bazaarBuy = new HashMap<>();
        for (MarketSnapshot.BazaarQuote quote : book.bazaar.values()) {
            bazaarBuy.put(quote.id(), quote.topAsk());
        }
        FlipEngine.Result fast = engine.compute(candidate, book, config, sold,
                new AttributePricing(sold, bazaarBuy), npcPrices);
        if (fast.auctions().isEmpty()) {
            return;
        }
        fastFlips.putAll(fast.auctions());
        FlipEngine.Result base = baseResult;
        result = new FlipEngine.Result(base.bazaar(),
                fastFlips.merge(base.auctions(), config.limit),
                base.deterministic(), base.sniping());
    }

    private void requestGlobalSales(MarketSnapshot candidates, MarketSnapshot reference) {
        requestGlobalSales(candidates);
        if (reference != null && reference != candidates) {
            requestGlobalSales(reference);
        }
        if (ItemCatalog.loaded()) {
            globalSales.requestCatalog(ItemCatalog.ids());
        }
    }

    private void requestGlobalSales(MarketSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        List<MarketSnapshot.Listing> all = new java.util.ArrayList<>();
        for (List<MarketSnapshot.Listing> listings : snapshot.listingsByKey.values()) {
            all.addAll(listings);
        }
        // An active listing with no local evidence is the highest-value unknown: resolving it
        // stops the zero-sale gate from calling an unobserved item zero. Do those first.
        for (MarketSnapshot.Listing listing : all) {
            if (!sold.hasLocalSales(listing.skyblockId())) {
                globalSales.requestPriority(listing.skyblockId(), listing.variant().marketTag());
            }
        }
        // Other active listings still beat the full catalog backlog.
        for (MarketSnapshot.Listing listing : all) {
            if (sold.hasLocalSales(listing.skyblockId())) {
                globalSales.request(listing.skyblockId(), listing.variant().marketTag());
            }
        }
        // Ending auctions are the other place an inflated BIN reference can become a bid
        // recommendation. Resolve their weekly summaries before the catalog backlog too.
        for (MarketSnapshot.Auction auction : snapshot.endingAuctions) {
            if (!sold.hasLocalSales(auction.skyblockId())) {
                globalSales.requestPriority(auction.skyblockId(), auction.variant().marketTag());
            } else {
                globalSales.request(auction.skyblockId(), auction.variant().marketTag());
            }
        }
        // Bazaar ids normally have no auction sales; keep them behind active auction listings.
        for (String id : snapshot.bazaar.keySet()) {
            globalSales.request(id, id);
        }
    }

    public List<Flip> bazaar() {
        return result.bazaar();
    }

    public List<Flip> auctions() {
        return result.auctions();
    }

    public List<Flip> deterministic() {
        return result.deterministic();
    }

    public List<Flip> sniping() {
        return result.sniping();
    }

    public String status() {
        return status;
    }

    public long lastUpdated() {
        return lastUpdated;
    }

    public int saleSamples() {
        return sold.ingested();
    }

    public int trackedItems() {
        return sold.knownItems();
    }

    public int catalogItems() {
        return catalogItems;
    }

    public String globalStatus() {
        return globalSales.cached() + " global sale caches, " + globalSales.pending()
                + " queued, " + sold.pendingGlobal() + " pending, "
                + sold.unavailableGlobal() + " unavailable | " + globalSales.health();
    }

    public int globalCached() {
        return globalSales.cached();
    }

    public int globalQueued() {
        return globalSales.pending();
    }

    public int globalPendingSales() {
        return sold.pendingGlobal();
    }

    public int globalUnavailableSales() {
        return sold.unavailableGlobal();
    }

    public void setGlobalFaultMode(GlobalSales.FaultMode mode) {
        globalSales.setFaultMode(mode);
    }

    public String globalHealth() {
        return globalSales.health();
    }

    /** Auction flips whose global volume is still pending or unavailable. */
    public int unresolvedFlips() {
        int count = 0;
        for (Flip flip : result.auctions()) {
            if (flip.detail().contains("global volume pending")
                    || flip.detail().contains("global volume unavailable")) {
                count++;
            }
        }
        return count;
    }

    public int globalSalesItems() {
        return globalSales.cached();
    }
}
