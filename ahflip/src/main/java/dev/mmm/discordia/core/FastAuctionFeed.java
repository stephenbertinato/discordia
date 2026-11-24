package dev.mmm.discordia.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Optional low-latency lane fed by SkyCofl's newest-active-auction view.
 *
 * <p>The public Hypixel auction dump is only refreshed about once a minute. SkyCofl's active feed
 * publishes new auctions in a few seconds, and its detail route carries the NBT and enchantment
 * data needed to build the same variant keys as the normal book. This lane watches only items
 * whose tag already exists in the cached book and whose ask is plausibly below the market, then
 * brings full listings back to the normal flip engine.
 *
 * <p>Every eligible listing is queued, and a separate drain thread works through the backlog while
 * sharing SkyCofl's public rate budget with the normal global-sales lookups. Temporary detail
 * failures stay queued and retry; the ordinary page-0 scan remains the fallback. No API key or
 * account is used.
 */
public final class FastAuctionFeed {
    private static final String FEED_URL = "https://sky.coflnet.com/api/auctions/active/filter"
            + "?page=0&pageSize=100&orderBy=NEWEST";
    private static final String DETAIL_URL = "https://sky.coflnet.com/api/auction/";
    private static final long POLL_MS = 2_000L;
    private static final double MAX_PRICE_RATIO = 1.35d;
    private static final int MAX_SEEN = 4_000;
    private static final Gson GSON = new Gson();

    private record Candidate(String uuid, String tag, String tier, double price, double ratio,
                             long endAt) {
    }

    private record TagBook(List<MarketSnapshot.Listing> samples, double lowest) {
    }

    private static final class Pending {
        private final Candidate candidate;
        private int attempts;

        private Pending(Candidate candidate) {
            this.candidate = candidate;
        }
    }

    private final Supplier<MarketSnapshot> reference;
    private final Consumer<MarketSnapshot.Listing> sink;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Deque<String> seenOrder = new ArrayDeque<>();
    private final Object queueLock = new Object();
    private final PriorityQueue<Pending> queue = new PriorityQueue<>(Comparator.comparingDouble(
            pending -> pending.candidate.ratio()));
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    private volatile boolean running;
    private volatile String status = "off";
    private volatile long lastListingAt;
    private volatile Map<String, TagBook> tagBooks = Map.of();
    private Thread pollThread;
    private Thread drainThread;
    private volatile ScheduledExecutorService retryScheduler;
    private volatile long generation;
    private MarketSnapshot indexedReference;

    public FastAuctionFeed(Supplier<MarketSnapshot> reference,
                           Consumer<MarketSnapshot.Listing> sink) {
        this.reference = reference;
        this.sink = sink;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        status = "starting";
        generation++;
        retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "discordia-fast-retry");
            thread.setDaemon(true);
            return thread;
        });
        pollThread = new Thread(this::pollLoop, "discordia-fast-auctions");
        pollThread.setDaemon(true);
        pollThread.start();
        drainThread = new Thread(this::drainLoop, "discordia-fast-details");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        ScheduledExecutorService scheduler = retryScheduler;
        retryScheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        Thread poll = pollThread;
        Thread drain = drainThread;
        pollThread = null;
        drainThread = null;
        if (poll != null) {
            poll.interrupt();
        }
        if (drain != null) {
            drain.interrupt();
        }
        synchronized (queueLock) {
            queue.clear();
            queued.clear();
            queueLock.notifyAll();
        }
        status = "off";
    }

    public String status() {
        return status;
    }

    public long lastListingAt() {
        return lastListingAt;
    }

    private void pollLoop() {
        long nextPoll = 0L;
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                long now = System.currentTimeMillis();
                if (now >= nextPoll) {
                    poll();
                    nextPoll = now + POLL_MS;
                }
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                status = "error: " + shortError(error);
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void poll() throws Exception {
        MarketSnapshot book = reference.get();
        if (book == null || book.listingsByKey.isEmpty()) {
            status = "waiting for book";
            return;
        }
        if (book != indexedReference) {
            tagBooks = index(book);
            indexedReference = book;
        }

        // The public endpoint is CDN-cached with max-age=5. A cache-buster gives us the current
        // in-memory snapshot instead of waiting for that cache window.
        CoflRateLimiter.acquireFast();
        JsonObject feed = HypixelApi.getJson(FEED_URL + "&_=" + System.currentTimeMillis());
        JsonArray items = feed.getAsJsonArray("items");
        if (items == null) {
            status = "bad feed";
            return;
        }

        for (JsonElement element : items) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String uuid = string(item, "uuid", "");
            if (uuid.isBlank() || !item.has("bin") || !item.get("bin").getAsBoolean()) {
                continue;
            }
            String tag = normalizeTag(string(item, "tag", ""));
            TagBook tagBook = tagBooks.get(tag);
            if (tagBook == null) {
                remember(uuid);
                continue;
            }
            double price = number(item, "startingBid", 0d);
            if (price <= 0d || price > tagBook.lowest() * MAX_PRICE_RATIO) {
                remember(uuid);
                continue;
            }
            if (seen.contains(uuid) || queued.contains(uuid)) {
                continue;
            }
            enqueue(new Candidate(uuid, tag, string(item, "tier", ""), price,
                    price / tagBook.lowest(), timestamp(item, "end")));
        }
        status = queueSize() == 0 ? "ok" : "ok, " + queueSize() + " queued";
    }

    /**
     * Drains the complete eligible backlog independently of feed polling. The shared Cofl budget
     * decides the pace; there is no per-poll cap, so a burst cannot strand candidates in the feed
     * while the next snapshot replaces them.
     */
    private void drainLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            Pending pending;
            try {
                pending = take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (pending == null) {
                return;
            }

            try {
                CoflRateLimiter.acquireFastDetail();
                JsonObject detail = HypixelApi.getJson(DETAIL_URL + pending.candidate.uuid()
                        + "?_=" + System.currentTimeMillis());
                MarketSnapshot.Listing listing = toListing(detail,
                        tagBooks.get(pending.candidate.tag()));
                if (listing != null && listing.uuid().equalsIgnoreCase(pending.candidate.uuid())) {
                    lastListingAt = System.currentTimeMillis();
                    sink.accept(listing);
                    complete(pending);
                } else {
                    retry(pending, "unusable detail");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception error) {
                retry(pending, shortError(error));
            }
        }
    }

    private void enqueue(Candidate candidate) {
        synchronized (queueLock) {
            if (!running || seen.contains(candidate.uuid()) || !queued.add(candidate.uuid())) {
                return;
            }
            queue.add(new Pending(candidate));
            queueLock.notifyAll();
        }
    }

    private Pending take() throws InterruptedException {
        synchronized (queueLock) {
            while (running && queue.isEmpty()) {
                queueLock.wait();
            }
            if (!running) {
                return null;
            }
            return queue.poll();
        }
    }

    private void complete(Pending pending) {
        retire(pending);
    }

    private void retry(Pending pending, String error) {
        // A failed detail is not evidence that the auction is gone. Keep the UUID queued and
        // retry it later; only a successful detail or the auction's own end time retires it.
        long endAt = pending.candidate.endAt();
        if (endAt > 0L && System.currentTimeMillis() >= endAt) {
            retire(pending);
            return;
        }
        pending.attempts++;
        long backoff = Math.min(5_000L, 500L * Math.max(1, pending.attempts));
        status = "retrying " + pending.candidate.uuid() + ": " + error;
        ScheduledExecutorService scheduler = retryScheduler;
        long run = generation;
        if (scheduler == null) {
            requeue(pending, run);
            return;
        }
        try {
            scheduler.schedule(() -> requeue(pending, run), backoff, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // The lane stopped while this failure was being scheduled.
            synchronized (queueLock) {
                queued.remove(pending.candidate.uuid());
            }
        }
    }

    private void requeue(Pending pending, long run) {
        synchronized (queueLock) {
            if (run != generation || !running || seen.contains(pending.candidate.uuid())) {
                queued.remove(pending.candidate.uuid());
                return;
            }
            queue.add(pending);
            queueLock.notifyAll();
        }
    }

    private void retire(Pending pending) {
        synchronized (queueLock) {
            queued.remove(pending.candidate.uuid());
            remember(pending.candidate.uuid());
        }
    }

    private int queueSize() {
        synchronized (queueLock) {
            return queue.size();
        }
    }

    private Map<String, TagBook> index(MarketSnapshot book) {
        Map<String, List<MarketSnapshot.Listing>> grouped = new HashMap<>();
        for (List<MarketSnapshot.Listing> listings : book.listingsByKey.values()) {
            for (MarketSnapshot.Listing listing : listings) {
                String tag = normalizeTag(listing.skyblockId());
                if (!tag.isBlank()) {
                    grouped.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(listing);
                }
            }
        }
        Map<String, TagBook> result = new HashMap<>();
        for (Map.Entry<String, List<MarketSnapshot.Listing>> entry : grouped.entrySet()) {
            double lowest = Double.MAX_VALUE;
            for (MarketSnapshot.Listing listing : entry.getValue()) {
                if (listing.price() > 0d) {
                    lowest = Math.min(lowest, listing.price());
                }
            }
            if (lowest != Double.MAX_VALUE) {
                result.put(entry.getKey(), new TagBook(List.copyOf(entry.getValue()), lowest));
            }
        }
        return result;
    }

    private MarketSnapshot.Listing toListing(JsonObject detail, TagBook tagBook) {
        if (detail == null || tagBook == null || tagBook.samples().isEmpty()) {
            return null;
        }
        String uuid = string(detail, "uuid", "");
        String rawTag = string(detail, "tag", "");
        String tag = normalizeTag(rawTag);
        double price = number(detail, "startingBid", 0d);
        boolean bin = detail.has("bin") && detail.get("bin").getAsBoolean();
        if (uuid.isBlank() || tag.isBlank() || price <= 0d || !bin) {
            return null;
        }
        MarketSnapshot.Listing sample = sample(tagBook, string(detail, "tier", ""));
        if (sample == null) {
            return null;
        }

        Map<String, Object> attributes = new HashMap<>();
        JsonObject nbt = detail.getAsJsonObject("nbtData");
        JsonObject data = nbt == null ? null : nbt.getAsJsonObject("data");
        if (data != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> decoded = GSON.fromJson(data, Map.class);
            if (decoded != null) {
                attributes.putAll(decoded);
            }
        }

        boolean pet = rawTag.toUpperCase(Locale.ROOT).startsWith("PET_");
        attributes.put("id", pet ? "PET" : rawTag);
        String reforge = string(detail, "reforge", "");
        if (!reforge.isBlank() && !"none".equalsIgnoreCase(reforge)) {
            attributes.put("modifier", reforge);
        }

        JsonArray enchantments = detail.getAsJsonArray("enchantments");
        if (enchantments != null && !enchantments.isEmpty()) {
            Map<String, Object> enchants = new HashMap<>();
            for (JsonElement element : enchantments) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject enchant = element.getAsJsonObject();
                String type = string(enchant, "type", "");
                if (!type.isBlank() && enchant.has("level")) {
                    enchants.put(type, enchant.get("level").getAsInt());
                }
            }
            attributes.put("enchantments", enchants);
        }

        // ItemVariant expects petInfo as its original JSON string (that is how Live NBT stores it),
        // not as a decoded map. Keep it untouched. Some detail rows only expose the flattened
        // fields, so rebuild the small JSON object in that fallback case.
        if (pet && !(attributes.get("petInfo") instanceof String)) {
            JsonObject flat = detail.getAsJsonObject("flatNbt");
            if (flat != null) {
                attributes.put("petInfo", flat.toString());
            }
        }

        String displayName = cleanName(sample);
        ItemVariant variant = ItemVariant.from(attributes, displayName);
        if (MarketSnapshot.isExcludedCosmetic(string(detail, "itemName", ""), variant.id(),
                attributes, List.of())) {
            return null;
        }
        String rawTier = string(detail, "tier", sample.tier());
        String tier = variant.actualTier(rawTier == null || rawTier.isBlank()
                || rawTier.contains("?") ? sample.tier() : rawTier);
        String exactKey = variant.exactKey(displayName, tier);
        String groupKey = variant.groupKey(displayName, tier);
        String baseKey = variant.itemBaseKey(displayName, tier);
        return new MarketSnapshot.Listing(uuid, displayName, tier, price, variant.id(),
                variant.signature(), exactKey, groupKey, baseKey, variant.describe(), variant);
    }

    private static MarketSnapshot.Listing sample(TagBook tagBook, String tier) {
        for (MarketSnapshot.Listing listing : tagBook.samples()) {
            if (listing.tier().equalsIgnoreCase(tier)) {
                return listing;
            }
        }
        return tagBook.samples().get(0);
    }

    /**
     * The reference name is used only to recover a clean base name. Its reforge prefix must be
     * removed before the new listing's own reforge is encoded into its key.
     */
    private static String cleanName(MarketSnapshot.Listing sample) {
        String name = sample.name();
        String reforge = sample.variant().reforge();
        if (reforge != null && !reforge.isBlank()) {
            String stripped = name.replaceFirst("(?i)(?<!\\S)" + java.util.regex.Pattern.quote(reforge) + "\\s+", "");
            if (!stripped.isBlank()) {
                name = stripped;
            }
        }
        return name;
    }

    private static String normalizeTag(String tag) {
        String value = tag == null ? "" : tag.trim();
        return value.toUpperCase(Locale.ROOT).startsWith("PET_") ? value.substring(4) : value;
    }

    private void remember(String uuid) {
        synchronized (queueLock) {
            if (!seen.add(uuid)) {
                return;
            }
            seenOrder.addLast(uuid);
            while (seen.size() > MAX_SEEN && !seenOrder.isEmpty()) {
                seen.remove(seenOrder.removeFirst());
            }
        }
    }

    private static long timestamp(JsonObject object, String key) {
        String value = string(object, key, "");
        if (value.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static double number(JsonObject object, String key, double fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            double value = object.get(key).getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String shortError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String text = cause.getMessage();
        if (text == null || text.isBlank()) {
            text = cause.getClass().getSimpleName();
        }
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}
