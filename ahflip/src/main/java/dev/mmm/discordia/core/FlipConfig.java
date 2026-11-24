package dev.mmm.discordia.core;

/** Filters. Defaults are deliberately conservative: fewer flips, fewer bags. */
public final class FlipConfig {
    /**
     * Total coins you are willing to deploy right now. In game this is the purse, re-read every
     * pulse; in the console it is {@code --budget}. It is the only cost ceiling there is.
     */
    public double budget = 50_000_000d;
    /** Absolute minimum profit per flip, after tax. */
    public double minProfit = 400_000d;
    /** Auction: minimum return on the buy price. */
    public double minMarginPct = 8d;
    /** Bazaar: order spreads are thin, so this is separate from the auction threshold. */
    public double minBazaarMarginPct = 0.2d;
    /** Bazaar: above this margin the book is thin or mispriced, not an opportunity. */
    public double maxBazaarMarginPct = 15d;
    /** Bazaar: ignore absurd unit prices caused by 0.1-coin bids. */
    public double minBazaarPrice = 10d;
    /** Auction: reject anything above this margin. Huge margins mean illiquid, manipulated or mis-keyed. */
    public double maxAuctionMarginPct = 60d;
    /** Auction: reject price clusters that disagree too much (mixed variants, fake listings). */
    public double maxRelativeSpread = 0.6d;
    /** Auction: minimum confidence score before a listing is shown. */
    public double minConfidence = 0.35d;
    /** Bazaar: minimum profit per unit, before volume is considered. */
    public double minBazaarUnitProfit = 1d;
    /** Bazaar: minimum estimated coins/hour after the capture rate. */
    public double minCoinsPerHour = 50_000d;
    /** Bazaar: minimum units moved per day (from the API's weekly volume). */
    public double minBazaarSalesPerDay = 1_000d;
    /** Auction: minimum number of live BINs before we trust the reference price. */
    public int minAuctionListings = 6;
    /** Auction: minimum realized sales/day once enough sale data exists for the item. */
    public double minAuctionSalesPerDay = 1d;
    /** Auction: realized sales of the same variant required before it can appear as a flip. */
    public int minAuctionSalesForFlip = 1;
    /** Auction: below this many sales the listing anchor is used, with a higher margin bar. */
    public int fullSalesTrust = 5;
    public double thinSalesMarginPct = 5d;
    /** Thin samples: never project above the realized sale median. */
    public double maxThinAnchorOverSales = 1.0d;
    /**
     * Plain items: never buy more than this multiple of the market-wide weekly median. A pumped
     * thin book can otherwise make an inflated current ask look like the resale price.
     */
    public double maxBuyOverGlobalMedian = 1.15d;
    /** Plain items with several recent exact-variant sales get a little more room for fluctuation. */
    public double maxBuyOverGlobalMedianWithSales = 1.25d;
    /** Plain items: never project a resale target above this multiple of the weekly median. */
    public double maxTargetOverGlobalMedian = 1.40d;
    /** Weekly sales required before the market-wide median is trusted. */
    public int minGlobalSalesForPriceGuard = 8;
    /**
     * Weekly sales required before the market-wide median is treated as an <em>independently
     * verified exit</em> for a plain item. Hundreds of sales at a median are stronger evidence than
     * any book, so a listing far under that median is genuinely cheap, not an impossible margin.
     * Below this count the ordinary margin cap applies unchanged.
     */
    public int minGlobalSalesForTrustedExit = 50;
    /** Plain items: do not show them until Cofl provides enough weekly sales to price them. */
    public boolean requireGlobalPriceForPlainFlips = true;
    /**
     * The margin hurdle grows with the buy price, because a big flip locks up big capital. Below
     * {@link #marginScaleFrom} the plain {@link #minMarginPct} applies; every
     * {@link #marginScaleStep} coins above it adds {@link #marginScalePct}, up to
     * {@link #maxScaledMarginPct}. A 500M flip has to earn as if 500M is at stake, not 8%.
     */
    public double marginScaleFrom = 10_000_000d;
    public double marginScaleStep = 20_000_000d;
    public double marginScalePct = 1d;
    public double maxScaledMarginPct = 20d;
    /** Keep strong book gaps even when no same-variant sale exists; they are labeled book-only. */
    public boolean bookOnlyFlips = true;
    public double bookOnlyMinProfit = 1_000_000d;
    public double bookOnlyMinMarginPct = 8d;
    public double bookOnlyMinConfidence = 0.35d;
    public int bookOnlyMinSupport = 2;
    /** Auction: listings near the resale anchor needed to trust that anchor. */
    public int minAuctionSupport = 2;
    /**
     * Auction: a lower listing floor is allowed when the exact variant has at least
     * {@link #fullSalesTrust} realized sales. A rare item can trade more often than it is listed,
     * and realized sales are stronger evidence than a thin book. Spread, support and anchor bars
     * never move; only the number of listings needed to price from does.
     */
    public int minVariantListingsWithSales = 3;
    /**
     * Auction: the hard gate uses this quantile of realized sales instead of the median, when there
     * are at least {@link #fullSalesTrust} sales to measure it from.
     */
    public double conservativeSalesQuantile = 0.25d;
    /**
     * Auction: reserve taken off the exit for price drift, a second listing fee and slippage.
     * Profit is booked after this reserve, not before it.
     */
    public double riskBufferPct = 1.5d;
    /** Auction: maximum expected hold, in hours; 0 disables the gate. */
    public double maxHoldHours = 168d;
    /** Book-only flips have no sale evidence for their target, so they get a tighter horizon. */
    public double maxBookOnlyHoldHours = 72d;
    /** Percent added to the margin requirement when the hold time is unknown. */
    public double unknownLiquidityMarginPremiumPct = 2d;
    /** Markdown assumed when an item has to be relisted to move it. */
    public double unsoldMarkdownPct = 10d;
    /** Opportunity cost of coins tied up in a listed item, percent per day. */
    public double capitalChargePerDayPct = 0.5d;
    /** Reject a candidate whose expected value after sale risk and locked capital is not positive. */
    public boolean requirePositiveRiskAdjusted = true;
    /** Craft-cost lane: price recipes from live ingredients and cap auctions at replacement cost. */
    public boolean crafts = true;
    /** Craft flips: minimum profit after the conservative exit. */
    public double minCraftProfit = 250_000d;
    /** Craft flips: minimum return on the ingredient bill. */
    public double minCraftMarginPct = 10d;
    /** Craft flips: above this margin the output book is too thin to be a market. */
    public double maxCraftMarginPct = 150d;
    /** Craft flips: how deep the recipe tree may recurse before the floor is unknown. */
    public int maxCraftDepth = 6;
    /** Craft flips: the output must move at least this many units a day. */
    public double minCraftSalesPerDay = 1d;
    /**
     * Auctions: a listing's resale target is capped at its replacement cost plus this much. Buying
     * far above what the item costs to build is the mistake a craft floor exists to prevent.
     */
    public double maxCraftPremiumPct = 25d;
    /** Fallback Recombobulator cost; overridden by the live bazaar ask when available. */
    public double recombCost = 11_000_000d;
    /** Snipes: reference price is this quantile of the non-BIN pool (0.25 = lower quartile). */
    public double auctionReferenceQuantile = 0.25d;
    /** Bazaar: assumed share of market volume we actually capture. */
    public double bazaarCapture = 0.10d;
    /** Bazaar tax: 1.25% base, 1.125% with the free upgrade, 1% with max Bazaar Flipper. */
    public double bazaarTax = 0.0125d;
    /** Price step used to take the top of the order book. */
    public double tick = 0.1d;
    public boolean bazaar = true;
    public boolean auctions = true;
    /** NPC-floor and forge conversions: income independent of market timing. */
    public boolean deterministic = true;
    /** Real NPC gaps are small: the biggest seen in a live dump was 19k on an 800k item. */
    public double minNpcProfit = 5_000d;
    public double minNpcMarginPct = 1d;
    public double minForgeProfit = 200_000d;
    /** Forge: reject margins this large; they usually mean an empty side of the book. */
    public double maxForgeMarginPct = 150d;
    /** Kat pet-rarity upgrades: known coins, materials and duration. Level 1 only. */
    public boolean kat = true;
    public double minKatProfit = 250_000d;
    public double minKatMarginPct = 10d;
    public int minKatListings = 2;
    /** Non-BIN auctions ending within this window, with a bid far under the BIN reference. */
    public boolean snipes = true;
    public int snipeWindowMinutes = 30;
    public int snipeMinLeadSeconds = 45;
    public double minSnipeProfit = 100_000d;
    public double minSnipeMarginPct = 10d;
    public double minSnipeConfidence = 0.25d;
    /** Auction books are thin; a few BIN comparables are enough for a reference here. */
    public int minSnipeListings = 3;
    public int limit = 100;
    /** Optional SkyCofl newest-auction lane; the normal scan remains the fallback. */
    public boolean fastAuctions = true;
    /**
     * Superseded: the scan loop now sleeps for {@link MarketTicker#nextDelayMs}, which polls hard
     * only around the predicted 60 s tick. Kept so an older saved settings file still loads.
     */
    public long scanIntervalMs = 2_000L;
    /** Safety net: force a full auction dump this often even if the tick looks unchanged. */
    public long fullRefreshMs = 600_000L;

    /** The purse, and nothing else: affordability is the only ceiling on a good flip. */
    public double maxCost() {
        return budget;
    }

    /** The margin a flip must clear at this buy price; see {@link #marginScaleFrom}. */
    public double marginFor(double price) {
        double hurdle = minMarginPct;
        if (price > marginScaleFrom && marginScaleStep > 0d) {
            hurdle += (price - marginScaleFrom) / marginScaleStep * marginScalePct;
        }
        return Math.max(minMarginPct, Math.min(hurdle, maxScaledMarginPct));
    }

    public FlipConfig copy() {
        FlipConfig c = new FlipConfig();
        c.budget = budget;
        c.minProfit = minProfit;
        c.minMarginPct = minMarginPct;
        c.minBazaarMarginPct = minBazaarMarginPct;
        c.maxBazaarMarginPct = maxBazaarMarginPct;
        c.minBazaarPrice = minBazaarPrice;
        c.maxAuctionMarginPct = maxAuctionMarginPct;
        c.maxRelativeSpread = maxRelativeSpread;
        c.minConfidence = minConfidence;
        c.minBazaarUnitProfit = minBazaarUnitProfit;
        c.minCoinsPerHour = minCoinsPerHour;
        c.minBazaarSalesPerDay = minBazaarSalesPerDay;
        c.minAuctionListings = minAuctionListings;
        c.auctionReferenceQuantile = auctionReferenceQuantile;
        c.bazaarCapture = bazaarCapture;
        c.bazaarTax = bazaarTax;
        c.tick = tick;
        c.bazaar = bazaar;
        c.auctions = auctions;
        c.fastAuctions = fastAuctions;
        c.limit = limit;
        c.minAuctionSalesPerDay = minAuctionSalesPerDay;
        c.minAuctionSalesForFlip = minAuctionSalesForFlip;
        c.fullSalesTrust = fullSalesTrust;
        c.thinSalesMarginPct = thinSalesMarginPct;
        c.maxThinAnchorOverSales = maxThinAnchorOverSales;
        c.maxBuyOverGlobalMedian = maxBuyOverGlobalMedian;
        c.maxBuyOverGlobalMedianWithSales = maxBuyOverGlobalMedianWithSales;
        c.maxTargetOverGlobalMedian = maxTargetOverGlobalMedian;
        c.minGlobalSalesForPriceGuard = minGlobalSalesForPriceGuard;
        c.minGlobalSalesForTrustedExit = minGlobalSalesForTrustedExit;
        c.requireGlobalPriceForPlainFlips = requireGlobalPriceForPlainFlips;
        c.marginScaleFrom = marginScaleFrom;
        c.marginScaleStep = marginScaleStep;
        c.marginScalePct = marginScalePct;
        c.maxScaledMarginPct = maxScaledMarginPct;
        c.bookOnlyFlips = bookOnlyFlips;
        c.bookOnlyMinProfit = bookOnlyMinProfit;
        c.bookOnlyMinMarginPct = bookOnlyMinMarginPct;
        c.bookOnlyMinConfidence = bookOnlyMinConfidence;
        c.bookOnlyMinSupport = bookOnlyMinSupport;
        c.minAuctionSupport = minAuctionSupport;
        c.minVariantListingsWithSales = minVariantListingsWithSales;
        c.conservativeSalesQuantile = conservativeSalesQuantile;
        c.riskBufferPct = riskBufferPct;
        c.maxHoldHours = maxHoldHours;
        c.maxBookOnlyHoldHours = maxBookOnlyHoldHours;
        c.unknownLiquidityMarginPremiumPct = unknownLiquidityMarginPremiumPct;
        c.unsoldMarkdownPct = unsoldMarkdownPct;
        c.capitalChargePerDayPct = capitalChargePerDayPct;
        c.requirePositiveRiskAdjusted = requirePositiveRiskAdjusted;
        c.crafts = crafts;
        c.minCraftProfit = minCraftProfit;
        c.minCraftMarginPct = minCraftMarginPct;
        c.maxCraftMarginPct = maxCraftMarginPct;
        c.maxCraftDepth = maxCraftDepth;
        c.minCraftSalesPerDay = minCraftSalesPerDay;
        c.maxCraftPremiumPct = maxCraftPremiumPct;
        c.recombCost = recombCost;
        c.deterministic = deterministic;
        c.minNpcProfit = minNpcProfit;
        c.minNpcMarginPct = minNpcMarginPct;
        c.minForgeProfit = minForgeProfit;
        c.maxForgeMarginPct = maxForgeMarginPct;
        c.kat = kat;
        c.minKatProfit = minKatProfit;
        c.minKatMarginPct = minKatMarginPct;
        c.minKatListings = minKatListings;
        c.snipes = snipes;
        c.snipeWindowMinutes = snipeWindowMinutes;
        c.snipeMinLeadSeconds = snipeMinLeadSeconds;
        c.minSnipeProfit = minSnipeProfit;
        c.minSnipeMarginPct = minSnipeMarginPct;
        c.minSnipeConfidence = minSnipeConfidence;
        c.minSnipeListings = minSnipeListings;
        c.scanIntervalMs = scanIntervalMs;
        c.fullRefreshMs = fullRefreshMs;
        return c;
    }
}
