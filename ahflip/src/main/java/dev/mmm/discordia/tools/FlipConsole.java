package dev.mmm.discordia.tools;

import com.google.gson.JsonObject;
import dev.mmm.discordia.core.Flip;
import dev.mmm.discordia.core.FlipConfig;
import dev.mmm.discordia.core.FlipEngine;
import dev.mmm.discordia.core.GlobalSales;
import dev.mmm.discordia.core.HypixelApi;
import dev.mmm.discordia.core.ItemCatalog;
import dev.mmm.discordia.core.MarketSnapshot;
import dev.mmm.discordia.core.SoldStats;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Runs the engine without Minecraft. Useful for checking the maths against the live market.
 *
 * <pre>
 *   java -cp "build/core;libs/gson.jar" dev.mmm.discordia.tools.FlipConsole --budget 80m --margin 10
 * </pre>
 */
public final class FlipConsole {

    public static void main(String[] args) throws Exception {
        FlipConfig config = new FlipConfig();
        boolean fast = false;
        for (String arg : normalize(args)) {
            if (arg.equals("--fast")) {
                fast = true;
            } else if (arg.equals("--no-bazaar")) {
                config.bazaar = false;
            } else if (arg.equals("--no-ah")) {
                config.auctions = false;
            } else if (arg.startsWith("--budget=")) {
                config.budget = amount(arg.substring("--budget=".length()));
            } else if (arg.startsWith("--min-profit=")) {
                config.minProfit = amount(arg.substring("--min-profit=".length()));
            } else if (arg.startsWith("--margin=")) {
                config.minMarginPct = Double.parseDouble(arg.substring("--margin=".length()));
            } else if (arg.startsWith("--max-margin=")) {
                config.maxAuctionMarginPct = Double.parseDouble(arg.substring("--max-margin=".length()));
            } else if (arg.startsWith("--confidence=")) {
                config.minConfidence = Double.parseDouble(arg.substring("--confidence=".length()));
            } else if (arg.startsWith("--limit=")) {
                config.limit = Integer.parseInt(arg.substring("--limit=".length()));
            }
        }

        Path statsPath = Paths.get("build/discordia-sales.json");
        Path legacy = Paths.get("build/ahflip-sales.json");
        if (!java.nio.file.Files.exists(statsPath) && java.nio.file.Files.exists(legacy)) {
            statsPath = legacy;
        }
        SoldStats sold = SoldStats.load(statsPath);
        int catalogItems = ItemCatalog.register(sold);
        GlobalSales global = new GlobalSales(sold, Paths.get("build/discordia-global-sales.json"));

        System.out.printf("Fetching market (budget %.1fM, min margin %.0f%%, min profit %.0fk)...%n",
                config.budget / 1_000_000d, config.minMarginPct, config.minProfit / 1_000d);
        long started = System.currentTimeMillis();
        JsonObject ended = HypixelApi.getJson(HypixelApi.ENDED_URL);
        int tickSales = sold.ingest(ended);
        JsonObject bazaarJson = HypixelApi.getJson(HypixelApi.BAZAAR_URL);
        MarketSnapshot reference = MarketSnapshot.from(bazaarJson, HypixelApi.auctionPages());
        // --fast mimics the in-game loop: newest page only, scored against the full book.
        MarketSnapshot snapshot = fast
                ? MarketSnapshot.from(bazaarJson, List.of(HypixelApi.auctionPage(0)))
                : reference;
        java.util.List<MarketSnapshot.Listing> allListings = new java.util.ArrayList<>();
        for (java.util.List<MarketSnapshot.Listing> listings : snapshot.listingsByKey.values()) {
            allListings.addAll(listings);
        }
        for (MarketSnapshot.Listing listing : allListings) {
            if (!sold.hasLocalSales(listing.skyblockId())) {
                global.requestPriority(listing.skyblockId(), listing.variant().marketTag());
            }
        }
        for (MarketSnapshot.Listing listing : allListings) {
            if (sold.hasLocalSales(listing.skyblockId())) {
                global.request(listing.skyblockId(), listing.variant().marketTag());
            }
        }
        for (String id : snapshot.bazaar.keySet()) {
            global.request(id, id);
        }
        global.requestCatalog(ItemCatalog.ids());
        global.start();
        java.util.Map<String, Double> bazaarBuy = new java.util.HashMap<>();
        for (MarketSnapshot.BazaarQuote quote : reference.bazaar.values()) {
            bazaarBuy.put(quote.id(), quote.topAsk());
        }
        dev.mmm.discordia.core.NpcPrices npc;
        try {
            npc = dev.mmm.discordia.core.NpcPrices.fetch();
        } catch (Exception e) {
            npc = dev.mmm.discordia.core.NpcPrices.empty();
        }
        FlipEngine.Result result = new FlipEngine().compute(snapshot, reference, config, sold,
                new dev.mmm.discordia.core.AttributePricing(sold, bazaarBuy), npc);
        System.out.printf("NPC prices: %d items%n", npc.size());
        long elapsed = System.currentTimeMillis() - started;
        sold.save(statsPath);

        System.out.printf("Snapshot: %d candidates from a %d-auction book, %d bazaar products, fetched in %.1fs%n",
                snapshot.auctionCount, reference.auctionCount, snapshot.bazaar.size(), elapsed / 1000d);
        System.out.printf("Sales: %d new this tick, %d in the 7-day local window across %d sale items%n",
                tickSales, sold.total(), sold.trackedItems());
        System.out.printf("Catalog: %d game items, %d known item ids%n",
                catalogItems, sold.knownItems());
        System.out.printf("Global: %d cached, %d queued, %d pending, %d unavailable (SkyCofl 7-day summaries)%n",
                global.cached(), global.pending(), sold.pendingGlobal(), sold.unavailableGlobal());
        System.out.println("  health: " + global.health());
        global.save();

        print("BAZAAR ORDER FLIPS", result.bazaar());
        print("AUCTION BIN FLIPS", result.auctions());
        print("DETERMINISTIC INCOME", result.deterministic());
        print("AUCTION SNIPES (ENDING SOON)", result.sniping());

        System.out.println();
        System.out.println("Nothing was bought. Copy a command and run it yourself if you agree with the price.");
    }

    /** Accepts both {@code --flag=value} and {@code --flag value}. */
    private static String[] normalize(String[] args) {
        java.util.List<String> out = new java.util.ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--") && !arg.contains("=") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.add(arg + "=" + args[++i]);
            } else {
                out.add(arg);
            }
        }
        return out.toArray(String[]::new);
    }

    private static void print(String title, List<Flip> flips) {
        System.out.println("=== " + title + " (" + flips.size() + ") ===");
        if (flips.isEmpty()) {
            System.out.println("  none pass the current filters");
            System.out.println();
            return;
        }
        System.out.printf("  %-34s %-7s %10s %10s %9s %7s %8s %8s%n",
                "ITEM", "TIER", "BUY", "SELL", "PROFIT", "MARGIN", "EST/h", "SELLS IN");
        for (Flip flip : flips) {
            System.out.printf("  %-34s %-7s %10s %10s %9s %6.0f%% %8s %8s%n",
                    shorten(flip.name(), 34),
                    flip.tier(),
                    coins(flip.buyPrice()),
                    coins(flip.targetPrice()),
                    coins(flip.profit()),
                    flip.marginPct(),
                    flip.coinsPerHour() > 0 ? coins(flip.coinsPerHour()) : "-",
                    flip.minutesToSell() > 0 ? FlipEngine.duration(flip.minutesToSell()) : "-");
            if (!flip.command().isEmpty()) {
                System.out.println("      " + flip.command());
            }
            if (flip.detail().startsWith("ATTR") || flip.kind() == Flip.Kind.FORGE
                    || flip.kind() == Flip.Kind.SNIPE || flip.kind() == Flip.Kind.KAT
                    || flip.detail().contains("global volume pending")
                    || flip.detail().contains("global volume unavailable")) {
                System.out.println("      " + shorten(flip.detail(), 150));
            }
        }
        System.out.println();
    }

    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String coins(double value) {
        if (value >= 1_000_000_000d) {
            return String.format("%.2fB", value / 1_000_000_000d);
        }
        if (value >= 1_000_000d) {
            return String.format("%.2fM", value / 1_000_000d);
        }
        if (value >= 1_000d) {
            return String.format("%.1fk", value / 1_000d);
        }
        return String.format("%.0f", value);
    }

    private static double amount(String raw) {
        String value = raw.trim().toLowerCase();
        double multiplier = 1d;
        if (value.endsWith("k")) {
            multiplier = 1_000d;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = 1_000_000d;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("b")) {
            multiplier = 1_000_000_000d;
            value = value.substring(0, value.length() - 1);
        }
        return Double.parseDouble(value) * multiplier;
    }
}
