package dev.mmm.discordia.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A point-in-time view of the two markets.
 *
 * <p>Everything here comes from the public, unauthenticated Hypixel API:
 * {@code /v2/skyblock/bazaar} and {@code /v2/skyblock/auctions}.
 *
 * <p>Auctions are pooled three ways so the engine can fall back gracefully when a key is too thin:
 * exact variant (enchants included), variant group (same reforge/stars/pet level, any enchants),
 * and base item (name + tier only).
 */
public final class MarketSnapshot {

    public record BazaarQuote(String id, double topBid, double topAsk, double weekBuy, double weekSell,
                              List<Level> asks, List<Level> bids) {

        public record Level(double price, double amount) {
        }

        public record Fill(double total, double filled, boolean complete) {
        }

        public BazaarQuote {
            asks = List.copyOf(asks);
            bids = List.copyOf(bids);
        }

        /** Walks the ask side: what it actually costs to buy {@code quantity} units. */
        public Fill buy(double quantity) {
            return fill(asks, quantity);
        }

        /** Walks the bid side: what {@code quantity} units actually fetch if sold now. */
        public Fill sell(double quantity) {
            return fill(bids, quantity);
        }

        private static Fill fill(List<Level> levels, double quantity) {
            double remaining = quantity;
            double total = 0d;
            for (Level level : levels) {
                if (remaining <= 0d) {
                    break;
                }
                if (level.amount() <= 0d || level.price() <= 0d) {
                    continue;
                }
                double take = Math.min(remaining, level.amount());
                total += take * level.price();
                remaining -= take;
            }
            return new Fill(total, quantity - remaining, remaining <= 1e-9);
        }
    }

    /** A non-BIN auction that has not ended yet. */
    public record Auction(String uuid,
                          String name,
                          String tier,
                          double currentBid,
                          double startingBid,
                          long end,
                          String skyblockId,
                          String variantSignature,
                          String variantKey,
                          String groupKey,
                          String baseKey,
                          String variantLabel,
                          ItemVariant variant) {
    }

    public record Listing(String uuid,
                          String name,
                          String tier,
                          double price,
                          String skyblockId,
                          String variantSignature,
                          String variantKey,
                          String groupKey,
                          String baseKey,
                          String variantLabel,
                          ItemVariant variant) {
    }

    public final long lastUpdated;
    public final Map<String, BazaarQuote> bazaar;
    public final Map<String, List<Listing>> listingsByKey;
    public final Map<String, List<Double>> pricesByKey;
    public final Map<String, List<Double>> groupPrices;
    public final Map<String, List<Double>> basePrices;
    public final List<Auction> endingAuctions;
    public final int auctionCount;

    private MarketSnapshot(long lastUpdated,
                           Map<String, BazaarQuote> bazaar,
                           Map<String, List<Listing>> listingsByKey,
                           Map<String, List<Double>> pricesByKey,
                           Map<String, List<Double>> groupPrices,
                           Map<String, List<Double>> basePrices,
                           List<Auction> endingAuctions,
                           int auctionCount) {
        this.lastUpdated = lastUpdated;
        this.bazaar = bazaar;
        this.listingsByKey = listingsByKey;
        this.pricesByKey = pricesByKey;
        this.groupPrices = groupPrices;
        this.basePrices = basePrices;
        this.endingAuctions = endingAuctions;
        this.auctionCount = auctionCount;
    }

    /** A tiny candidate snapshot for a fast-lane listing, priced against the cached full book. */
    public static MarketSnapshot ofListings(List<Listing> listings, long lastUpdated,
                                            Map<String, BazaarQuote> bazaar) {
        Map<String, List<Listing>> byExact = new HashMap<>();
        Map<String, List<Double>> exactPrices = new HashMap<>();
        Map<String, List<Double>> groupPrices = new HashMap<>();
        Map<String, List<Double>> basePrices = new HashMap<>();
        int count = 0;
        if (listings != null) {
            for (Listing listing : listings) {
                if (listing == null || listing.uuid().isBlank() || listing.price() <= 0d) {
                    continue;
                }
                byExact.computeIfAbsent(listing.variantKey(), ignored -> new ArrayList<>()).add(listing);
                exactPrices.computeIfAbsent(listing.variantKey(), ignored -> new ArrayList<>()).add(listing.price());
                groupPrices.computeIfAbsent(listing.groupKey(), ignored -> new ArrayList<>()).add(listing.price());
                basePrices.computeIfAbsent(listing.baseKey(), ignored -> new ArrayList<>()).add(listing.price());
                count++;
            }
        }
        sortAll(exactPrices);
        sortAll(groupPrices);
        sortAll(basePrices);
        return new MarketSnapshot(lastUpdated, bazaar == null ? Map.of() : bazaar,
                byExact, exactPrices, groupPrices, basePrices, List.of(), count);
    }

    public static MarketSnapshot fetch() throws IOException, InterruptedException {
        JsonObject bazaarJson = HypixelApi.getJson(HypixelApi.BAZAAR_URL);
        List<JsonObject> pages = HypixelApi.auctionPages();
        return from(bazaarJson, pages);
    }

    public static MarketSnapshot from(JsonObject bazaarJson, List<JsonObject> auctionPages) {
        // Bazaar data is only an optional valuation input. An unavailable bazaar must never block
        // the AH book from being parsed or displayed.
        JsonObject bazaarRoot = bazaarJson == null ? new JsonObject() : bazaarJson;
        long updated = bazaarRoot.has("lastUpdated") ? bazaarRoot.get("lastUpdated").getAsLong() : 0L;

        Map<String, BazaarQuote> bazaar = new HashMap<>();
        JsonObject products = bazaarRoot.getAsJsonObject("products");
        if (products != null) {
            for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
                JsonObject product = entry.getValue().getAsJsonObject();
                JsonObject quick = product.getAsJsonObject("quick_status");
                if (quick == null) {
                    continue;
                }
                // Careful: Hypixel names these from the opposite perspective to what you'd expect.
                // buy_summary holds the offers you BUY from (asks), sell_summary holds the orders
                // you SELL into (bids). That is why buyPrice is always above sellPrice.
                List<BazaarQuote.Level> asks = levels(product.getAsJsonArray("buy_summary"), true);
                List<BazaarQuote.Level> bids = levels(product.getAsJsonArray("sell_summary"), false);
                double topAsk = asks.isEmpty() ? quick.get("buyPrice").getAsDouble() : asks.get(0).price();
                double topBid = bids.isEmpty() ? quick.get("sellPrice").getAsDouble() : bids.get(0).price();

                double weekBuy = quick.has("buyMovingWeek") ? quick.get("buyMovingWeek").getAsDouble() : 0d;
                double weekSell = quick.has("sellMovingWeek") ? quick.get("sellMovingWeek").getAsDouble() : 0d;
                bazaar.put(entry.getKey(), new BazaarQuote(entry.getKey(), topBid, topAsk, weekBuy, weekSell, asks, bids));
            }
        }

        Map<String, List<Listing>> byExact = new HashMap<>();
        Map<String, List<Double>> exactPrices = new HashMap<>();
        Map<String, List<Double>> groupPrices = new HashMap<>();
        Map<String, List<Double>> basePrices = new HashMap<>();
        List<Auction> ending = new ArrayList<>();
        int count = 0;

        for (JsonObject page : auctionPages) {
            JsonArray auctions = page.getAsJsonArray("auctions");
            if (auctions == null) {
                continue;
            }
            for (JsonElement element : auctions) {
                JsonObject auction = element.getAsJsonObject();
                boolean isBin = auction.has("bin") && auction.get("bin").getAsBoolean();
                if (!isBin) {
                    // Non-BIN auctions are kept for the ending-soon snipe section.
                    if (!auction.has("end")) {
                        continue;
                    }
                    String name = auction.has("item_name") ? auction.get("item_name").getAsString() : "";
                    String tier = auction.has("tier") ? auction.get("tier").getAsString() : "?";
                    String uuid = auction.has("uuid") ? auction.get("uuid").getAsString() : "";
                    double starting = auction.has("starting_bid") ? auction.get("starting_bid").getAsDouble() : 0d;
                    double bid = auction.has("highest_bid_amount") ? auction.get("highest_bid_amount").getAsDouble() : 0d;
                    if (bid <= 0d) {
                        bid = starting;
                    }
                    long end = auction.get("end").getAsLong();
                    if (name.isEmpty() || uuid.isEmpty() || bid <= 0d) {
                        continue;
                    }
                    Nbt.Decoded decoded = Nbt.decode(auction.has("item_bytes") ? auction.get("item_bytes").getAsString() : null);
                    ItemVariant variant = ItemVariant.from(decoded == null ? null : decoded.extra(),
                            decoded == null ? null : decoded.name());
                    if (isExcludedCosmetic(name, variant.id(), decoded == null ? null : decoded.extra(),
                            decoded == null ? List.of() : decoded.lore())) {
                        continue;
                    }
                    String actualTier = variant.actualTier(tier);
                    ending.add(new Auction(uuid, name, actualTier, bid, starting, end, variant.id(), variant.signature(),
                            variant.exactKey(name, actualTier), variant.groupKey(name, actualTier),
                            variant.itemBaseKey(name, actualTier), variant.describe(), variant));
                    continue;
                }
                if (auction.has("claimed") && auction.get("claimed").getAsBoolean()) {
                    continue;
                }
                String name = auction.has("item_name") ? auction.get("item_name").getAsString() : "";
                String tier = auction.has("tier") ? auction.get("tier").getAsString() : "?";
                String uuid = auction.has("uuid") ? auction.get("uuid").getAsString() : "";
                double price = auction.has("starting_bid") ? auction.get("starting_bid").getAsDouble() : 0d;
                if (name.isEmpty() || uuid.isEmpty() || price <= 0) {
                    continue;
                }

                String itemBytes = auction.has("item_bytes") ? auction.get("item_bytes").getAsString() : null;
                Nbt.Decoded decoded = Nbt.decode(itemBytes);
                Map<String, Object> attributes = decoded == null ? null : decoded.extra();
                ItemVariant variant = ItemVariant.from(attributes, decoded == null ? null : decoded.name());
                // Never buy an unapplied skin, a rune, or any item carrying an applied rune.
                if (isExcludedCosmetic(name, variant.id(), attributes,
                        decoded == null ? List.of() : decoded.lore())) {
                    continue;
                }

                // Tier Boost displays one rarity higher; price the pet as what it actually is.
                String actualTier = variant.actualTier(tier);
                String exactKey = variant.exactKey(name, actualTier);
                String groupKey = variant.groupKey(name, actualTier);
                String baseKey = variant.itemBaseKey(name, actualTier);

                byExact.computeIfAbsent(exactKey, ignored -> new ArrayList<>())
                        .add(new Listing(uuid, name, actualTier, price, variant.id(), variant.signature(), exactKey, groupKey, baseKey, variant.describe(), variant));
                exactPrices.computeIfAbsent(exactKey, ignored -> new ArrayList<>()).add(price);
                groupPrices.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(price);
                basePrices.computeIfAbsent(baseKey, ignored -> new ArrayList<>()).add(price);
                count++;
            }
        }

        sortAll(exactPrices);
        sortAll(groupPrices);
        sortAll(basePrices);

        return new MarketSnapshot(updated, bazaar, byExact, exactPrices, groupPrices, basePrices, ending, count);
    }

    /** Order-book levels, best first; the API already sorts them, but do not rely on it. */
    private static List<BazaarQuote.Level> levels(JsonArray summary, boolean ascending) {
        List<BazaarQuote.Level> out = new ArrayList<>();
        if (summary == null) {
            return out;
        }
        for (JsonElement element : summary) {
            JsonObject row = element.getAsJsonObject();
            if (!row.has("pricePerUnit") || !row.has("amount")) {
                continue;
            }
            double price = row.get("pricePerUnit").getAsDouble();
            double amount = row.get("amount").getAsDouble();
            if (price > 0d && amount > 0d) {
                out.add(new BazaarQuote.Level(price, amount));
            }
        }
        out.sort((a, b) -> ascending
                ? Double.compare(a.price(), b.price())
                : Double.compare(b.price(), a.price()));
        return out;
    }

    /**
     * Unapplied skins, every rune item, every dye, and armor with an applied dye are never bought.
     * An applied skin is allowed to remain but contributes no value because variant keys ignore it.
     */
    public static boolean isExcludedCosmetic(String name, String id, Map<String, Object> attributes,
                                              List<String> lore) {
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        String upperId = id == null ? "" : id.toUpperCase(java.util.Locale.ROOT);
        return lowerName.contains("skin") || upperId.contains("SKIN")
                || lowerName.contains("rune") || upperId.contains("RUNE")
                || lowerName.endsWith("dye") || lowerName.contains(" dye")
                || upperId.contains("DYE")
                || (attributes != null && attributes.containsKey("runes"))
                || ItemVariant.hasAppliedDye(attributes, lore);
    }

    private static void sortAll(Map<String, List<Double>> prices) {
        for (List<Double> values : prices.values()) {
            values.sort(Comparator.naturalOrder());
        }
    }
}
