package dev.mmm.discordia.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a market snapshot into a short list of flips.
 *
 * <p>Deliberately conservative:
 * <ul>
 *   <li>Bazaar: only quotes where both the bid and the ask sides have real weekly volume.</li>
 *   <li>Auctions: realized sales price a flip. Recent sales use the exact reforge first, then a
 *       reforge stone in the same price band; the target is capped by the next-cheapest listing.
 *       Pets use their actual `petInfo.tier`. Items with no comparable sale must clear the
 *       higher book-only bar and are labeled as unverified. Plain items must also agree with
 *       Cofl's weekly median; a pumped active ask cannot become the target.</li>
 * </ul>
 */
public final class FlipEngine {

    public record Result(List<Flip> bazaar, List<Flip> auctions, List<Flip> deterministic,
                         List<Flip> sniping) {
    }

    public Result compute(MarketSnapshot snapshot, FlipConfig config) {
        return compute(snapshot, config, null);
    }

    public Result compute(MarketSnapshot snapshot, FlipConfig config, SoldStats sold) {
        return compute(snapshot, snapshot, config, sold);
    }

    public Result compute(MarketSnapshot snapshot, MarketSnapshot reference, FlipConfig config, SoldStats sold) {
        return compute(snapshot, reference, config, sold, null);
    }

    /**
     * @param snapshot  listings to consider; may be only the newest page of the dump
     * @param reference book used for reference prices; usually a full dump
     */
    public Result compute(MarketSnapshot snapshot, MarketSnapshot reference, FlipConfig config, SoldStats sold,
                          AttributePricing pricing) {
        return compute(snapshot, reference, config, sold, pricing, null);
    }

    public Result compute(MarketSnapshot snapshot, MarketSnapshot reference, FlipConfig config, SoldStats sold,
                          AttributePricing pricing, NpcPrices npc) {
        MarketSnapshot book = reference == null ? snapshot : reference;
        if (sold != null) {
            registerItems(sold, snapshot);
            if (book != snapshot) {
                registerItems(sold, book);
            }
        }
        List<Flip> bazaar = config.bazaar ? bazaarFlips(snapshot, config) : List.of();
        List<Flip> auctions = config.auctions ? auctionFlips(snapshot, reference, config, sold, pricing) : List.of();
        List<Flip> deterministic = config.deterministic ? deterministicFlips(book, config, npc) : List.of();
        List<Flip> sniping = config.snipes ? snipeFlips(book, config, sold) : List.of();
        return new Result(bazaar, auctions, deterministic, sniping);
    }

    /**
     * Non-BIN auctions ending soon where the current bid is far below the BIN reference price.
     * Sorted by end time, because this section is only useful if you can still act on it.
     */
    private List<Flip> snipeFlips(MarketSnapshot snapshot, FlipConfig config, SoldStats sold) {
        long now = System.currentTimeMillis();
        long minLead = config.snipeMinLeadSeconds * 1000L;
        long maxLead = config.snipeWindowMinutes * 60_000L;
        double maxCost = config.maxCost();

        record Timed(Flip flip, long end) {
        }
        List<Timed> timed = new ArrayList<>();

        for (MarketSnapshot.Auction auction : snapshot.endingAuctions) {
            long left = auction.end() - now;
            if (left < minLead || left > maxLead || auction.currentBid() > maxCost) {
                continue;
            }

            List<Double> prices = trimOutliers(snapshot.pricesByKey.getOrDefault(auction.variantKey(), List.of()));
            double penalty = 1d;
            if (prices.size() < config.minSnipeListings) {
                prices = trimOutliers(snapshot.groupPrices.getOrDefault(auction.groupKey(), List.of()));
                penalty = 0.92d;
            }
            if (prices.size() < config.minSnipeListings) {
                prices = trimOutliers(snapshot.basePrices.getOrDefault(auction.baseKey(), List.of()));
                penalty = 0.8d;
            }
            if (prices.size() < config.minSnipeListings) {
                continue;
            }
            double median = percentile(prices, 0.5);
            if (median <= 0 || relativeSpread(prices) > config.maxRelativeSpread) {
                continue;
            }
            double target = percentile(prices, config.auctionReferenceQuantile);
            if (target <= 0) {
                continue;
            }
            double confidence = confidence(prices) * penalty;
            if (confidence < config.minSnipeConfidence) {
                continue;
            }

            double bid = auction.currentBid();
            String globalPriceNote = "";
            if (isPlainAuctionVariant(auction.variant())) {
                SoldStats.Stats weekly = sold == null
                        ? SoldStats.EMPTY : sold.globalSummary(auction.skyblockId());
                // Non-BIN items are not always queued for a weekly summary because they are not
                // active BIN listings. Apply the guard when the summary is available, but do not
                // require it here; BIN entries still require it before they can be shown.
                if (weekly.count() >= config.minGlobalSalesForPriceGuard && weekly.median() > 0d) {
                    if (bid > weekly.median() * config.maxBuyOverGlobalMedian) {
                        continue;
                    }
                    double weeklyTargetCap = weekly.median() * config.maxTargetOverGlobalMedian;
                    if (target > weeklyTargetCap) {
                        target = weeklyTargetCap;
                        globalPriceNote = String.format(" (weekly median cap %s)", coins(weekly.median()));
                    }
                }
            }
            double net = Tax.ahNet(target);
            double maxBid = net - config.minSnipeProfit;
            if (maxBid <= bid) {
                continue;
            }
            double profit = net - bid;
            if (profit < config.minSnipeProfit) {
                continue;
            }
            double margin = profit / bid * 100d;
            if (margin < config.minSnipeMarginPct) {
                continue;
            }

            timed.add(new Timed(new Flip(
                    Flip.Kind.SNIPE,
                    auction.name(),
                    auction.uuid(),
                    auction.tier(),
                    bid,
                    target,
                    profit,
                    margin,
                    0d,
                    left / 60_000d,
                    0d,
                    confidence,
                    String.format("ends in %s, bid %s vs %s; max bid %s%s",
                            duration(left / 60_000d), coins(bid), coins(target), coins(maxBid), globalPriceNote)
            ), auction.end()));
        }

        timed.sort(Comparator.comparingLong(Timed::end));
        List<Flip> out = new ArrayList<>();
        for (Timed entry : timed) {
            if (out.size() >= config.limit) {
                break;
            }
            out.add(entry.flip());
        }
        return out;
    }

    /**
     * Income that does not depend on market timing: listings under the NPC price, and forge recipes
     * with known inputs and duration.
     */
    private List<Flip> deterministicFlips(MarketSnapshot snapshot, FlipConfig config, NpcPrices npc) {
        List<Flip> out = new ArrayList<>();
        if (npc != null) {
            out.addAll(npcFlips(snapshot, config, npc));
        }
        out.addAll(forgeFlips(snapshot, config));
        if (config.kat) {
            out.addAll(katFlips(snapshot, config));
        }
        return out;
    }

    private List<Flip> katFlips(MarketSnapshot snapshot, FlipConfig config) {
        List<Flip> out = new ArrayList<>();
        for (KatConversions.Conversion conversion : KatConversions.compute(snapshot, config)) {
            KatConversions.Upgrade upgrade = conversion.upgrade();
            double margin = conversion.profit() / conversion.cost() * 100d;
            out.add(new Flip(
                    Flip.Kind.KAT,
                    upgrade.name() + " -> " + prettify(upgrade.to()),
                    upgrade.pet(),
                    upgrade.to(),
                    conversion.cost(),
                    conversion.targetPrice(),
                    conversion.profit(),
                    margin,
                    0d,
                    0d,
                    conversion.perDay() / 24d,
                    0.75d,
                    String.format("Lv1 %s + %s coins + %s materials; kat takes %s; max %d listings",
                            coins(conversion.petCost()), coins(upgrade.coins()),
                            coins(conversion.materialCost()), duration(upgrade.seconds() / 60d),
                            Math.min(conversion.sourceListings(), conversion.targetListings()))
            ));
        }
        out.sort(Comparator.comparingDouble(Flip::coinsPerHour).reversed());
        return trim(out, config.limit);
    }

    private List<Flip> npcFlips(MarketSnapshot snapshot, FlipConfig config, NpcPrices npc) {
        double maxCost = config.maxCost();
        List<Flip> out = new ArrayList<>();
        for (List<MarketSnapshot.Listing> listings : snapshot.listingsByKey.values()) {
            for (MarketSnapshot.Listing listing : listings) {
                double npcPrice = npc.price(listing.skyblockId());
                if (npcPrice <= 0 || listing.price() >= npcPrice || listing.price() > maxCost) {
                    continue;
                }
                double profit = npcPrice - listing.price();
                if (profit < config.minNpcProfit) {
                    continue;
                }
                double margin = profit / listing.price() * 100d;
                if (margin < config.minNpcMarginPct) {
                    continue;
                }
                out.add(new Flip(
                        Flip.Kind.NPC,
                        listing.name(),
                        listing.uuid(),
                        listing.tier(),
                        listing.price(),
                        npcPrice,
                        profit,
                        margin,
                        0d,
                        0d,
                        0d,
                        1.0d,
                        String.format("NPC pays %s vs %s listed (no fees; daily NPC sell cap applies)",
                                coins(npcPrice), coins(listing.price()))
                ));
            }
        }
        out.sort(Comparator.comparingDouble(Flip::profit).reversed());
        return trim(out, config.limit);
    }

    private List<Flip> forgeFlips(MarketSnapshot snapshot, FlipConfig config) {
        double maxCost = config.maxCost();
        List<Flip> out = new ArrayList<>();
        for (ForgeConversions.Conversion conversion : ForgeConversions.compute(snapshot.bazaar, config)) {
            if (conversion.cost() > maxCost || conversion.profit() < config.minForgeProfit) {
                continue;
            }
            ForgeConversions.Recipe recipe = conversion.recipe();
            double margin = conversion.profit() / conversion.cost() * 100d;
            out.add(new Flip(
                    Flip.Kind.FORGE,
                    prettify(recipe.output()) + " (forge)",
                    recipe.output(),
                    "-",
                    conversion.cost(),
                    conversion.revenue(),
                    conversion.profit(),
                    margin,
                    0d,
                    0d,
                    conversion.perDay() / 24d,
                    0.9d,
                    String.format("%s of inputs, %s; %s/day",
                            coins(conversion.cost()), duration(recipe.durationSeconds() / 60d), coins(conversion.perDay()))
            ));
        }
        out.sort(Comparator.comparingDouble(Flip::coinsPerHour).reversed());
        return trim(out, config.limit);
    }

    private List<Flip> bazaarFlips(MarketSnapshot snapshot, FlipConfig config) {
        double maxCost = config.maxCost();
        List<Flip> out = new ArrayList<>();

        for (MarketSnapshot.BazaarQuote quote : snapshot.bazaar.values()) {
            if (quote.id().toUpperCase(java.util.Locale.ROOT).contains("RUNE")) {
                continue;
            }
            double buy = quote.topBid() + config.tick;
            double sell = quote.topAsk() - config.tick;
            if (sell <= buy || buy < config.minBazaarPrice) {
                continue;
            }
            double profit = Tax.bazaarProfit(buy, sell, config.bazaarTax);
            if (profit < config.minBazaarUnitProfit) {
                continue;
            }
            double margin = profit / buy * 100d;
            if (margin < config.minBazaarMarginPct || margin > config.maxBazaarMarginPct || buy > maxCost) {
                continue;
            }
            double unitsPerDay = Math.min(quote.weekBuy(), quote.weekSell()) / 7d;
            if (unitsPerDay < config.minBazaarSalesPerDay) {
                continue;
            }
            // Two ceilings: how much of the book we realistically capture, and how many units
            // our budget can actually hold at once (assume one turn per hour).
            double volumeCeiling = unitsPerDay / 24d * config.bazaarCapture;
            double capitalCeiling = maxCost / buy;
            double unitsPerHour = Math.min(volumeCeiling, capitalCeiling);
            double coinsPerHour = unitsPerHour * profit;
            if (coinsPerHour < config.minCoinsPerHour) {
                continue;
            }

            out.add(new Flip(
                    Flip.Kind.BAZAAR,
                    prettify(quote.id()),
                    quote.id(),
                    "-",
                    buy,
                    sell,
                    profit,
                    margin,
                    unitsPerDay,
                    0d,
                    coinsPerHour,
                    0.6d,
                    String.format("bid %.1f -> ask %.1f, %.0f units/day", buy, sell, unitsPerDay)
            ));
        }

        out.sort(Comparator.comparingDouble(Flip::coinsPerHour).reversed());
        return trim(out, config.limit);
    }

    private List<Flip> auctionFlips(MarketSnapshot snapshot, MarketSnapshot reference, FlipConfig config,
                                    SoldStats sold, AttributePricing pricing) {
        double maxCost = config.maxCost();
        double recombCost = config.recombCost;
        MarketSnapshot.BazaarQuote recombQuote = snapshot.bazaar.get("RECOMBOBULATOR_3000");
        if (recombQuote != null && recombQuote.topAsk() > 0d) {
            recombCost = recombQuote.topAsk();
        }
        List<Flip> out = new ArrayList<>();
        Map<String, List<Double>> exactBook = reference == null ? snapshot.pricesByKey : reference.pricesByKey;
        Map<String, List<Double>> groupBook = reference == null ? snapshot.groupPrices : reference.groupPrices;
        Map<String, List<Double>> baseBook = reference == null ? snapshot.basePrices : reference.basePrices;
        MarketSnapshot referenceBook = reference == null ? snapshot : reference;
        Map<String, SoldStats.Stats> statsCache = new HashMap<>();
        Map<String, List<SoldStats.Sale>> salesCache = new HashMap<>();
        Map<String, Double> rateCache = new HashMap<>();

        // A non-recombed item must compete with the recombed version minus the Recombobulator
        // cost. This is what stops a 50M non-recomb listing from being priced against a 58M
        // recombed one as if the 11M recomb were free.
        Map<String, List<Double>> recombedPrices = new HashMap<>();
        Map<String, List<Double>> fullBladePrices = new HashMap<>();
        for (List<MarketSnapshot.Listing> listings : referenceBook.listingsByKey.values()) {
            for (MarketSnapshot.Listing listing : listings) {
                if (listing.variant().recombobulated()) {
                    recombedPrices.computeIfAbsent(recombKey(listing), ignored -> new ArrayList<>())
                            .add(listing.price());
                }
                if (isBlade(listing) && listing.variant().hasWitherImpact()) {
                    fullBladePrices.computeIfAbsent(bladeKey(listing), ignored -> new ArrayList<>())
                            .add(listing.price());
                }
            }
        }
        for (List<Double> values : recombedPrices.values()) {
            values.sort(Comparator.naturalOrder());
        }

        for (List<Double> values : fullBladePrices.values()) {
            values.sort(Comparator.naturalOrder());
        }

        for (Map.Entry<String, List<MarketSnapshot.Listing>> entry : snapshot.listingsByKey.entrySet()) {
            List<MarketSnapshot.Listing> listings = entry.getValue();
            if (listings.isEmpty()) {
                continue;
            }
            MarketSnapshot.Listing sample = listings.get(0);

            // Prefer the exact variant pool; widen only as far as the data forces us to.
            List<Double> prices = trimOutliers(exactBook.getOrDefault(entry.getKey(), List.of()));
            double poolPenalty = 1d;
            String poolName = "exact variant";
            if (prices.size() < config.minAuctionListings) {
                prices = trimOutliers(groupBook.getOrDefault(sample.groupKey(), List.of()));
                poolPenalty = 0.92d;
                poolName = "same variant, any enchants";
            }
            if (prices.size() < config.minAuctionListings) {
                prices = trimOutliers(baseBook.getOrDefault(sample.baseKey(), List.of()));
                poolPenalty = 0.8d;
                poolName = "base item pool";
            }
            // Pets are priced at their actual rarity: MarketSnapshot has already mapped a Tier
            // Boost to petInfo.tier (one lower than the displayed auction tier).
            boolean poolReady = prices.size() >= config.minAuctionListings;
            double confidence = 0d;
            if (poolReady) {
                double median = percentile(prices, 0.5);
                if (median <= 0 || relativeSpread(prices) > config.maxRelativeSpread) {
                    poolReady = false;
                } else {
                    confidence = confidence(prices) * poolPenalty;
                }
            }
            if (!poolReady) {
                // The book is too thin to price from; only evidenced attribute comparables can.
                poolName = "attribute comparables";
            }


            for (MarketSnapshot.Listing listing : listings) {
                if (listing.price() > maxCost) {
                    continue;
                }

                // Resale must be an actual listing you can undercut, not a quartile. If anyone is
                // cheaper than this listing, or the next listing is too close, there is no flip.
                double listingAnchor = poolReady ? resaleAnchor(prices, listing.price()) : 0d;
                int anchorSupport = poolReady ? supportCount(prices, listingAnchor) : 0;
                if (poolReady && (listingAnchor <= 0d
                        || anchorSupport < config.minAuctionSupport)) {
                    continue;
                }

                SoldStats.Stats realized = sold == null ? SoldStats.EMPTY
                        : cachedStats(statsCache, sold, listing.skyblockId());
                double perDay = realized.perDay();

                // Recent sales first with the exact reforge. Only when that reforge has no sales
                // do we fall back to reforges whose stone cost is in a similar band. Cheap
                // reforges are not blindly interchangeable with Lustrous/Gilded ones.
                List<Double> exactReforgePrices = new ArrayList<>();
                List<Double> similarReforgePrices = new ArrayList<>();
                if (sold != null) {
                    for (SoldStats.Sale sale : cachedSales(salesCache, sold, listing.skyblockId())) {
                        if (sale.stars() != listing.variant().stars()
                                || sale.petLevel() != listing.variant().petLevel()
                                || !matches(sale.heldItem(), listing.variant().heldItem())
                                || !matches(sale.petTier(), listing.variant().petTier())
                                || !matches(sale.scrolls(), listing.variant().scrollSignature())
                                || sale.recombobulated() != listing.variant().recombobulated()) {
                            continue;
                        }
                        if ((sale.reforge() == null ? "" : sale.reforge()).equals(listing.variant().reforge())) {
                            exactReforgePrices.add(sale.price());
                        } else if (ItemVariant.reforgeMatches(sale.reforge(), listing.variant().reforge())) {
                            similarReforgePrices.add(sale.price());
                        }
                    }
                }
                List<Double> variantPrices = exactReforgePrices.isEmpty()
                        ? similarReforgePrices : exactReforgePrices;
                boolean bookOnly = variantPrices.size() < config.minAuctionSalesForFlip;
                double salesMedian = 0d;
                double target;
                String reforgeEvidence;
                if (bookOnly) {
                    // There is no same-variant sale to verify the target. Keep only exceptional
                    // book gaps, require a deep book, and label them clearly as book-only.
                    double bookProfit = Tax.ahProfit(listing.price(), listingAnchor);
                    double bookMargin = bookProfit / listing.price() * 100d;
                    if (!config.bookOnlyFlips
                            || !poolReady
                            || poolName.equals("base item pool")
                            || (!realized.pending() && !realized.unavailable() && realized.count() == 0)
                            || anchorSupport < config.bookOnlyMinSupport
                            || confidence < config.bookOnlyMinConfidence
                            || bookProfit < config.bookOnlyMinProfit
                            || bookMargin < config.bookOnlyMinMarginPct) {
                        continue;
                    }
                    target = listingAnchor;
                    reforgeEvidence = realized.pending()
                            ? "book only, global volume pending"
                            : realized.unavailable()
                            ? "book only, global volume unavailable"
                            : "book only, no same-variant sale";
                } else {
                    if (realized.count() == 0 || realized.perDay() < config.minAuctionSalesPerDay) {
                        continue;
                    }
                    reforgeEvidence = exactReforgePrices.isEmpty() ? "similar reforge" : "same reforge";
                    variantPrices.sort(Comparator.naturalOrder());
                    salesMedian = percentile(variantPrices, 0.5);
                    boolean thick = variantPrices.size() >= config.fullSalesTrust;
                    // A 1-4 sale median is not a market; use the next real listing as the target and
                    // demand a larger margin instead. A thick sample uses the sales median itself.
                    target = thick ? salesMedian * 0.98d : listingAnchor;
                    if (!thick && salesMedian > 0d
                            && target > salesMedian * config.maxThinAnchorOverSales) {
                        // The next listing is many times the realized price; that is a manipulated or
                        // stale ask, not a resale target.
                        continue;
                    }
                    if (poolReady) {
                        target = Math.min(target, listingAnchor);
                    }
                }

                // A thin or pumped book can make an inflated ask look like a legitimate resale
                // target. For plain items, compare both the entry and target with the market-wide
                // weekly median. The 40M Gyrokinetic Wand that triggered this guard had a ~25M
                // weekly median; buying five listings above that is not a flip, it is the exit
                // liquidity for whoever inflated the book.
                String globalPriceNote = "";
                if (isPlainAuctionVariant(listing)) {
                    SoldStats.Stats weekly = sold == null
                            ? SoldStats.EMPTY : sold.globalSummary(listing.skyblockId());
                    if (weekly.count() < config.minGlobalSalesForPriceGuard || weekly.median() <= 0d) {
                        if (config.requireGlobalPriceForPlainFlips) {
                            // A plain item with only a thin or pending weekly summary is exactly
                            // where a pumped first-page ask can become a false "best flip".
                            continue;
                        }
                    } else {
                        // With several recent exact-variant sales, allow normal price fluctuation.
                        // Without them, the weekly median is the stronger evidence.
                        double buyMultiple = variantPrices.size() >= config.fullSalesTrust
                                ? config.maxBuyOverGlobalMedianWithSales
                                : config.maxBuyOverGlobalMedian;
                        if (listing.price() > weekly.median() * buyMultiple) {
                            continue;
                        }
                        double weeklyTargetCap = weekly.median() * config.maxTargetOverGlobalMedian;
                        if (target > weeklyTargetCap) {
                            target = weeklyTargetCap;
                            globalPriceNote = String.format(" (weekly median cap %s)", coins(weekly.median()));
                        }
                    }
                }

                double recombCeiling = 0d;
                if (!isPet(listing) && !listing.variant().recombobulated()) {
                    List<Double> recombed = recombedPrices.get(recombKey(listing));
                    if (recombed != null && !recombed.isEmpty()) {
                        double recombedTarget = recombed.get(0) * 0.99d;
                        double recombedNet = Tax.ahNet(recombedTarget) - recombCost;
                        if (recombedNet <= 0d) {
                            continue;
                        }
                        recombCeiling = Tax.grossForNet(recombedNet);
                    }
                }
                boolean bladePartial = isBlade(listing) && !listing.variant().hasWitherImpact();
                double scrollCost = 0d;
                double fullBladeAnchor = 0d;
                int missingScrolls = 0;
                if (bladePartial) {
                    List<String> missing = listing.variant().missingWitherScrolls();
                    missingScrolls = missing.size();
                    boolean buyable = !missing.isEmpty();
                    for (String scroll : missing) {
                        MarketSnapshot.BazaarQuote quote = snapshot.bazaar.get(scroll);
                        if (quote == null) {
                            buyable = false;
                            break;
                        }
                        MarketSnapshot.BazaarQuote.Fill fill = quote.buy(1d);
                        if (!fill.complete()) {
                            buyable = false;
                            break;
                        }
                        scrollCost += fill.total();
                    }
                    if (!buyable) {
                        continue;
                    }
                    List<Double> full = fullBladePrices.get(bladeKey(listing));
                    if (full == null || full.isEmpty()) {
                        continue;
                    }
                    fullBladeAnchor = full.get(0) * 0.99d;
                    double fullNet = Tax.ahNet(fullBladeAnchor) - scrollCost;
                    if (fullNet <= 0d) {
                        continue;
                    }
                    target = Math.min(target, Tax.grossForNet(fullNet));
                }
                if (target <= 0d) {
                    continue;
                }

                // A listing priced like a plain item but carrying evidenced attributes.
                AttributePricing.Valuation valuation = pricing == null ? null
                        : pricing.valuation(listing.skyblockId(), listing.variant());
                double effectiveTarget = target;
                boolean attributePlay = false;
                boolean comparableTarget = false;
                boolean recombCapped = false;
                if (valuation != null
                        && listing.price() <= valuation.plain() * 1.15d
                        && valuation.premium() >= Math.max(1_000_000d, listing.price() * 0.15d)) {
                    double attributeTarget = Math.min(valuation.comparable(), valuation.plain() + valuation.premium());
                    if (attributeTarget > effectiveTarget) {
                        effectiveTarget = attributeTarget;
                        comparableTarget = true;
                    }
                    attributePlay = true;
                }
                if (recombCeiling > 0d && effectiveTarget > recombCeiling) {
                    effectiveTarget = recombCeiling;
                    recombCapped = true;
                }
                // A percentage margin is the wrong gate for expensive items. A 380M pet at 2%
                // is a 5.9M flip, which is better than a 600k flip at 14%; let absolute profit
                // carry the trade when the percentage is thin but the sales support it.
                double projectedProfit = Tax.ahProfit(listing.price(), effectiveTarget);
                if (listing.price() > effectiveTarget * (1d - config.minMarginPct / 100d)
                        && projectedProfit < config.largeProfitOverride) {
                    continue;
                }

                double listingConfidence = attributePlay ? Math.max(confidence, 0.55d) : confidence;
                if (listingConfidence < config.minConfidence) {
                    continue;
                }

                double profit = projectedProfit;
                if (profit < config.minProfit) {
                    continue;
                }
                double margin = profit / listing.price() * 100d;
                if (variantPrices.size() < config.fullSalesTrust
                        && margin < config.thinSalesMarginPct
                        && profit < config.largeProfitOverride) {
                    continue;
                }
                // A 300% "profit" on a thin book is a trap, not an edge. The wider cap is only
                // justified when the target itself came from comparable sales; an asking price
                // is not evidence.
                double marginCap = marginCap(comparableTarget, config);
                if (margin > marginCap) {
                    continue;
                }

                String volume = realized.pending()
                        ? "global volume pending (book-only)"
                        : realized.unavailable()
                        ? "global volume unavailable (book-only)"
                        : String.format("%.1f sells/day (%s, n=%d, median %s)",
                                perDay, reforgeEvidence, variantPrices.size(), coins(salesMedian));

                // Time-to-sell from throughput: Hypixel exposes no join key between a sold auction
                // and the listing it came from, so (listings + 1) / sales-per-hour is the estimate.
                double salesPerHour = 0d;
                if (sold != null) {
                    if (poolName.equals("exact variant")) {
                        salesPerHour = sold.salesPerHour(listing.variantSignature());
                    }
                    if (salesPerHour <= 0d) {
                        salesPerHour = cachedRate(rateCache, sold, listing.skyblockId());
                    }
                }
                double minutesToSell = salesPerHour > 0d ? (prices.size() + 1d) / salesPerHour * 60d : 0d;
                String timing = minutesToSell > 0d
                        ? String.format("%d listed, %.1f sales/h -> ~%s", prices.size(), salesPerHour, duration(minutesToSell))
                        : realized.pending() ? "pending"
                        : realized.unavailable() ? "unavailable" : "no recent sales";
                String note = attributePlay && valuation != null ? attributeNote(valuation) + " | " : "";
                String poolText = poolReady
                        ? String.format("next of %d BINs", prices.size())
                        : "no book";
                String reforgeNote = !sample.variant().reforge().isEmpty()
                        && !ItemVariant.expensiveReforge(sample.variant().reforge())
                        ? " (cheap reforge pooled)" : "";
                String recombNote = recombCapped
                        ? String.format(" (recomb ceiling %s after %s recomb)",
                                coins(recombCeiling), coins(recombCost))
                        : "";
                String scrollNote = bladePartial
                        ? String.format(" (adds %d scroll%s for %s; full target %s)",
                                missingScrolls, missingScrolls == 1 ? "" : "s",
                                coins(scrollCost), coins(fullBladeAnchor))
                        : "";

                out.add(new Flip(
                        Flip.Kind.AUCTION,
                        listing.name(),
                        listing.uuid(),
                        listing.tier(),
                        listing.price(),
                        effectiveTarget,
                        profit,
                        margin,
                        perDay,
                        minutesToSell,
                        0d,
                        listingConfidence,
                        String.format("%s%s [%s]%s%s%s%s %s = %s; %s; %s", note,
                                sample.variantLabel(), poolName, reforgeNote, recombNote, scrollNote,
                                globalPriceNote, poolText, coins(effectiveTarget), volume, timing),
                        salesMedian
                ));
            }
        }

        out.sort(Comparator
                .comparingDouble((Flip flip) -> flip.profit() * flip.confidence()).reversed()
                .thenComparing(Comparator.comparingDouble(Flip::marginPct).reversed()));
        return trim(out, config.limit);
    }

    /** Older sale rows predate pet tier/item fields; treat those as wildcards, not mismatches. */
    private static boolean matches(String saleValue, String variantValue) {
        return saleValue == null || saleValue.isEmpty() || saleValue.equals(variantValue);
    }

    /** One synchronized/sorted lookup per item per scan instead of one per listing. */
    private static SoldStats.Stats cachedStats(Map<String, SoldStats.Stats> cache, SoldStats sold, String id) {
        if (cache.containsKey(id)) {
            return cache.get(id);
        }
        SoldStats.Stats stats = sold.stats(id);
        cache.put(id, stats);
        return stats;
    }

    /** Registers every item in a snapshot, including items with no sale rows. */
    private static void registerItems(SoldStats sold, MarketSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        for (List<MarketSnapshot.Listing> listings : snapshot.listingsByKey.values()) {
            for (MarketSnapshot.Listing listing : listings) {
                sold.registerKnown(listing.skyblockId());
            }
        }
        for (String id : snapshot.bazaar.keySet()) {
            sold.registerKnown(id);
        }
    }

    private static List<SoldStats.Sale> cachedSales(Map<String, List<SoldStats.Sale>> cache,
                                                   SoldStats sold, String id) {
        return cache.computeIfAbsent(id, sold::salesFor);
    }

    private static double cachedRate(Map<String, Double> cache, SoldStats sold, String id) {
        Double rate = cache.get(id);
        if (rate != null) {
            return rate;
        }
        double value = sold.salesPerHourById(id);
        cache.put(id, value);
        return value;
    }

    /** Same item/stars/level/scrolls, ignoring reforge and tier, for the recombed ceiling. */
    private static String recombKey(MarketSnapshot.Listing listing) {
        ItemVariant variant = listing.variant();
        return variant.id() + "|" + variant.stars() + "|" + variant.petLevel() + "|"
                + variant.heldItem() + "|" + variant.scrollSignature();
    }

    private static final Set<String> BLADES = Set.of("HYPERION", "ASTRAEA", "SCYLLA", "VALKYRIE");

    private static boolean isBlade(MarketSnapshot.Listing listing) {
        String id = listing.variant().id();
        return id != null && BLADES.contains(id.toUpperCase(Locale.ROOT));
    }

    private static String bladeKey(MarketSnapshot.Listing listing) {
        return listing.variant().id() + "|" + listing.tier() + "|" + listing.variant().stars();
    }

    /** Pets carry a level and often a held item; display tier alone is not a value. */
    private static boolean isPet(MarketSnapshot.Listing listing) {
        return listing.variant().petLevel() > 0 || listing.name().startsWith("[Lvl ");
    }

    /**
     * Weekly base-item medians are directly comparable only for a plain item. Stars, reforges,
     * pet levels, scrolls, recombs and enchant signatures can legitimately change the value, so
     * those variants keep their existing exact-evidence logic.
     */
    private static boolean isPlainAuctionVariant(MarketSnapshot.Listing listing) {
        return isPlainAuctionVariant(listing.variant());
    }

    private static boolean isPlainAuctionVariant(ItemVariant variant) {
        return variant.stars() == 0
                && variant.petLevel() == 0
                && variant.reforge().isEmpty()
                && variant.ultimates().isEmpty()
                && variant.heldItem().isEmpty()
                && variant.petTier().isEmpty()
                && !variant.recombobulated()
                && (variant.abilityScrolls() == null || variant.abilityScrolls().isEmpty());
    }

    /**
     * The realistic immediate resale price: 1% under the next-cheapest identical listing.
     * Returns 0 when this listing is not the cheapest, when another listing has the same price,
     * or when there is no other listing to undercut.
     */
    private static double resaleAnchor(List<Double> sorted, double buyPrice) {
        int index = 0;
        if (!sorted.isEmpty() && sorted.get(0) < buyPrice - 1e-6d) {
            return 0d;
        }
        int samePrice = 0;
        while (index < sorted.size() && Math.abs(sorted.get(index) - buyPrice) <= 1e-6d) {
            samePrice++;
            index++;
        }
        if (samePrice != 1 || index >= sorted.size()) {
            return 0d;
        }
        return sorted.get(index) * 0.99d;
    }

    /** Listings within 10% of the anchor: one lonely next price is not a market. */
    private static int supportCount(List<Double> sorted, double target) {
        int count = 0;
        for (double price : sorted) {
            if (price >= target * 0.9d && price <= target * 1.1d) {
                count++;
            }
        }
        return count;
    }

    /** Only a target taken from attribute comparables earns the wider sanity cap. */
    static double marginCap(boolean comparableTarget, FlipConfig config) {
        return comparableTarget ? Math.max(config.maxAuctionMarginPct, 150d) : config.maxAuctionMarginPct;
    }

    private static List<Flip> trim(List<Flip> flips, int limit) {
        if (flips.size() <= limit) {
            return flips;
        }
        return new ArrayList<>(flips.subList(0, limit));
    }

    /** Drops obvious scam-bait and split-variant extremes before computing statistics. */
    static List<Double> trimOutliers(List<Double> sorted) {
        if (sorted.size() < 4) {
            return sorted;
        }
        double median = percentile(sorted, 0.5);
        List<Double> kept = new ArrayList<>(sorted.size());
        for (double price : sorted) {
            if (price >= median * 0.1d && price <= median * 10d) {
                kept.add(price);
            }
        }
        return kept.size() >= 4 ? kept : sorted;
    }

    /** Mean absolute deviation relative to the median. */
    static double relativeSpread(List<Double> sorted) {
        double median = percentile(sorted, 0.5);
        if (median <= 0) {
            return 1d;
        }
        double mad = 0d;
        for (double price : sorted) {
            mad += Math.abs(price - median);
        }
        return mad / sorted.size() / median;
    }

    /** Nearest-rank percentile over an already sorted list. */
    static double percentile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0d;
        }
        int index = (int) Math.floor(q * (sorted.size() - 1));
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    /** Higher when there are many listings and prices agree. */
    static double confidence(List<Double> sorted) {
        int n = sorted.size();
        double median = percentile(sorted, 0.5);
        if (median <= 0) {
            return 0d;
        }
        double mad = 0d;
        for (double price : sorted) {
            mad += Math.abs(price - median);
        }
        mad /= n;
        double relativeSpread = mad / median;
        // A five-listing book is too small to verify independently; six is the floor used by
        // minAuctionListings. The score still rewards larger, tighter books.
        double sampleScore = clamp01(n / 15d);
        double agreement = clamp01(1d - relativeSpread * 2d);
        return sampleScore * agreement;
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

    /** Reads like: {@code ATTR plain 1.00M, similar 4.20M; ultimate_wise:5 +3.10M (n=4)}. */
    public static String attributeNote(AttributePricing.Valuation valuation) {
        StringBuilder builder = new StringBuilder("ATTR ");
        builder.append("plain ").append(coins(valuation.plain()))
                .append(", similar ").append(coins(valuation.comparable()));
        int shown = 0;
        for (AttributePricing.Worth worth : valuation.worth()) {
            if (shown++ >= 2) {
                break;
            }
            builder.append("; ").append(worth.attribute()).append(" +").append(coins(worth.premium()))
                    .append(" (n=").append(worth.samples()).append(')');
        }
        return builder.toString();
    }

    /** Compact human duration: 8m, 2.4h, 1.7d. */
    public static String duration(double minutes) {
        if (minutes < 90d) {
            return Math.round(minutes) + "m";
        }
        if (minutes < 48d * 60d) {
            return String.format("%.1fh", minutes / 60d);
        }
        return String.format("%.1fd", minutes / 1440d);
    }

    private static double clamp01(double value) {
        return Math.max(0d, Math.min(1d, value));
    }

    private static String prettify(String id) {
        String[] parts = id.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }
}
