package dev.mmm.discordia.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Realized sales, accumulated from {@code /v2/skyblock/auctions_ended}.
 *
 * <p>That endpoint gives a price and compressed NBT but no name, so entries are keyed by the
 * SkyBlock id recovered through {@link Nbt}. With a rolling 7-day window this answers the question
 * the live BIN book cannot: how often does this thing actually sell?
 *
 * <p>Each sale also carries the attributes it was sold with — stars, reforge, pet level and its
 * ultimate enchants — which is what lets {@link AttributePricing} work out whether an enchant is
 * actually worth anything on a given item. The store contains sales observed while the client was
 * running, not a market-wide history; absence is not proof that an item never sells.
 */
public final class SoldStats {
    private static final Gson GSON = new Gson();
    private static final long WINDOW_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long RATE_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_PER_ITEM = 500;
    private static final int MAX_RATE_EVENTS = 2_000;
    private static final int MIN_RATE_SALES = 3;

    public record Sale(String uuid,
                       double price,
                       long ts,
                       int stars,
                       String reforge,
                       int petLevel,
                       int enchants,
                       String ultimates,
                       String heldItem,
                       String petTier,
                       boolean dyed,
                       String scrolls,
                       boolean recombobulated) {
    }

    private final Map<String, Deque<Sale>> sales = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> signatureSales = new ConcurrentHashMap<>();
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final Set<String> knownItems = ConcurrentHashMap.newKeySet();
    /** Market-wide 7-day item stats, overlaid by {@link GlobalSales}; never a replacement for
     * the exact local sale rows used by attribute pricing. */
    private final Map<String, Stats> globalStats = new ConcurrentHashMap<>();
    /** Ids queued for a global summary but not resolved yet. Pending is not zero. */
    private final Set<String> pendingGlobal = ConcurrentHashMap.newKeySet();
    private final Set<String> unavailableGlobal = ConcurrentHashMap.newKeySet();
    private volatile long firstSeen = System.currentTimeMillis();
    private volatile int ingested;

    public record Stats(int count, double median, double p25, double perDay, boolean pending,
                        boolean unavailable) {
        /** Compatibility constructor for callers that predate the unavailable state. */
        public Stats(int count, double median, double p25, double perDay, boolean pending) {
            this(count, median, p25, perDay, pending, false);
        }
    }

    /** Explicit no-observed-sale state. Callers never need a null check for missing sale history. */
    public static final Stats EMPTY = new Stats(0, 0d, 0d, 0d, false, false);
    /** A global summary is queued/in flight; volume is unknown, not zero. */
    public static final Stats PENDING = new Stats(0, 0d, 0d, 0d, true, false);
    /** The global source rejected this id; volume is unknown, not zero. */
    public static final Stats UNAVAILABLE = new Stats(0, 0d, 0d, 0d, false, true);

    /** Marks an item as waiting for a global summary. */
    public void setGlobalPending(String id) {
        if (id != null && !id.isEmpty()) {
            unavailableGlobal.remove(id);
            pendingGlobal.add(id);
            knownItems.add(id);
        }
    }

    public void clearGlobalPending(String id) {
        if (id != null) {
            pendingGlobal.remove(id);
        }
    }

    /** Marks a permanent item-level failure without turning it into zero sales. */
    public void setGlobalUnavailable(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        pendingGlobal.remove(id);
        unavailableGlobal.add(id);
        knownItems.add(id);
    }

    public int pendingGlobal() {
        return pendingGlobal.size();
    }

    public int unavailableGlobal() {
        return unavailableGlobal.size();
    }

    /** Clears an expired unavailable marker before a retry. */
    public void clearGlobalUnavailable(String id) {
        if (id != null) {
            unavailableGlobal.remove(id);
        }
    }

    /** Removes a summary when its tag alias changes and the cached value is no longer trustworthy. */
    public void clearGlobal(String id) {
        if (id != null) {
            globalStats.remove(id);
        }
    }

    /** Installs a market-wide summary for one item id. An empty summary is meaningful (zero sales). */
    public void setGlobal(String id, Stats stats) {
        if (id == null || id.isEmpty() || stats == null) {
            return;
        }
        pendingGlobal.remove(id);
        unavailableGlobal.remove(id);
        globalStats.put(id, stats);
        knownItems.add(id);
    }

    public int globalItems() {
        return globalStats.size();
    }

    /**
     * The market-wide summary only, never a locally observed fallback. The auction price sanity
     * guard needs this distinction: a single local sale can itself be the inflated print that we
     * are trying to reject.
     */
    public Stats globalSummary(String id) {
        if (id == null || id.isEmpty()) {
            return EMPTY;
        }
        Stats summary = globalStats.get(id);
        return summary == null ? EMPTY : summary;
    }

    /** True when there is at least one local, non-dyed sale in the 7-day window. */
    public boolean hasLocalSales(String id) {
        if (id == null) {
            return false;
        }
        Deque<Sale> queue = sales.get(id);
        if (queue == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (queue) {
            for (Sale sale : queue) {
                if (now - sale.ts() <= WINDOW_MS && !sale.dyed()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Adds every BIN sale in the response that we have not already counted. */
    public int ingest(JsonObject endedResponse) {
        JsonArray auctions = endedResponse.getAsJsonArray("auctions");
        if (auctions == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int added = 0;
        for (JsonElement element : auctions) {
            JsonObject auction = element.getAsJsonObject();
            if (!auction.has("bin") || !auction.get("bin").getAsBoolean()) {
                continue;
            }
            if (!auction.has("price")) {
                continue;
            }
            double price = auction.get("price").getAsDouble();
            if (price <= 0) {
                continue;
            }
            // The field is auction_id here; active auctions call the same thing uuid.
            String uuid = null;
            if (auction.has("uuid")) {
                uuid = auction.get("uuid").getAsString();
            } else if (auction.has("auction_id")) {
                uuid = auction.get("auction_id").getAsString();
            }
            if (uuid != null && seen.containsKey(uuid)) {
                continue;
            }

            Nbt.Decoded decoded = Nbt.decode(auction.has("item_bytes") ? auction.get("item_bytes").getAsString() : null);
            if (decoded == null || decoded.extra() == null) {
                continue;
            }
            ItemVariant variant = ItemVariant.from(decoded.extra(), decoded.name());
            String id = variant.id();
            knownItems.add(id);
            if (ItemVariant.hasAppliedDye(decoded.extra(), decoded.lore())) {
                continue;
            }
            // auctions_ended carries the real end timestamp. Stamping every row with the
            // ingestion time makes a backlog of old sales look like a sudden burst.
            long soldAt = auction.has("timestamp") ? auction.get("timestamp").getAsLong() : now;
            if (soldAt <= 0L) {
                soldAt = now;
            }
            if (uuid != null) {
                seen.put(uuid, soldAt);
            }

            Sale sale = new Sale(uuid, price, soldAt, variant.stars(), variant.reforge(),
                    variant.petLevel(), variant.enchants(), String.join(",", variant.ultimates()),
                    variant.heldItem(), variant.petTier(), false, variant.scrollSignature(),
                    variant.recombobulated());
            Deque<Sale> queue = sales.computeIfAbsent(id, ignored -> new ArrayDeque<>());
            synchronized (queue) {
                queue.addLast(sale);
                while (queue.size() > MAX_PER_ITEM) {
                    queue.removeFirst();
                }
            }

            Deque<Long> timing = signatureSales.computeIfAbsent(variant.signature(), ignored -> new ArrayDeque<>());
            synchronized (timing) {
                timing.addLast(soldAt);
                while (timing.size() > MAX_RATE_EVENTS) {
                    timing.removeFirst();
                }
            }
            added++;
        }
        ingested += added;
        prune(now);
        return added;
    }

    public Stats stats(String id) {
        if (id == null || id.isEmpty()) {
            return EMPTY;
        }
        knownItems.add(id);
        // The global source covers all variants and is not limited to when the client was online.
        // Prefer it for volume/median when it carries real volume; local rows remain the exact
        // variant evidence. A global zero must not erase sales this client actually observed.
        Stats global = globalStats.get(id);
        Deque<Sale> queue = sales.get(id);
        if (queue == null) {
            return global != null ? global : unresolved(id);
        }
        List<Double> prices = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (queue) {
            for (Sale sale : queue) {
                if (now - sale.ts() <= WINDOW_MS && !sale.dyed()) {
                    prices.add(sale.price());
                }
            }
        }
        if (prices.isEmpty()) {
            return global != null ? global : unresolved(id);
        }
        prices.sort(Comparator.naturalOrder());
        double perDay = prices.size() / observedDays(now);
        Stats local = new Stats(prices.size(), FlipEngine.percentile(prices, 0.5),
                FlipEngine.percentile(prices, 0.25), perDay, false, false);
        return global != null && global.count() > 0 ? global : local;
    }

    private Stats unresolved(String id) {
        if (pendingGlobal.contains(id)) {
            return PENDING;
        }
        if (unavailableGlobal.contains(id)) {
            return UNAVAILABLE;
        }
        return EMPTY;
    }

    /** A copy of the item's recent sales, for attribute valuation. */
    public List<Sale> salesFor(String id) {
        if (id == null) {
            return List.of();
        }
        knownItems.add(id);
        Deque<Sale> queue = sales.get(id);
        if (queue == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        List<Sale> out = new ArrayList<>();
        synchronized (queue) {
            for (Sale sale : queue) {
                if (now - sale.ts() <= WINDOW_MS && !sale.dyed()) {
                    out.add(sale);
                }
            }
        }
        return out;
    }

    /** Sales per hour for one exact variant signature, over the last 24h. 0 when too few sales. */
    public double salesPerHour(String signature) {
        if (signature == null) {
            return 0d;
        }
        return rate(signatureSales.get(signature));
    }

    /** Sales per hour for the base item id, used when the variant itself is too sparse. */
    public double salesPerHourById(String id) {
        if (id == null) {
            return 0d;
        }
        Stats global = globalStats.get(id);
        if (global != null && global.count() > 0) {
            return global.perDay() > 0d ? global.perDay() / 24d : 0d;
        }
        Deque<Sale> queue = sales.get(id);
        if (queue == null) {
            return 0d;
        }
        long now = System.currentTimeMillis();
        int count = 0;
        synchronized (queue) {
            for (Sale sale : queue) {
                if (now - sale.ts() <= RATE_WINDOW_MS && !sale.dyed()) {
                    count++;
                }
            }
        }
        return count < MIN_RATE_SALES ? 0d : count / observedHours(now);
    }

    private double rate(Deque<Long> timestamps) {
        if (timestamps == null) {
            return 0d;
        }
        long now = System.currentTimeMillis();
        int count = 0;
        synchronized (timestamps) {
            for (long ts : timestamps) {
                if (now - ts <= RATE_WINDOW_MS) {
                    count++;
                }
            }
        }
        return count < MIN_RATE_SALES ? 0d : count / observedHours(now);
    }

    /** Sales currently in the window across all items. */
    public int total() {
        int total = 0;
        for (Deque<Sale> queue : sales.values()) {
            total += queue.size();
        }
        return total;
    }

    public int ingested() {
        return ingested;
    }

    public int trackedItems() {
        return sales.size();
    }

    /** Every item the sale store or the resource catalog knows about. */
    public int knownItems() {
        return knownItems.size();
    }

    /** Seeds all game item ids, including items with no sales in the local window. */
    public void registerKnown(Collection<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            if (id != null && !id.isEmpty()) {
                knownItems.add(id);
            }
        }
    }

    public void registerKnown(String id) {
        if (id != null && !id.isEmpty()) {
            knownItems.add(id);
        }
    }

    public void prune() {
        prune(System.currentTimeMillis());
    }

    private void prune(long now) {
        for (Map.Entry<String, Deque<Sale>> entry : sales.entrySet()) {
            Deque<Sale> queue = entry.getValue();
            synchronized (queue) {
                while (!queue.isEmpty() && now - queue.peekFirst().ts() > WINDOW_MS) {
                    queue.removeFirst();
                }
            }
        }
        for (Deque<Long> timestamps : signatureSales.values()) {
            synchronized (timestamps) {
                while (!timestamps.isEmpty() && now - timestamps.peekFirst() > RATE_WINDOW_MS) {
                    timestamps.removeFirst();
                }
            }
        }
        seen.values().removeIf(timestamp -> now - timestamp > WINDOW_MS);
    }

    private double observedDays(long now) {
        double days = (now - firstSeen) / 86_400_000d;
        // The window never holds more than 7 days, so older installs must not divide by more.
        // A fresh store is floored at one day: a minute of data cannot imply a daily rate.
        if (Double.isNaN(days)) {
            return 1d;
        }
        return Math.max(1d, Math.min(7d, days));
    }

    private double observedHours(long now) {
        double hours = (now - firstSeen) / 3_600_000d;
        if (Double.isNaN(hours)) {
            return 1d;
        }
        return Math.max(1d, Math.min(24d, hours));
    }

    public void save(Path path) {
        JsonObject root = new JsonObject();
        root.addProperty("firstSeen", firstSeen);

        JsonObject items = new JsonObject();
        for (Map.Entry<String, Deque<Sale>> entry : sales.entrySet()) {
            JsonArray list = new JsonArray();
            synchronized (entry.getValue()) {
                for (Sale sale : entry.getValue()) {
                    JsonArray row = new JsonArray();
                    row.add(new JsonPrimitive(sale.price()));
                    row.add(new JsonPrimitive(sale.ts()));
                    if (sale.uuid() != null) {
                        row.add(new JsonPrimitive(sale.uuid()));
                    }
                    row.add(new JsonPrimitive(sale.stars()));
                    row.add(new JsonPrimitive(sale.reforge()));
                    row.add(new JsonPrimitive(sale.petLevel()));
                    row.add(new JsonPrimitive(sale.enchants()));
                    row.add(new JsonPrimitive(sale.ultimates()));
                    row.add(new JsonPrimitive(sale.heldItem() == null ? "" : sale.heldItem()));
                    row.add(new JsonPrimitive(sale.petTier() == null ? "" : sale.petTier()));
                    row.add(new JsonPrimitive(sale.dyed()));
                    row.add(new JsonPrimitive(sale.scrolls() == null ? "" : sale.scrolls()));
                    row.add(new JsonPrimitive(sale.recombobulated()));
                    list.add(row);
                }
            }
            if (!list.isEmpty()) {
                items.add(entry.getKey(), list);
            }
        }
        root.add("items", items);

        JsonObject signatures = new JsonObject();
        for (Map.Entry<String, Deque<Long>> entry : signatureSales.entrySet()) {
            JsonArray list = new JsonArray();
            synchronized (entry.getValue()) {
                for (long ts : entry.getValue()) {
                    list.add(new JsonPrimitive(ts));
                }
            }
            if (!list.isEmpty()) {
                signatures.add(entry.getKey(), list);
            }
        }
        root.add("signatures", signatures);

        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
            // Stats are a cache, not user data; failing to persist is not fatal.
        }
    }

    public static SoldStats load(Path path) {
        SoldStats stats = new SoldStats();
        if (!Files.exists(path)) {
            return stats;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("firstSeen")) {
                stats.firstSeen = root.get("firstSeen").getAsLong();
            }
            JsonObject items = root.getAsJsonObject("items");
            if (items != null) {
                for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                    Deque<Sale> queue = new ArrayDeque<>();
                    for (JsonElement element : entry.getValue().getAsJsonArray()) {
                        JsonArray row = element.getAsJsonArray();
                        String uuid = row.size() > 2 ? row.get(2).getAsString() : null;
                        Sale sale = new Sale(
                                uuid,
                                row.get(0).getAsDouble(),
                                row.get(1).getAsLong(),
                                row.size() > 3 ? row.get(3).getAsInt() : 0,
                                row.size() > 4 ? row.get(4).getAsString() : "",
                                row.size() > 5 ? row.get(5).getAsInt() : 0,
                                row.size() > 6 ? row.get(6).getAsInt() : 0,
                                row.size() > 7 ? row.get(7).getAsString() : "",
                                row.size() > 8 ? row.get(8).getAsString() : "",
                                row.size() > 9 ? row.get(9).getAsString() : "",
                                row.size() > 10 && row.get(10).getAsBoolean(),
                                row.size() > 11 ? row.get(11).getAsString() : "",
                                row.size() > 12 && row.get(12).getAsBoolean());
                        queue.addLast(sale);
                        if (uuid != null) {
                            stats.seen.put(uuid, sale.ts());
                        }
                    }
                    stats.sales.put(entry.getKey(), queue);
                    stats.knownItems.add(entry.getKey());
                }
            }
            JsonObject signatures = root.getAsJsonObject("signatures");
            if (signatures != null) {
                for (Map.Entry<String, JsonElement> entry : signatures.entrySet()) {
                    Deque<Long> timestamps = new ArrayDeque<>();
                    for (JsonElement element : entry.getValue().getAsJsonArray()) {
                        timestamps.addLast(element.getAsLong());
                    }
                    stats.signatureSales.put(entry.getKey(), timestamps);
                }
            }
            stats.prune();
        } catch (Exception ignored) {
            return new SoldStats();
        }
        return stats;
    }
}
