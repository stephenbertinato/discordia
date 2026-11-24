package dev.mmm.discordia.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mmm.discordia.core.Flip;
import dev.mmm.discordia.core.FlipConfig;
import dev.mmm.discordia.core.FlipEngine;
import dev.mmm.discordia.core.ItemVariant;
import dev.mmm.discordia.core.MarketSnapshot;
import dev.mmm.discordia.core.Rejections;
import dev.mmm.discordia.core.RiskModel;
import dev.mmm.discordia.core.SoldStats;
import dev.mmm.discordia.core.Tax;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, offline regression probe for the flip algorithm's safety gates.
 *
 * <p>Every case builds a synthetic book and asserts the decision, not just "a flip appeared":
 * fee arithmetic at each bracket boundary, the anchor and support rules, the thin-sample
 * manipulation guard, the plain-item weekly-median gate, the recombobulator and Wither-scroll
 * ceilings, the conservative exit floor, the hold horizon and the reason codes.
 *
 * <p>No HTTP, no local sale files, no Minecraft classes:
 * <pre>
 *   java -cp "build/classes;libs/gson.jar" dev.mmm.discordia.tools.AlgorithmGuardProbe
 * </pre>
 */
public final class AlgorithmGuardProbe {
    private static int checks;

    public static void main(String[] args) throws Exception {
        feeBrackets();
        claimTaxCap();
        aBidIsNeverABinPrice();
        riskModel();
        anchorGuards();
        laneParity();
        thinSampleManipulation();
        thickSalesArePricedConservatively();
        plainGlobalGates();
        recombobulatorCeiling();
        witherScrollCeiling();
        aThinBookWithRealSalesIsStillPriced();
        System.out.printf("%nALL GUARDS PASS (%d checks)%n", checks);
    }

    // ---------------------------------------------------------------- fees

    /**
     * The AH listing fee is charged at one bracket rate for the whole price, so net income really
     * does fall when a sale crosses 10M or 100M. Assert the exact bracket values instead of
     * pretending the curve is smooth; a flip whose profit depends on that cliff is a bad flip.
     */
    private static void feeBrackets() {
        section("AH fee brackets");
        close(Tax.ahListingFee(9_999_999d), 99_999.99d, 0.01d, "1% below 10M");
        close(Tax.ahListingFee(10_000_000d), 200_000d, 0.01d, "2% from 10M");
        close(Tax.ahListingFee(10_000_001d), 200_000.02d, 0.01d, "2% just above 10M");
        close(Tax.ahListingFee(100_000_000d), 2_000_000d, 0.01d, "2% at 100M");
        close(Tax.ahListingFee(100_000_001d), 2_500_000.025d, 0.01d, "2.5% above 100M");
        // Net is monotonic inside a bracket and drops only at the documented bracket boundaries.
        check(Tax.ahNet(9_999_999d) > Tax.ahNet(9_000_000d), "net must rise with the sale price");
        check(Tax.ahNet(10_000_000d) < Tax.ahNet(9_999_999d),
                "the 10M bracket cliff must be visible, not hidden");
        check(Tax.grossForNet(Tax.ahNet(37_450_000d)) >= 37_449_000d,
                "grossForNet must invert ahNet");
        System.out.println("PASS exact listing fee at 9.99M/10M/10.01M/100M/100.01M");
    }

    private static void claimTaxCap() {
        section("AH claim tax");
        close(Tax.ahClaimTax(1_000_000d), 0d, 1e-9d, "no claim tax at 1M");
        close(Tax.ahClaimTax(1_005_000d), 5_000d, 1e-6d, "clamped to sale - 1M just above 1M");
        close(Tax.ahClaimTax(1_010_000d), 10_000d, 1e-6d, "still clamped at 1.01M");
        close(Tax.ahClaimTax(50_000_000d), 500_000d, 1e-6d, "1% on a large sale");
        System.out.println("PASS claim tax is zero at 1M, 1% above, and never eats the first 1M");
    }

    // ---------------------------------------------------------------- parsing

    /** A non-BIN bid is never a purchase price, and a BIN price is the ask, not the top bid. */
    private static void aBidIsNeverABinPrice() {
        section("non-BIN is never a BIN purchase");
        long now = System.currentTimeMillis();

        JsonObject auction = new JsonObject();
        auction.addProperty("uuid", "00000000-0000-0000-0000-000000000001");
        auction.addProperty("item_name", "Auction Thing");
        auction.addProperty("tier", "RARE");
        auction.addProperty("starting_bid", 1_000d);
        auction.addProperty("highest_bid_amount", 5_000d);
        auction.addProperty("bin", false);
        auction.addProperty("start", now - 60_000L);
        auction.addProperty("end", now + 600_000L);

        JsonObject bin = new JsonObject();
        bin.addProperty("uuid", "00000000-0000-0000-0000-000000000002");
        bin.addProperty("item_name", "Bin Thing");
        bin.addProperty("tier", "RARE");
        bin.addProperty("starting_bid", 1_000d);
        bin.addProperty("highest_bid_amount", 999_999d);
        bin.addProperty("bin", true);
        bin.addProperty("start", now - 60_000L);
        bin.addProperty("end", now + 600_000L);

        JsonArray list = new JsonArray();
        list.add(auction);
        list.add(bin);
        JsonObject page = new JsonObject();
        page.add("auctions", list);

        MarketSnapshot parsed = MarketSnapshot.from(null, List.of(page));
        check(parsed.auctionCount == 1, "only the BIN auction may enter the book");
        check(parsed.endingAuctions.size() == 1, "the non-BIN auction must be kept for snipes only");
        check(parsed.endingAuctions.get(0).currentBid() == 5_000d,
                "the non-BIN bid must be read as a current bid");
        List<MarketSnapshot.Listing> listings = new ArrayList<>();
        for (List<MarketSnapshot.Listing> group : parsed.listingsByKey.values()) {
            listings.addAll(group);
        }
        check(listings.size() == 1, "exactly one BIN listing expected");
        check(listings.get(0).price() == 1_000d,
                "a BIN purchase price is starting_bid, never highest_bid_amount");
        System.out.println("PASS a 5k bid on a non-BIN auction never became a buy price");
    }

    // ---------------------------------------------------------------- risk model

    private static void riskModel() {
        section("risk model");
        RiskModel.Liquidity liquidity = RiskModel.liquidity(6d, 41);
        check(liquidity.known(), "6 sales/hour over 41 listings is a known rate");
        close(liquidity.expectedHours(), 7d, 1e-9d, "Little's law: (41+1)/6 hours");
        check(!RiskModel.liquidity(0d, 10).known(), "a zero rate is unknown, not instant");
        close(RiskModel.saleProbability(7d, 168d), 1d, 1e-4d, "a 7h hold inside a week always sells");
        check(RiskModel.saleProbability(200d, 24d) < 0.2d, "a 200h hold inside a day rarely sells");
        check(RiskModel.conservativeGross(10_000_000d, 9_000_000d, 2d) == 8_820_000d,
                "the lower bound is used when it is below the target, then the reserve");
        check(RiskModel.conservativeGross(10_000_000d, 0d, 2d) == 9_800_000d,
                "an unknown lower bound must not lift the target");
        check(RiskModel.conservativeGross(10_000_000d, 12_000_000d, 0d) == 10_000_000d,
                "a higher bound never raises the exit");
        List<Double> sorted = List.of(1d, 2d, 3d, 4d, 5d);
        check(RiskModel.lowerQuantile(sorted, 0.25d) == 2d, "p25 of five sorted prices is the second");
        System.out.println("PASS conservative exit, hold time and sale probability are monotonic");
    }

    // ---------------------------------------------------------------- anchors

    /**
     * A flip needs a unique cheapest listing and a supported next price. Five listings sharing the
     * cheapest price must all be refused, and an item with one lonely price above it is not a market.
     */
    private static void anchorGuards() {
        section("anchor and support guards");
        SoldStats sold = new SoldStats();
        seedGlobal(sold, "ANCHOR_RING", 10_000_000d, 40d);
        FlipConfig config = base();

        Book duplicate = new Book();
        for (int i = 0; i < 6; i++) {
            duplicate.add("dup-" + i, "Anchor Ring", "RARE", plain("ANCHOR_RING"), 8_000_000d);
        }
        FlipEngine engine = new FlipEngine();
        List<Flip> flips = engine.compute(duplicate.build(), duplicate.build(), config, sold, null, null).auctions();
        check(flips.isEmpty(), "six listings at the same price must never be a flip");
        check(engine.rejections().get(Rejections.Reason.NO_ANCHOR) == 6,
                "a duplicated price must be reported as NO_ANCHOR");
        System.out.println("PASS identical lowest price on every copy: 6 x NO_ANCHOR");

        // One cheap copy, five above it: the cheap one may be priced, the dearer ones may not.
        Book mixed = new Book();
        mixed.add("cheap", "Anchor Ring", "RARE", plain("ANCHOR_RING"), 8_000_000d);
        for (int i = 0; i < 5; i++) {
            mixed.add("dear-" + i, "Anchor Ring", "RARE", plain("ANCHOR_RING"), 11_000_000d);
        }
        engine = new FlipEngine();
        flips = engine.compute(mixed.build(), mixed.build(), config, sold, null, null).auctions();
        check(engine.rejections().get(Rejections.Reason.NO_ANCHOR) == 5,
                "five listings that are not the cheapest must be refused");
        check(!flips.isEmpty(), "the unique cheapest copy of a supported book must be priced");
        check(flips.get(0).buyPrice() == 8_000_000d, "only the cheapest copy can be a flip");
        System.out.println("PASS cheaper-than-this rejections: 5 x NO_ANCHOR, cheapest copy priced");

        // One lonely next price is not a market.
        Book lonely = new Book();
        lonely.add("lonely-cheap", "Anchor Ring", "RARE", plain("ANCHOR_RING"), 8_000_000d);
        lonely.add("lonely-next", "Anchor Ring", "RARE", plain("ANCHOR_RING"), 40_000_000d);
        lonely.add("lonely-mid", "Anchor Ring", "RARE", plain("ANCHOR_RING"), 55_000_000d);
        lonely.add("lonely-hi-1", "Anchor Ring", "RARE", plain("ANCHOR_RING"), 58_000_000d);
        lonely.add("lonely-hi-2", "Anchor Ring", "RARE", plain("ANCHOR_RING"), 57_000_000d);
        lonely.add("lonely-hi-3", "Anchor Ring", "RARE", plain("ANCHOR_RING"), 58_000_000d);
        engine = new FlipEngine();
        flips = engine.compute(lonely.build(), lonely.build(), config, sold, null, null).auctions();
        check(flips.isEmpty(), "a single lonely listing above the ask must not price a flip");
        check(engine.rejections().get(Rejections.Reason.THIN_SUPPORT) >= 1,
                "a lonely anchor must be reported as THIN_SUPPORT");
        System.out.println("PASS lonely 40M next listing with nothing near it: THIN_SUPPORT");
    }

    /**
     * The three lanes must agree. A full-book scan prices a listing against a pool that contains
     * it; page 0 and the SkyCofl fast lane price the same listing against a cached book that does
     * not. When those disagree, the mod announces a batch on the first full scan and then goes
     * silent, because every undercutting listing in the live lanes is thrown away as "no anchor".
     */
    private static void laneParity() {
        section("page-0 and fast-lane parity");
        SoldStats sold = new SoldStats();
        seedGlobal(sold, "LANE_RING", 10_000_000d, 20d);
        FlipConfig config = base();

        Book book = new Book();
        book.add("lane-cheap", "Lane Ring", "RARE", plain("LANE_RING"), 8_000_000d);
        for (int i = 0; i < 5; i++) {
            book.add("lane-dear-" + i, "Lane Ring", "RARE", plain("LANE_RING"), 11_000_000d);
        }
        MarketSnapshot reference = book.build();

        List<Flip> full = new FlipEngine().compute(reference, reference, config, sold, null, null).auctions();
        check(full.size() == 1, "the full book must price the cheap copy exactly once");

        MarketSnapshot page0 = MarketSnapshot.ofListings(List.of(book.get("lane-cheap")),
                System.currentTimeMillis(), Map.of());
        check(page0.auctionCount == 1, "the page-0 fixture must hold exactly one listing");
        List<Flip> live = new FlipEngine().compute(page0, reference, config, sold, null, null).auctions();
        check(live.size() == 1,
                "a live page-0 scan that undercuts the cached cheapest must still be a flip");
        close(live.get(0).targetPrice(), full.get(0).targetPrice(), 1d,
                "a page-0 scan and a full scan must agree on the target");
        close(live.get(0).buyPrice(), full.get(0).buyPrice(), 1d,
                "a page-0 scan and a full scan must agree on the buy price");
        System.out.println("PASS page-0 and full-book scan price the same listing identically");

        // Two sellers listing the same item at the same price in one scan: neither is unique.
        Book twins = new Book();
        twins.add("twin-a", "Lane Ring", "RARE", plain("LANE_RING"), 8_000_000d);
        twins.add("twin-b", "Lane Ring", "RARE", plain("LANE_RING"), 8_000_000d);
        MarketSnapshot twinSnapshot = MarketSnapshot.ofListings(twins.all(),
                System.currentTimeMillis(), Map.of());
        FlipEngine engine = new FlipEngine();
        List<Flip> twinFlips = engine.compute(twinSnapshot, reference, config, sold, null, null).auctions();
        check(twinFlips.isEmpty(), "two copies at the same price in one live scan must both be refused");
        check(engine.rejections().get(Rejections.Reason.NO_ANCHOR) == 2,
                "a live duplicate price must be reported as NO_ANCHOR");
        System.out.println("PASS two live copies at the same price: 2 x NO_ANCHOR");

        // A listing that is dearer than the cached cheapest is not a flip in any lane.
        Book dearer = new Book();
        dearer.add("dearer", "Lane Ring", "RARE", plain("LANE_RING"), 9_000_000d);
        MarketSnapshot dearerSnapshot = MarketSnapshot.ofListings(dearer.all(),
                System.currentTimeMillis(), Map.of());
        engine = new FlipEngine();
        twinFlips = engine.compute(dearerSnapshot, reference, config, sold, null, null).auctions();
        check(twinFlips.isEmpty(), "a live listing above the cached cheapest must be refused");
        System.out.println("PASS a live listing above the cached cheapest: refused");
    }

    /** A 1-4 sale sample cannot project a resale price many times above what the item clears at. */
    private static void thinSampleManipulation() {
        section("thin-sample manipulation guard");
        SoldStats sold = new SoldStats();
        seedGlobal(sold, "PUMP_RING", 50_000_000d, 20d);
        for (int i = 0; i < 4; i++) {
            seedSale(sold, "PUMP_RING", 5_000_000d, "pump-" + i);
        }
        Book book = new Book();
        book.add("pump-cheap", "Pump Ring", "RARE", plain("PUMP_RING"), 8_000_000d);
        for (int i = 0; i < 5; i++) {
            book.add("pump-dear-" + i, "Pump Ring", "RARE", plain("PUMP_RING"), 40_000_000d);
        }
        FlipEngine engine = new FlipEngine();
        List<Flip> flips = engine.compute(book.build(), book.build(), base(), sold, null, null).auctions();
        check(flips.isEmpty(), "a 40M anchor over four 5M sales must be refused");
        check(engine.rejections().get(Rejections.Reason.MANIPULATED_ANCHOR) >= 1,
                "the manipulated anchor must be reported as MANIPULATED_ANCHOR");
        System.out.println("PASS four 5M sales under a 40M ask: MANIPULATED_ANCHOR");
    }

    /** Thick evidence prices a flip, and the booked profit uses the lower quartile, not the ask. */
    private static void thickSalesArePricedConservatively() {
        section("thick sales are priced at a conservative exit");
        SoldStats sold = new SoldStats();
        seedGlobal(sold, "THICK_RING", 20_000_000d, 30d);
        double[] prices = {18_000_000d, 19_000_000d, 20_000_000d, 21_000_000d, 22_000_000d};
        for (int i = 0; i < prices.length; i++) {
            seedSale(sold, "THICK_RING", prices[i], "thick-" + i);
        }
        Book book = new Book();
        book.add("thick-cheap", "Thick Ring", "RARE", plain("THICK_RING"), 14_000_000d);
        for (int i = 0; i < 5; i++) {
            book.add("thick-dear-" + i, "Thick Ring", "RARE", plain("THICK_RING"), 22_000_000d);
        }
        FlipEngine engine = new FlipEngine();
        List<Flip> flips = engine.compute(book.build(), book.build(), base(), sold, null, null).auctions();
        check(!flips.isEmpty(), "twenty-two million against a 20M median with five sales is a flip");
        Flip flip = flips.get(0);
        check(flip.detail().contains("p25 of"), "the conservative exit must cite the realized p25");
        check(flip.detail().contains("floor "), "the alert must state the floor it survives");
        double profitAtAsk = Tax.ahProfit(flip.buyPrice(), flip.targetPrice());
        check(flip.profit() < profitAtAsk,
                "booked profit must be below the profit at the optimistic ask");
        check(flip.profit() > 0d, "a conservative exit must still be profitable");
        System.out.println(String.format("PASS thick sale: ask %s -> floor-booked +%s (ask-only +%s)",
                coins(flip.targetPrice()), coins(flip.profit()), coins(profitAtAsk)));
    }

    /** A plain item without a trusted weekly median is never shown; pending is not zero. */
    private static void plainGlobalGates() {
        section("plain-item weekly median gate");
        FlipConfig config = base();

        SoldStats thin = new SoldStats();
        seedGlobal(thin, "PLAIN_RING", 10_000_000d, 10d);
        soldCount(thin, "PLAIN_RING", 4);
        FlipEngine engine = new FlipEngine();
        List<Flip> flips = engine.compute(plainBook().build(), plainBook().build(), config, thin, null, null)
                .auctions();
        check(flips.isEmpty(), "a four-sale weekly summary must not price a plain item");
        check(engine.rejections().get(Rejections.Reason.GLOBAL_ZERO) >= 1,
                "a thin summary must be reported as GLOBAL_ZERO");
        System.out.println("PASS plain item with 4 weekly sales: GLOBAL_ZERO");

        SoldStats pending = new SoldStats();
        pending.setGlobalPending("PLAIN_RING");
        engine = new FlipEngine();
        flips = engine.compute(plainBook().build(), plainBook().build(), config, pending, null, null).auctions();
        check(flips.isEmpty(), "a pending weekly summary must not price a plain item");
        check(engine.rejections().get(Rejections.Reason.GLOBAL_PENDING) >= 1,
                "an unanswered summary must be reported as GLOBAL_PENDING");
        System.out.println("PASS plain item while Cofl has not answered: GLOBAL_PENDING");

        SoldStats trusted = new SoldStats();
        seedGlobal(trusted, "PLAIN_RING", 11_000_000d, 40d);
        engine = new FlipEngine();
        flips = engine.compute(plainBook().build(), plainBook().build(), config, trusted, null, null).auctions();
        check(!flips.isEmpty(), "a trusted weekly median must let a plain book flip through");
        System.out.println("PASS plain item with a trusted 400-sale weekly median: priced");

        // A mislisted cheap copy inside a thick market. The book alone can never justify a
        // nine-hundred-thousand-percent margin, but 200 weekly sales at a 15.6M median can: the
        // exit is external truth. Refusing this as IMPLAUSIBLE_MARGIN is how a real 15M flip that
        // the whole market trades every day gets thrown away.
        Book mislisted = new Book();
        mislisted.add("misl-cheap", "Plain Ring", "RARE", plain("PLAIN_RING"), 17d);
        for (int i = 0; i < 6; i++) {
            mislisted.add("misl-dear-" + i, "Plain Ring", "RARE", plain("PLAIN_RING"), 15_600_000d);
        }
        SoldStats thick = new SoldStats();
        seedGlobal(thick, "PLAIN_RING", 15_600_000d, 400d);
        engine = new FlipEngine();
        flips = engine.compute(mislisted.build(), mislisted.build(), config, thick, null, null).auctions();
        check(!flips.isEmpty(), "a 17-coin listing under a 15.6M verified median must be announced");
        check(flips.isEmpty() || flips.get(0).detail().contains("verified exit"),
                "the alert must cite the weekly sales that verified its exit");
        System.out.println("PASS mislisted copy: 17 coins under a 15.6M verified median is priced");

        // The same listing with a thin summary must stay refused: 6 sales prove nothing.
        SoldStats unverified = new SoldStats();
        soldCount(unverified, "PLAIN_RING", 6);
        engine = new FlipEngine();
        flips = engine.compute(mislisted.build(), mislisted.build(), config, unverified, null, null)
                .auctions();
        check(flips.isEmpty(), "a six-sale summary must never license an unbounded margin");
        System.out.println("PASS mislisted copy with 6 weekly sales: still refused");
    }

    /** A non-recombed copy is worth the recombed price minus the live recombobulator cost. */
    private static void recombobulatorCeiling() {
        section("recombobulator ceiling");
        SoldStats sold = new SoldStats();
        seedGlobal(sold, "RECOMB_RING", 55_000_000d, 20d);
        FlipConfig config = base();

        Book capped = new Book();
        capped.bazaar(recombQuote(10_200_000d));
        for (int i = 0; i < 3; i++) {
            capped.add("recomb-copy-" + i, "Recomb Ring", "RARE", recombed("RECOMB_RING"), 58_000_000d);
        }
        capped.add("recomb-cheap", "Recomb Ring", "RARE", plain("RECOMB_RING"), 50_000_000d);
        for (int i = 0; i < 5; i++) {
            capped.add("recomb-dear-" + i, "Recomb Ring", "RARE", plain("RECOMB_RING"), 58_000_000d);
        }
        FlipEngine engine = new FlipEngine();
        List<Flip> flips = engine.compute(capped.build(), capped.build(), config, sold, null, null).auctions();
        check(flips.isEmpty(),
                "a 50M non-recomb listing against a 58M recombed copy must be refused");
        System.out.println("PASS non-recomb 50M vs recombed 58M with a 10.2M recomb: refused");

        Book control = new Book();
        control.add("no-recomb-cheap", "Recomb Ring", "RARE", plain("RECOMB_RING"), 50_000_000d);
        for (int i = 0; i < 5; i++) {
            control.add("no-recomb-dear-" + i, "Recomb Ring", "RARE", plain("RECOMB_RING"), 58_000_000d);
        }
        engine = new FlipEngine();
        flips = engine.compute(control.build(), control.build(), config, sold, null, null).auctions();
        check(!flips.isEmpty(), "the same book without a recombed copy is a normal flip");
        System.out.println("PASS same book with no recombed copy: priced normally");
    }

    /**
     * A blade missing scrolls is worth a full one minus the instant bazaar cost of the scrolls.
     * An unfillable scroll book, or a scroll bill bigger than the item cap, ends the trade.
     */
    private static void witherScrollCeiling() {
        section("Wither scroll ceiling and capital");
        SoldStats sold = new SoldStats();
        seedGlobal(sold, "HYPERION", 30_000_000d, 20d);
        FlipConfig config = base();

        FlipEngine engine = new FlipEngine();
        List<Flip> flips = engine.compute(bladeBook(2_000_000d).build(), bladeBook(2_000_000d).build(),
                config, sold, null, null).auctions();
        check(!flips.isEmpty(), "a partial blade with a deep scroll book must be priced");
        Flip flip = flips.get(0);
        check(flip.targetPrice() <= 33_600_000d,
                "the partial blade target must be capped by the full blade minus scroll cost");
        check(flip.detail().contains("adds 3 scrolls"), "the scroll add-on cost must be shown");
        System.out.println(String.format("PASS partial blade: %s -> %s capped by the full-blade exit",
                coins(flip.buyPrice()), coins(flip.targetPrice())));

        engine = new FlipEngine();
        flips = engine.compute(bladeBook(0d).build(), bladeBook(0d).build(), config, sold, null, null).auctions();
        check(flips.isEmpty(), "an unfillable scroll book must end the blade trade");
        check(engine.rejections().get(Rejections.Reason.SCROLL_UNAVAILABLE) >= 1,
                "a missing scroll must be reported as SCROLL_UNAVAILABLE");
        System.out.println("PASS empty scroll book: SCROLL_UNAVAILABLE");

        FlipConfig small = base();
        small.budget = 34_000_000d;
        small.maxBudgetShare = 1d;
        engine = new FlipEngine();
        flips = engine.compute(bladeBook(2_000_000d).build(), bladeBook(2_000_000d).build(),
                small, sold, null, null).auctions();
        check(flips.isEmpty(), "a blade whose scrolls push it past the item cap must be refused");
        check(engine.rejections().get(Rejections.Reason.OVER_BUDGET) >= 1,
                "buy price plus scroll cost over the cap must be reported as OVER_BUDGET");
        System.out.println("PASS 30M blade + 6M scrolls over a 34M cap: OVER_BUDGET");
    }

    /**
     * A rare item can trade more often than it is listed. Realized sales lower the listing floor,
     * and only the listing floor: the same book without sales stays refused.
     */
    private static void aThinBookWithRealSalesIsStillPriced() {
        section("thin book rescued by realized sales");
        SoldStats sold = new SoldStats();
        seedGlobal(sold, "RARE_RING", 13_000_000d, 10d);
        for (int i = 0; i < 5; i++) {
            seedSale(sold, "RARE_RING", 13_000_000d + i * 100_000d, "rare-" + i);
        }
        Book book = new Book();
        book.add("rare-cheap", "Rare Ring", "RARE", plain("RARE_RING"), 10_000_000d);
        book.add("rare-next-1", "Rare Ring", "RARE", plain("RARE_RING"), 14_000_000d);
        book.add("rare-next-2", "Rare Ring", "RARE", plain("RARE_RING"), 14_200_000d);

        FlipEngine engine = new FlipEngine();
        List<Flip> flips = engine.compute(book.build(), book.build(), base(), sold, null, null).auctions();
        check(!flips.isEmpty(), "three listings backed by five realized sales must still be priced");
        System.out.println("PASS three-listing book with five realized sales: priced");

        SoldStats bare = new SoldStats();
        seedGlobal(bare, "RARE_RING", 13_000_000d, 10d);
        engine = new FlipEngine();
        flips = engine.compute(book.build(), book.build(), base(), bare, null, null).auctions();
        check(flips.isEmpty(), "the same three-listing book without sale evidence must be refused");
        check(engine.rejections().get(Rejections.Reason.THIN_POOL) >= 1,
                "a thin book without evidence must be reported as THIN_POOL");
        System.out.println("PASS same three-listing book with no sales: THIN_POOL");

        // Every accepted flip must have survived the risk-adjusted expectation too.
        SoldStats slow = new SoldStats();
        seedGlobal(slow, "SLOW_RING", 40_000_000d, 0.02d);
        engine = new FlipEngine();
        flips = engine.compute(slowBook().build(), slowBook().build(), base(), slow, null, null).auctions();
        check(flips.isEmpty(), "an item that trades twice a month must be refused as a capital trap");
        check(engine.rejections().get(Rejections.Reason.CAPITAL_TRAP) >= 1,
                "a hold beyond the horizon must be reported as CAPITAL_TRAP");
        System.out.println("PASS two sales a month over a 6-listing pool: CAPITAL_TRAP");
    }

    // ---------------------------------------------------------------- fixtures

    private static FlipConfig base() {
        FlipConfig config = new FlipConfig();
        config.bazaar = false;
        config.deterministic = false;
        config.snipes = false;
        config.auctions = true;
        config.budget = 1_000_000_000d;
        config.maxBudgetShare = 1d;
        config.maxItemCost = 0d;
        config.limit = 10;
        return config;
    }

    private static ItemVariant plain(String id) {
        return new ItemVariant(id, "", 0, 0, 0, "0", List.of(), "", "", false, List.of());
    }

    private static ItemVariant recombed(String id) {
        return new ItemVariant(id, "", 0, 0, 0, "0", List.of(), "", "", true, List.of());
    }

    private static final List<String> ALL_SCROLLS = List.of(
            "IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL");

    private static ItemVariant blade(List<String> scrolls) {
        return new ItemVariant("HYPERION", "", 0, 0, 0, "0", List.of(), "", "", false, scrolls);
    }

    private static MarketSnapshot.BazaarQuote recombQuote(double ask) {
        return new MarketSnapshot.BazaarQuote("RECOMBOBULATOR_3000", ask - 200_000d, ask, 0d, 0d,
                List.of(new MarketSnapshot.BazaarQuote.Level(ask, 64d)),
                List.of(new MarketSnapshot.BazaarQuote.Level(ask - 200_000d, 64d)));
    }

    /** A book of six identical plain listings: one 8.9M entry under a 10.89M pool. */
    private static Book plainBook() {
        Book book = new Book();
        book.add("plain-cheap", "Plain Ring", "RARE", plain("PLAIN_RING"), 8_900_000d);
        for (int i = 0; i < 5; i++) {
            book.add("plain-dear-" + i, "Plain Ring", "RARE", plain("PLAIN_RING"), 10_890_000d);
        }
        return book;
    }

    private static Book slowBook() {
        Book book = new Book();
        book.add("slow-cheap", "Slow Ring", "RARE", plain("SLOW_RING"), 30_000_000d);
        for (int i = 0; i < 5; i++) {
            book.add("slow-dear-" + i, "Slow Ring", "RARE", plain("SLOW_RING"), 40_000_000d);
        }
        return book;
    }

    /**
     * Five partial Hyperions under five full ones. {@code scrollAsk} of 0 leaves the scroll book
     * empty, which is the incomplete-depth case.
     */
    private static Book bladeBook(double scrollAsk) {
        Book book = new Book();
        for (int i = 0; i < 5; i++) {
            book.add("blade-full-" + i, "Hyperion", "LEGENDARY", blade(ALL_SCROLLS), 40_000_000d);
        }
        book.add("blade-partial-cheap", "Hyperion", "LEGENDARY", blade(List.of()), 30_000_000d);
        for (int i = 0; i < 5; i++) {
            book.add("blade-partial-" + i, "Hyperion", "LEGENDARY", blade(List.of()), 35_000_000d);
        }
        if (scrollAsk > 0d) {
            for (String scroll : ALL_SCROLLS) {
                book.bazaar(new MarketSnapshot.BazaarQuote(scroll, scrollAsk - 100_000d, scrollAsk, 0d, 0d,
                        List.of(new MarketSnapshot.BazaarQuote.Level(scrollAsk, 128d)),
                        List.of(new MarketSnapshot.BazaarQuote.Level(scrollAsk - 100_000d, 128d))));
            }
        }
        return book;
    }

    private static void seedGlobal(SoldStats sold, String id, double median, double perDay) {
        sold.setGlobal(id, new SoldStats.Stats(200, median, median, perDay, false, false));
    }

    private static void soldCount(SoldStats sold, String id, int count) {
        sold.setGlobal(id, new SoldStats.Stats(count, 10_000_000d, 10_000_000d, 4d, false, false));
    }

    @SuppressWarnings("unchecked")
    private static void seedSale(SoldStats sold, String id, double price, String uuid) {
        try {
            Field field = SoldStats.class.getDeclaredField("sales");
            field.setAccessible(true);
            Map<String, Deque<SoldStats.Sale>> sales = (Map<String, Deque<SoldStats.Sale>>) field.get(sold);
            Deque<SoldStats.Sale> queue = sales.computeIfAbsent(id, ignored -> new ArrayDeque<>());
            queue.add(new SoldStats.Sale(uuid, price, System.currentTimeMillis(),
                    0, "", 0, 0, "", "", "", false, "", false));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not seed a local sale", e);
        }
    }

    /** Fluent snapshot builder: keeps the three price pools and the bazaar book consistent. */
    private static final class Book {
        private final Map<String, List<MarketSnapshot.Listing>> byKey = new LinkedHashMap<>();
        private final Map<String, List<Double>> exact = new LinkedHashMap<>();
        private final Map<String, List<Double>> group = new LinkedHashMap<>();
        private final Map<String, List<Double>> base = new LinkedHashMap<>();
        private final Map<String, MarketSnapshot.BazaarQuote> bazaar = new LinkedHashMap<>();

        Book add(String uuid, String name, String tier, ItemVariant variant, double price) {
            String exactKey = variant.exactKey(name, tier);
            String groupKey = variant.groupKey(name, tier);
            String baseKey = variant.itemBaseKey(name, tier);
            byKey.computeIfAbsent(exactKey, ignored -> new ArrayList<>())
                    .add(new MarketSnapshot.Listing(uuid, name, tier, price, variant.id(),
                            variant.signature(), exactKey, groupKey, baseKey, variant.describe(), variant));
            exact.computeIfAbsent(exactKey, ignored -> new ArrayList<>()).add(price);
            group.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(price);
            base.computeIfAbsent(baseKey, ignored -> new ArrayList<>()).add(price);
            return this;
        }

        Book bazaar(MarketSnapshot.BazaarQuote quote) {
            bazaar.put(quote.id(), quote);
            return this;
        }

        /** Every listing in the book, in insertion order. */
        List<MarketSnapshot.Listing> all() {
            List<MarketSnapshot.Listing> out = new ArrayList<>();
            for (List<MarketSnapshot.Listing> group : byKey.values()) {
                out.addAll(group);
            }
            return out;
        }

        MarketSnapshot.Listing get(String uuid) {
            for (MarketSnapshot.Listing listing : all()) {
                if (listing.uuid().equals(uuid)) {
                    return listing;
                }
            }
            throw new IllegalArgumentException("no listing " + uuid + " in the probe book");
        }

        MarketSnapshot build() {
            for (List<Double> prices : exact.values()) {
                prices.sort(Comparator.naturalOrder());
            }
            for (List<Double> prices : group.values()) {
                prices.sort(Comparator.naturalOrder());
            }
            for (List<Double> prices : base.values()) {
                prices.sort(Comparator.naturalOrder());
            }
            int count = 0;
            for (List<MarketSnapshot.Listing> listings : byKey.values()) {
                count += listings.size();
            }
            try {
                Constructor<MarketSnapshot> constructor = MarketSnapshot.class.getDeclaredConstructor(
                        long.class, Map.class, Map.class, Map.class, Map.class, Map.class, List.class, int.class);
                constructor.setAccessible(true);
                return constructor.newInstance(0L, bazaar, byKey, exact, group, base, List.of(), count);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("could not build a probe snapshot", e);
            }
        }
    }

    // ---------------------------------------------------------------- assertions

    private static void section(String title) {
        System.out.println();
        System.out.println("--- " + title);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void close(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance,
                message + ": expected " + expected + " but was " + actual);
    }

    private static String coins(double value) {
        return String.format("%.2fM", value / 1_000_000d);
    }
}
