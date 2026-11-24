package dev.mmm.discordia.tools;

import dev.mmm.discordia.core.Flip;
import dev.mmm.discordia.core.FlipConfig;
import dev.mmm.discordia.core.FlipEngine;
import dev.mmm.discordia.core.ItemVariant;
import dev.mmm.discordia.core.MarketSnapshot;
import dev.mmm.discordia.core.SoldStats;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, offline engine probe for the global-sales gate.
 *
 * <p>Builds a six-listing BIN book for an item with no local sale rows:
 * <ul>
 *   <li>{@code TITANIUM_RING}: global summary has 206 sales, so the book-only flip must survive.</li>
 *   <li>{@code ZERO_RING}: global summary is an explicit empty result, so the same book must be
 *       suppressed.</li>
 * </ul>
 *
 * <p>This intentionally uses no HTTP, no local sale data, and no Minecraft classes. Run with:
 * <pre>
 *   java -cp "build/classes;libs/gson.jar" dev.mmm.discordia.tools.GlobalGateProbe
 * </pre>
 */
public final class GlobalGateProbe {
    private static final long BUY = 8_900_000L;
    private static final long NEXT = 10_890_000L;

    public static void main(String[] args) throws Exception {
        SoldStats sold = new SoldStats();
        sold.setGlobal("TITANIUM_RING", new SoldStats.Stats(206, 7_999_989d, 7_999_989d, 29.43d, false, false));
        sold.setGlobal("SYNTHETIC_ZERO_RING", SoldStats.EMPTY);
        sold.setGlobal("GYROKINETIC_WAND", new SoldStats.Stats(210, 25_000_000d, 25_000_000d, 30d, false, false));
        sold.setGlobal("GYROKINETIC_CONTROL", new SoldStats.Stats(210, 50_000_000d, 50_000_000d, 30d, false, false));
        seedLocalSale(sold, "LOCAL_CONFLICT_RING", 5_000_000d);
        sold.setGlobal("LOCAL_CONFLICT_RING", SoldStats.EMPTY);
        sold.setGlobal("THICK_FLUCTUATION_RING", new SoldStats.Stats(210, 25_000_000d, 25_000_000d, 30d, false, false));
        for (int i = 0; i < 5; i++) {
            seedLocalSale(sold, "THICK_FLUCTUATION_RING", 40_000_000d + i * 100_000d, "probe-thick-" + i);
        }

        FlipConfig config = new FlipConfig();
        config.bazaar = false;
        config.deterministic = false;
        config.snipes = false;
        config.auctions = true;
        config.budget = 1_000_000_000d;
        config.limit = 10;

        MarketSnapshot noBazaar = MarketSnapshot.from(null, List.of());
        check(noBazaar.bazaar.isEmpty(), "missing bazaar data was not represented as empty");
        System.out.println("PASS missing bazaar data: auction parsing remains available");

        // Buy below the 8M weekly median and ask 8.6M: a real discount into a real market. The old
        // 8.9M -> 10.89M book bought 11% over the median and asked 36% over it, which is the pump
        // pattern the target cap now refuses.
        MarketSnapshot titaniumBook = book("TITANIUM_RING", "Titanium Ring", "RARE",
                7_000_000d, 8_600_000d);
        MarketSnapshot zeroBook = book("SYNTHETIC_ZERO_RING", "Synthetic Zero Ring", "RARE");
        FlipEngine engine = new FlipEngine();

        List<Flip> titanium = engine.compute(titaniumBook, titaniumBook, config, sold, null, null).auctions();
        check(!titanium.isEmpty(), "Titanium with a 206-sale global summary was suppressed");
        Flip titaniumFlip = titanium.get(0);
        check(titaniumFlip.name().equals("Titanium Ring"), "unexpected Titanium probe flip: " + titaniumFlip.name());
        check(titaniumFlip.detail().contains("book only"), "Titanium did not take the book-only path");
        check(!titaniumFlip.detail().contains("global volume pending"),
                "Titanium was still pending after receiving a global summary");
        check(!sold.hasLocalSales("TITANIUM_RING"), "probe accidentally had local Titanium sales");

        List<Flip> zero = engine.compute(zeroBook, zeroBook, config, sold, null, null).auctions();
        if (!zero.isEmpty()) {
            throw new AssertionError("Zero-sale control escaped the zero-sale gate: " + zero.get(0).name());
        }
        check(sold.stats("SYNTHETIC_ZERO_RING").count() == 0
                        && !sold.stats("SYNTHETIC_ZERO_RING").pending(),
                "Synthetic zero-ring stats were not a resolved empty result");

        MarketSnapshot pumped = book("GYROKINETIC_WAND", "Gyrokinetic Wand", "EPIC",
                40_000_000d, 45_770_000d);
        List<Flip> pumpedFlips = engine.compute(pumped, pumped, config, sold, null, null).auctions();
        check(pumpedFlips.isEmpty(), "A 40M listing against a 25M weekly median escaped the price guard");
        System.out.println("PASS pumped Gyrokinetic-like book: 40M entry rejected against 25M weekly median");

        // A 40M entry must clear a 9.5% price-scaled hurdle now, so the control is given a real gap
        // (a 50M exit) instead of the 8.26% the old flat 8% gate used to wave through.
        MarketSnapshot control = book("GYROKINETIC_CONTROL", "Gyrokinetic Control", "EPIC",
                40_000_000d, 50_000_000d);
        engine.resetRejections();
        List<Flip> controlFlips = engine.compute(control, control, config, sold, null, null).auctions();
        check(!controlFlips.isEmpty(), "The weekly price guard suppressed a listing supported by a 50M"
                + " median; rejections=" + engine.rejections().snapshot());

        // Five recent 40M sales are 2% of a 210-sale week at a 25M median. They cannot reprice the
        // exit 52% above the weekly median: that is the PORTALIZER pump, one day of sales lifting
        // every listing until the entry looks cheap.
        MarketSnapshot thick = book("THICK_FLUCTUATION_RING", "Thick Fluctuation Ring", "RARE",
                30_000_000d, 38_000_000d);
        List<Flip> thickFlips = engine.compute(thick, thick, config, sold, null, null).auctions();
        check(thickFlips.isEmpty(),
                "five recent 40M sales must not lift a 30M entry against a 25M weekly median");
        System.out.println("PASS thick local spike: 30M entry refused against a 210-sale 25M median");

        // The same evidence still counts when it agrees with the weekly market.
        MarketSnapshot consistent = book("THICK_FLUCTUATION_RING", "Thick Fluctuation Ring", "RARE",
                22_000_000d, 26_000_000d);
        List<Flip> consistentFlips = engine.compute(consistent, consistent, config, sold, null, null)
                .auctions();
        check(!consistentFlips.isEmpty(),
                "a 22M entry under a 25M weekly median must still be priced");
        System.out.println("PASS thick local evidence under the median: 22M entry priced");

        System.out.printf("PASS global Titanium: %s -> %s (+%s, %.0f%%)%n",
                coins(titaniumFlip.buyPrice()), coins(titaniumFlip.targetPrice()),
                coins(titaniumFlip.profit()), titaniumFlip.marginPct());
        System.out.println("  detail: " + titaniumFlip.detail());
        SoldStats.Stats conflict = sold.stats("LOCAL_CONFLICT_RING");
        check(conflict.count() == 1 && !conflict.pending() && !conflict.unavailable(),
                "global zero erased a locally observed sale");
        System.out.println("PASS SYNTHETIC_ZERO_RING (global 0, local 0): suppressed as a control");
        System.out.println("PASS LOCAL_CONFLICT_RING: local sale wins over a global zero");
    }

    private static void seedLocalSale(SoldStats sold, String id, double price) throws Exception {
        seedLocalSale(sold, id, price, "probe-local");
    }

    @SuppressWarnings("unchecked")
    private static void seedLocalSale(SoldStats sold, String id, double price, String uuid) throws Exception {
        Field field = SoldStats.class.getDeclaredField("sales");
        field.setAccessible(true);
        Map<String, Deque<SoldStats.Sale>> sales = (Map<String, Deque<SoldStats.Sale>>) field.get(sold);
        Deque<SoldStats.Sale> queue = sales.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        queue.add(new SoldStats.Sale(uuid, price, System.currentTimeMillis(),
                0, "", 0, 0, "", "", "", false, "", false));
    }

    private static MarketSnapshot book(String id, String name, String tier) throws Exception {
        return book(id, name, tier, BUY, NEXT);
    }

    private static MarketSnapshot book(String id, String name, String tier, double buy, double next) throws Exception {
        ItemVariant variant = new ItemVariant(id, "", 0, 0, 0, "0", List.of(), "", "", false, List.of());
        String exactKey = variant.exactKey(name, tier);
        String groupKey = variant.groupKey(name, tier);
        String baseKey = variant.itemBaseKey(name, tier);
        String signature = variant.signature();

        List<MarketSnapshot.Listing> listings = new ArrayList<>();
        List<Double> prices = new ArrayList<>();
        listings.add(listing(id + "-cheap", name, tier, buy, exactKey, groupKey, baseKey, signature, variant));
        prices.add(buy);
        for (int i = 1; i <= 5; i++) {
            listings.add(listing(id + "-" + i, name, tier, next, exactKey, groupKey, baseKey, signature, variant));
            prices.add(next);
        }

        Map<String, List<MarketSnapshot.Listing>> byKey = new LinkedHashMap<>();
        byKey.put(exactKey, listings);
        Map<String, List<Double>> exact = new LinkedHashMap<>();
        exact.put(exactKey, prices);
        Map<String, List<Double>> group = new LinkedHashMap<>();
        group.put(groupKey, prices);
        Map<String, List<Double>> base = new LinkedHashMap<>();
        base.put(baseKey, prices);

        Constructor<MarketSnapshot> constructor = MarketSnapshot.class.getDeclaredConstructor(
                long.class,
                Map.class,
                Map.class,
                Map.class,
                Map.class,
                Map.class,
                List.class,
                int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                0L,
                Map.of(),
                byKey,
                exact,
                group,
                base,
                List.of(),
                listings.size());
    }

    private static MarketSnapshot.Listing listing(String uuid, String name, String tier, double price,
                                                  String exactKey, String groupKey, String baseKey,
                                                  String signature, ItemVariant variant) {
        return new MarketSnapshot.Listing(uuid, name, tier, price, variant.id(), signature,
                exactKey, groupKey, baseKey, variant.describe(), variant);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String coins(double value) {
        return String.format("%.2fM", value / 1_000_000d);
    }
}
