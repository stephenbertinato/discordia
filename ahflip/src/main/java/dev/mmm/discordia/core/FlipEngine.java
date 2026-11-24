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
 *   <li>Profit is booked at a conservative exit (see {@link RiskModel}): the ask after a
 *       drift/relist reserve, and never above the realized p25 once there are enough sales. A
 *       candidate that only profits at the optimistic ask is refused.</li>
 *   <li>A hold time beyond the configured horizon is a capital trap and is refused, however large
 *       the headline profit; an unknown hold time widens the margin requirement instead.</li>
 *   <li>Every refusal increments a {@link Rejections} reason code, so an empty result can be told
 *       apart from a broken one.</li>
 * </ul>
 */
public final class FlipEngine {

    public record Result(List<Flip> bazaar, List<Flip> auctions, List<Flip> deterministic,
                         List<Flip> sniping) {
    }

    private final Rejections rejections = new Rejections();

    /** Why candidates were not shown, accumulated since the last {@link #resetRejections()}. */
    public Rejections rejections() {
        return rejections;
    }

    public void resetRejections() {
        rejections.clear();
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
        CraftCosts.Prices priceIndex = config.crafts ? priceIndex(book, npc) : null;
        CraftCosts craft = priceIndex == null ? null : new CraftCosts(priceIndex, config.maxCraftDepth);
        List<Flip> bazaar = config.bazaar ? bazaarFlips(snapshot, config) : List.of();
        List<Flip> auctions = config.auctions
                ? auctionFlips(snapshot, reference, config, sold, pricing, craft) : List.of();
        List<Flip> deterministic = new ArrayList<>();
        if (config.deterministic) {
            deterministic.addAll(deterministicFlips(book, config, npc));
        }
        if (craft != null) {
            deterministic.addAll(craftFlips(config, sold, craft, priceIndex));
        }
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
                rejections.count(Rejections.Reason.THIN_POOL);
                continue;
            }
            double median = percentile(prices, 0.5);
            if (median <= 0 || relativeSpread(prices) > config.maxRelativeSpread) {
                rejections.count(Rejections.Reason.WIDE_SPREAD);
                continue;
            }
            double target = percentile(prices, config.auctionReferenceQuantile);
            if (target <= 0) {
                rejections.count(Rejections.Reason.NO_TARGET);
                continue;
            }
            double confidence = confidence(prices) * penalty;
            if (confidence < config.minSnipeConfidence) {
                rejections.count(Rejections.Reason.LOW_CONFIDENCE);
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
                        rejections.count(Rejections.Reason.BUY_ABOVE_GLOBAL);
                        continue;
                    }
                    double weeklyTargetCap = weekly.median() * config.maxTargetOverGlobalMedian;
                    if (target > weeklyTargetCap) {
                        target = weeklyTargetCap;
                        globalPriceNote = String.format(" (weekly median cap %s)", coins(weekly.median()));
                    }
                }
            }
            // A bid is not a purchase and a BIN reference is not this auction's resale price:
            // reserve the same drift/relist buffer the BIN path uses.
            target = RiskModel.conservativeGross(target, 0d, config.riskBufferPct);
            if (target <= 0d) {
                rejections.count(Rejections.Reason.NO_TARGET);
                continue;
            }
            double net = Tax.ahNet(target);
            double maxBid = net - config.minSnipeProfit;
            if (maxBid <= bid) {
                rejections.count(Rejections.Reason.MARGIN);
                continue;
            }
            double profit = net - bid;
            if (profit < config.minSnipeProfit) {
                rejections.count(Rejections.Reason.MIN_PROFIT);
                continue;
            }
            double margin = profit / bid * 100d;
            if (margin < Math.max(config.minSnipeMarginPct, config.marginFor(auction.currentBid()))) {
                rejections.count(Rejections.Reason.MARGIN);
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
                                    SoldStats sold, AttributePricing pricing, CraftCosts craft) {
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
            // A full-book scan prices a listing against a pool that contains it; a page-0 or
            // fast-lane scan prices it against a cached book that does not, plus whatever else
            // arrived in the same scan. Both sides are kept so the anchor means the same thing in
            // every lane.
            List<MarketSnapshot.Listing> exactListings = referenceBook.listingsByKey.get(entry.getKey());
            List<Double> snapshotPrices = new ArrayList<>(listings.size());
            for (MarketSnapshot.Listing sibling : listings) {
                snapshotPrices.add(sibling.price());
            }
            snapshotPrices.sort(Comparator.naturalOrder());
            // Pets are priced at their actual rarity: MarketSnapshot has already mapped a Tier
            // Boost to petInfo.tier (one lower than the displayed auction tier).
            // Pool health is measured once per key. Whether a particular listing may be priced
            // from it is decided per listing, because realized sales can lower the listing floor.
            double poolMedian = prices.isEmpty() ? 0d : percentile(prices, 0.5);
            boolean poolSpreadOk = poolMedian > 0d && relativeSpread(prices) <= config.maxRelativeSpread;
            // The listing being scored belongs to the pool even when the cached book predates it.
            // Counting only the cached prices would make a 6-listing book look like 5 listings to
            // the live lanes alone, and a flip would be priced by the full scan and refused by the
            // fast lane for no reason the market can explain.
            int newcomers = 0;
            for (MarketSnapshot.Listing listing : listings) {
                if (!containsListing(exactListings, listing.uuid())) {
                    newcomers++;
                }
            }
            int poolSize = prices.size() + newcomers;
            boolean poolReady = poolSize >= config.minAuctionListings && poolSpreadOk;
            double poolConfidence = poolReady ? confidence(prices, poolSize) * poolPenalty : 0d;
            // A rescued thin pool keeps the same spread and support bars as a wide one; only the
            // number of listings needed to price from it moves.
            double thinConfidence = !poolReady && poolSpreadOk
                    && poolSize >= Math.max(2, config.minVariantListingsWithSales)
                    ? confidence(prices, poolSize) * poolPenalty : 0d;


            for (MarketSnapshot.Listing listing : listings) {
                if (listing.price() > maxCost) {
                    rejections.count(Rejections.Reason.OVER_BUDGET);
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
                variantPrices.sort(Comparator.naturalOrder());
                boolean bookOnly = variantPrices.size() < config.minAuctionSalesForFlip;
                boolean thickSales = variantPrices.size() >= config.fullSalesTrust;

                // A rare item can trade more often than it is listed, which is the one case where a
                // thin book is still a usable reference. Realized sales lower the listing floor;
                // they never lower the spread, support or anchor bars below it.
                boolean priced = poolReady
                        || (thickSales && poolSpreadOk
                        && prices.size() >= Math.max(2, config.minVariantListingsWithSales));

                // Resale must be an actual listing you can undercut, not a quartile. If anyone is
                // cheaper than this listing, or another copy already sits at this price, there is
                // no flip. Both the cached pool and the rest of this scan are checked, so a live
                // scan and a cached book cannot disagree about who is cheapest.
                double listingAnchor = priced
                        ? resaleAnchor(prices, snapshotPrices, listing.price(),
                        containsListing(exactListings, listing.uuid()))
                        : 0d;
                int anchorSupport = priced ? supportCount(prices, listingAnchor) : 0;
                if (priced && listingAnchor <= 0d) {
                    // "Somebody already beats me" and "I am the only copy, with nothing above me
                    // to undercut" are different problems with different fixes, so they carry
                    // different codes.
                    rejections.count(stuckAtTop(prices, snapshotPrices, listing.price(),
                            containsListing(exactListings, listing.uuid()))
                            ? Rejections.Reason.NOT_CHEAPEST : Rejections.Reason.NO_ANCHOR);
                    continue;
                }
                if (priced && anchorSupport < config.minAuctionSupport) {
                    rejections.count(Rejections.Reason.THIN_SUPPORT);
                    continue;
                }

                // Realized sales are the strongest evidence there is, so they carry confidence on
                // their own when the book is too thin to say anything about the target.
                double evidenceConfidence = thickSales ? Math.min(1d, variantPrices.size() / 10d) : 0d;
                double listingConfidence = Math.max(evidenceConfidence,
                        priced ? (poolReady ? poolConfidence : thinConfidence) : 0d);

                double salesMedian = 0d;
                double target;
                String reforgeEvidence;
                if (bookOnly) {
                    // There is no same-variant sale to verify the target. Keep only exceptional
                    // book gaps, require a deep book, and label them clearly as book-only.
                    double bookProfit = Tax.ahProfit(listing.price(), listingAnchor);
                    double bookMargin = bookProfit / listing.price() * 100d;
                    if (!config.bookOnlyFlips) {
                        rejections.count(Rejections.Reason.BOOK_ONLY_DISABLED);
                        continue;
                    }
                    if (!priced) {
                        rejections.count(poolSpreadOk
                                ? Rejections.Reason.THIN_POOL : Rejections.Reason.WIDE_SPREAD);
                        continue;
                    }
                    if (poolName.equals("base item pool")) {
                        rejections.count(Rejections.Reason.MIXED_POOL);
                        continue;
                    }
                    if (!realized.pending() && !realized.unavailable() && realized.count() == 0) {
                        rejections.count(Rejections.Reason.NO_SALES);
                        continue;
                    }
                    if (anchorSupport < config.bookOnlyMinSupport) {
                        rejections.count(Rejections.Reason.THIN_SUPPORT);
                        continue;
                    }
                    if (listingConfidence < config.bookOnlyMinConfidence) {
                        rejections.count(Rejections.Reason.LOW_CONFIDENCE);
                        continue;
                    }
                    if (bookProfit < config.bookOnlyMinProfit
                            || bookMargin < Math.max(config.bookOnlyMinMarginPct,
                            config.marginFor(listing.price()))) {
                        rejections.count(Rejections.Reason.MIN_PROFIT);
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
                        rejections.count(realized.count() == 0
                                ? Rejections.Reason.NO_SALES : Rejections.Reason.LOW_VOLUME);
                        continue;
                    }
                    reforgeEvidence = exactReforgePrices.isEmpty() ? "similar reforge" : "same reforge";
                    salesMedian = percentile(variantPrices, 0.5);
                    // A 1-4 sale median is not a market; use the next real listing as the target and
                    // demand a larger margin instead. A thick sample uses the sales median itself.
                    target = thickSales ? salesMedian * 0.98d : listingAnchor;
                    if (!thickSales && salesMedian > 0d
                            && target > salesMedian * config.maxThinAnchorOverSales) {
                        // The next listing is many times the realized price; that is a manipulated or
                        // stale ask, not a resale target.
                        rejections.count(Rejections.Reason.MANIPULATED_ANCHOR);
                        continue;
                    }
                    if (priced) {
                        target = Math.min(target, listingAnchor);
                    }
                }

                // A thin or pumped book can make an inflated ask look like a legitimate resale
                // target. For plain items, compare both the entry and target with the market-wide
                // weekly median. The 40M Gyrokinetic Wand that triggered this guard had a ~25M
                // weekly median; buying five listings above that is not a flip, it is the exit
                // liquidity for whoever inflated the book.
                String globalPriceNote = "";
                boolean trustedExit = false;
                if (isPlainAuctionVariant(listing)) {
                    SoldStats.Stats weekly = sold == null
                            ? SoldStats.EMPTY : sold.globalSummary(listing.skyblockId());
                    if (weekly.count() < config.minGlobalSalesForPriceGuard || weekly.median() <= 0d) {
                        if (config.requireGlobalPriceForPlainFlips) {
                            // A plain item with only a thin or pending weekly summary is exactly
                            // where a pumped first-page ask can become a false "best flip".
                            rejections.count(realized.pending() || realized.unavailable()
                                    ? Rejections.Reason.GLOBAL_PENDING : Rejections.Reason.GLOBAL_ZERO);
                            continue;
                        }
                    } else {
                        // With several recent exact-variant sales, allow normal price fluctuation.
                        // Without them, the weekly median is the stronger evidence.
                        double buyMultiple = variantPrices.size() >= config.fullSalesTrust
                                ? config.maxBuyOverGlobalMedianWithSales
                                : config.maxBuyOverGlobalMedian;
                        if (listing.price() > weekly.median() * buyMultiple) {
                            rejections.count(Rejections.Reason.BUY_ABOVE_GLOBAL);
                            continue;
                        }
                        double weeklyTargetCap = weekly.median() * config.maxTargetOverGlobalMedian;
                        if (target > weeklyTargetCap) {
                            target = weeklyTargetCap;
                            globalPriceNote = String.format(" (weekly median cap %s)", coins(weekly.median()));
                        }
                        // A thick market-wide median is an exit nobody in this book controls. When
                        // hundreds of sales happen at that price, a listing far below it is cheap in
                        // fact, and the book's own margin can no longer be "implausible". The cap
                        // exists to reject pumped fiction, not a 17-coin accessory the whole market
                        // resells for millions.
                        if (weekly.count() >= config.minGlobalSalesForTrustedExit) {
                            trustedExit = true;
                            globalPriceNote += String.format(" (verified exit, %d weekly sales)",
                                    weekly.count());
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
                            rejections.count(Rejections.Reason.RECOMB_CEILING);
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
                        rejections.count(Rejections.Reason.SCROLL_UNAVAILABLE);
                        continue;
                    }
                    // The scrolls are capital, not a discount. A cheap partial blade whose missing
                    // scrolls cost more than the whole item cap is not affordable even when the
                    // listing itself is.
                    if (listing.price() + scrollCost > maxCost) {
                        rejections.count(Rejections.Reason.OVER_BUDGET);
                        continue;
                    }
                    List<Double> full = fullBladePrices.get(bladeKey(listing));
                    if (full == null || full.isEmpty()) {
                        rejections.count(Rejections.Reason.NO_FULL_BLADE);
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
                    rejections.count(Rejections.Reason.NO_TARGET);
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

                // --- Replacement-cost ceiling --------------------------------------------------
                // What the item costs to build is the most anyone can rationally pay for it. A book
                // that claims a 900M resale on something craftable for 300M is describing an exit
                // that does not exist. This is the craft-cost finder's valuation, used as a cap.
                String craftCapNote = "";
                if (craft != null) {
                    double floor = craft.variantFloor(listing.variant(), listing.tier());
                    if (floor != CraftCosts.UNKNOWN && floor > 0d) {
                        double cap = floor * (1d + config.maxCraftPremiumPct / 100d);
                        if (effectiveTarget > cap) {
                            effectiveTarget = cap;
                            craftCapNote = String.format(" (craft floor cap %s)", coins(floor));
                        }
                    }
                }

                // --- Conservative exit --------------------------------------------------------
                // The ask is not the price you get. Every accepted flip must still be profitable
                // at a lower bound: the ask after a drift/relist/slippage reserve, and never above
                // the realized p25 once there are enough sales to measure one. Profit is booked at
                // that floor, not at the optimistic ask, so an alert cannot depend on getting the
                // best possible exit.
                double lowerBound = thickSales
                        ? RiskModel.lowerQuantile(variantPrices, config.conservativeSalesQuantile) : 0d;
                boolean lowerBoundUsed = lowerBound > 0d && lowerBound < effectiveTarget;
                double conservativeExit = RiskModel.conservativeGross(effectiveTarget, lowerBound,
                        config.riskBufferPct);
                if (conservativeExit <= 0d) {
                    rejections.count(Rejections.Reason.NO_TARGET);
                    continue;
                }

                // --- Liquidity and hold horizon -----------------------------------------------
                // A 4M profit on something that sells once a month is a capital trap, not a flip.
                // Hypixel exposes no join key between a sold auction and the listing it came from,
                // so (listings + 1) / sales per hour is the Little's-law estimate.
                double salesPerHour = 0d;
                if (sold != null) {
                    if (poolName.equals("exact variant")) {
                        salesPerHour = sold.salesPerHour(listing.variantSignature());
                    }
                    if (salesPerHour <= 0d) {
                        salesPerHour = cachedRate(rateCache, sold, listing.skyblockId());
                    }
                }
                RiskModel.Liquidity liquidity = RiskModel.liquidity(salesPerHour, prices.size());
                double holdHours = liquidity.expectedHours();
                double minutesToSell = holdHours * 60d;
                double maxHold = bookOnly ? config.maxBookOnlyHoldHours : config.maxHoldHours;
                if (liquidity.known() && maxHold > 0d && holdHours > maxHold) {
                    rejections.count(Rejections.Reason.CAPITAL_TRAP);
                    continue;
                }

                // --- Fee-inclusive profit at the conservative exit -----------------------------
                // The hurdle rises with the price: a 500M flip locks up 500M and must clear more
                // than the 8% that is enough for a 5M one.
                double requiredMargin = Math.max(config.marginFor(listing.price()),
                        thickSales ? 0d : config.thinSalesMarginPct);
                if (!liquidity.known()) {
                    // Unknown liquidity is not safe liquidity: widen the return requirement
                    // instead of pretending the item sells instantly.
                    requiredMargin += config.unknownLiquidityMarginPremiumPct;
                }
                double marginCap = marginCap(comparableTarget, config);
                requiredMargin = Math.min(requiredMargin, marginCap);

                double profit = Tax.ahNet(conservativeExit) - listing.price();
                if (profit <= 0d || profit < config.minProfit) {
                    rejections.count(Rejections.Reason.MIN_PROFIT);
                    continue;
                }
                double margin = profit / listing.price() * 100d;
                // A percentage margin is the wrong gate for expensive items. A 380M pet at 2%
                // is a 5.9M flip, which is better than a 600k flip at 14%; let absolute profit
                // carry the trade when the percentage is thin but the sales support it.
                if (margin < requiredMargin) {
                    rejections.count(Rejections.Reason.MARGIN);
                    continue;
                }
                // A 300% "profit" on a thin book is a trap, not an edge. The wider cap is only
                // justified when the target itself came from comparable sales; an asking price
                // is not evidence.
                if (!trustedExit && margin > marginCap) {
                    rejections.count(Rejections.Reason.IMPLAUSIBLE_MARGIN);
                    continue;
                }
                if (attributePlay) {
                    listingConfidence = Math.max(listingConfidence, 0.55d);
                }
                if (listingConfidence < config.minConfidence) {
                    rejections.count(Rejections.Reason.LOW_CONFIDENCE);
                    continue;
                }

                // --- Risk-adjusted expectation ------------------------------------------------
                // Expected profit after the chance of not selling and the cost of the coins locked
                // up in the meantime. This is what stops "huge headline, impossible hold".
                double probability = 0d;
                double riskAdjusted = profit;
                if (liquidity.known()) {
                    probability = RiskModel.saleProbability(holdHours, maxHold);
                    double unsoldCost = RiskModel.unsoldCost(conservativeExit, config.unsoldMarkdownPct);
                    riskAdjusted = RiskModel.riskAdjustedProfit(profit, probability, unsoldCost,
                            holdHours, listing.price(), config.capitalChargePerDayPct);
                    if (config.requirePositiveRiskAdjusted && riskAdjusted <= 0d) {
                        rejections.count(Rejections.Reason.RISK_ADJUSTED);
                        continue;
                    }
                }

                String volume = realized.pending()
                        ? "global volume pending (book-only)"
                        : realized.unavailable()
                        ? "global volume unavailable (book-only)"
                        : String.format("%.1f sells/day (%s, n=%d, median %s)",
                                perDay, reforgeEvidence, variantPrices.size(), coins(salesMedian));
                String timing = liquidity.known()
                        ? String.format("%d listed, %.1f sales/h -> ~%s%s",
                                prices.size(), salesPerHour, duration(minutesToSell),
                                maxHold > 0d
                                        ? String.format(", %.0f%% inside %s", probability * 100d,
                                        duration(maxHold * 60d))
                                        : "")
                        : realized.pending() ? "pending"
                        : realized.unavailable() ? "unavailable" : "no recent sales";
                String note = attributePlay && valuation != null ? attributeNote(valuation) + " | " : "";
                String poolText = priced
                        ? String.format("next of %d BINs", prices.size())
                        : "no book";
                String poolLabel = priced ? poolName : "attribute comparables";
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
                // State the floor explicitly: the number the alert is prepared to survive.
                String safeNote = String.format(" (floor %s%s)",
                        coins(conservativeExit),
                        lowerBoundUsed
                                ? String.format(", p%.0f of %d sales",
                                config.conservativeSalesQuantile * 100d, variantPrices.size())
                                : String.format(" after %.1f%% reserve", config.riskBufferPct));

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
                                                String.format("%s%s [%s]%s%s%s%s%s %s = %s; %s; %s%s",
                                note, sample.variantLabel(), poolLabel, reforgeNote, recombNote, scrollNote,
                                craftCapNote, globalPriceNote, poolText, coins(effectiveTarget), volume,
                                timing, safeNote),
                        salesMedian
                ));
            }
        }

        out.sort(Comparator
                .comparingDouble((Flip flip) -> flip.profit() * flip.confidence()).reversed()
                .thenComparing(Comparator.comparingDouble(Flip::marginPct).reversed()));
        return trim(out, config.limit);
    }

    /**
     * LBIN per item id, plus how many copies back it, for the craft resolver.
     *
     * <p>Ingredients that only exist on the auction house are priced from the cheapest listing, and
     * the depth is carried alongside so a single stale listing cannot price a whole recipe.
     */
    private static CraftCosts.Prices priceIndex(MarketSnapshot book, NpcPrices npc) {
        Map<String, Double> lbin = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        for (List<MarketSnapshot.Listing> listings : book.listingsByKey.values()) {
            for (MarketSnapshot.Listing listing : listings) {
                if (listing.price() <= 0d) {
                    continue;
                }
                String id = listing.skyblockId();
                Double current = lbin.get(id);
                if (current == null || listing.price() < current) {
                    lbin.put(id, listing.price());
                }
                depth.merge(id, 1, Integer::sum);
            }
        }
        return new CraftCosts.Prices(book.bazaar, npc, lbin, depth);
    }

    /**
     * Craft flips: buy the ingredients at provable prices, craft the item, sell it into evidenced
     * demand.
     *
     * <p>The ingredient bill is a real cost, so it is exact; the exit is not, so it is the same
     * conservative floor the auction lane uses (weekly median, then the realized p25 when there are
     * enough sales, then the drift/relist reserve). A craft is only announced when the finished item
     * is demonstrably traded at that price — otherwise the recipe is just a way to own something
     * nobody wants.
     */
    private List<Flip> craftFlips(FlipConfig config, SoldStats sold, CraftCosts craft,
                                  CraftCosts.Prices prices) {
        List<Flip> out = new ArrayList<>();
        if (sold == null) {
            return out;
        }
        double maxCost = config.maxCost();
        for (String id : CraftRecipes.all().keySet()) {
            SoldStats.Stats weekly = sold.globalSummary(id);
            // Evidence first: it is cheap, it removes the legacy ids, and a craft with no market is
            // not a flip however cheap its ingredients are.
            if (weekly.pending() || weekly.unavailable() || weekly.count() == 0) {
                // Most of the 2,002 recipes have no market at all. That is absence, not a refusal:
                // counting it would bury the auction diagnostics under two thousand entries.
                continue;
            }
            if (weekly.count() < config.minGlobalSalesForPriceGuard || weekly.median() <= 0d
                    || weekly.perDay() < config.minCraftSalesPerDay) {
                // It does trade, just not enough to be a market worth crafting into.
                rejections.count(Rejections.Reason.CRAFT_NO_EXIT);
                continue;
            }
            double cost = craft.craft(id);
            if (cost == CraftCosts.UNKNOWN || cost <= 0d) {
                rejections.count(Rejections.Reason.CRAFT_UNPRICED);
                continue;
            }
            if (cost > maxCost) {
                rejections.count(Rejections.Reason.OVER_BUDGET);
                continue;
            }

            List<Double> salePrices = new ArrayList<>();
            for (SoldStats.Sale sale : sold.salesFor(id)) {
                if (sale.price() > 0d) {
                    salePrices.add(sale.price());
                }
            }
            salePrices.sort(Comparator.naturalOrder());
            double lower = salePrices.size() >= config.fullSalesTrust
                    ? RiskModel.lowerQuantile(salePrices, config.conservativeSalesQuantile) : 0d;
            double exit = RiskModel.conservativeGross(weekly.median(), lower, config.riskBufferPct);
            if (exit <= 0d) {
                rejections.count(Rejections.Reason.NO_TARGET);
                continue;
            }

            double profit = Tax.ahNet(exit) - cost;
            if (profit < config.minCraftProfit) {
                rejections.count(Rejections.Reason.MIN_PROFIT);
                continue;
            }
            double margin = profit / cost * 100d;
            if (margin < Math.max(config.minCraftMarginPct, config.marginFor(cost))) {
                rejections.count(Rejections.Reason.MARGIN);
                continue;
            }
            if (margin > config.maxCraftMarginPct) {
                rejections.count(Rejections.Reason.IMPLAUSIBLE_MARGIN);
                continue;
            }

            double rate = sold.salesPerHourById(id);
            int listings = Math.max(1, prices.lbinDepth().getOrDefault(id, 1));
            RiskModel.Liquidity liquidity = RiskModel.liquidity(rate, listings);
            if (liquidity.known() && config.maxHoldHours > 0d
                    && liquidity.expectedHours() > config.maxHoldHours) {
                rejections.count(Rejections.Reason.CAPITAL_TRAP);
                continue;
            }
            if (liquidity.known() && config.requirePositiveRiskAdjusted) {
                double probability = RiskModel.saleProbability(liquidity.expectedHours(), config.maxHoldHours);
                double unsold = RiskModel.unsoldCost(exit, config.unsoldMarkdownPct);
                double adjusted = RiskModel.riskAdjustedProfit(profit, probability, unsold,
                        liquidity.expectedHours(), cost, config.capitalChargePerDayPct);
                if (adjusted <= 0d) {
                    rejections.count(Rejections.Reason.RISK_ADJUSTED);
                    continue;
                }
            }

            double confidence = Math.min(1d, 0.5d + weekly.count() / 200d);
            String detail = String.format(
                    "craft from %d ingredients; %s sold/day (n=%d, median %s)%s; exit %s after %.1f%% reserve",
                    CraftRecipes.of(id).size(), trimNumber(weekly.perDay()), weekly.count(),
                    coins(weekly.median()),
                    lower > 0d ? String.format(" (p25 %s)", coins(lower)) : "",
                    coins(exit), config.riskBufferPct);
            out.add(new Flip(Flip.Kind.CRAFT, prettify(id), id, "-", cost, exit, profit, margin,
                    weekly.perDay(), liquidity.known() ? liquidity.expectedHours() * 60d : 0d,
                    0d, confidence, detail, weekly.median()));
        }
        out.sort(Comparator
                .comparingDouble((Flip flip) -> flip.profit() * flip.confidence()).reversed());
        return trim(out, config.limit);
    }

    /** One decimal, without a trailing ".0", for the detail line. */
    private static String trimNumber(double value) {
        return value >= 100d ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.1f", value);
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
     *
     * <p>Returns 0 when this listing is not the unique cheapest, when another copy already sits at
     * the same price, or when there is no other listing to undercut.
     *
     * <p>Two price lists are needed because the lanes see different things. {@code pool} is the
     * cached reference pool; {@code snapshotPrices} is every price this scan saw for the same key,
     * which always includes this listing exactly once. A full-book scan has the listing inside
     * {@code pool} too ({@code candidateInPool}); a page-0 or fast-lane scan does not. Comparing
     * only against the cached pool used to make every listing that undercuts the cheapest cached
     * copy look like the only listing at its price, and the anchor came back as "no next listing",
     * so those lanes could never announce anything after the first full scan.
     */
    private static double resaleAnchor(List<Double> pool, List<Double> snapshotPrices,
                                       double buyPrice, boolean candidateInPool) {
        int poolAtPrice = 0;
        for (double price : pool) {
            if (price < buyPrice - 1e-6d) {
                return 0d;
            }
            if (price <= buyPrice + 1e-6d) {
                poolAtPrice++;
            }
        }
        int siblingsAtPrice = 0;
        for (double price : snapshotPrices) {
            if (price < buyPrice - 1e-6d) {
                return 0d;
            }
            if (price <= buyPrice + 1e-6d) {
                siblingsAtPrice++;
            }
        }
        // The candidate is one of snapshotPrices by construction; do not count it as a rival.
        siblingsAtPrice--;
        int poolElsewhere = poolAtPrice - (candidateInPool ? 1 : 0);
        if (poolElsewhere > 0 || siblingsAtPrice > 0) {
            return 0d;
        }
        double fromPool = firstPriceAbove(pool, buyPrice);
        double fromScan = firstPriceAbove(snapshotPrices, buyPrice);
        double next = fromPool <= 0d ? fromScan
                : fromScan <= 0d ? fromPool : Math.min(fromPool, fromScan);
        return next <= 0d ? 0d : next * 0.99d;
    }

    /** Lowest price strictly above {@code buyPrice} in an ascending list; 0 when there is none. */
    private static double firstPriceAbove(List<Double> sorted, double buyPrice) {
        for (double price : sorted) {
            if (price > buyPrice + 1e-6d) {
                return price;
            }
        }
        return 0d;
    }

    /** True when there is anything left to undercut, whichever lane the prices came from. */
    private static double nextPriceAbove(List<Double> pool, List<Double> snapshotPrices, double buyPrice) {
        double fromPool = firstPriceAbove(pool, buyPrice);
        double fromScan = firstPriceAbove(snapshotPrices, buyPrice);
        if (fromPool <= 0d) {
            return fromScan;
        }
        return fromScan <= 0d ? fromPool : Math.min(fromPool, fromScan);
    }

    /**
     * True only when this listing is the unique cheapest copy and there is simply nothing above it
     * to undercut — the one case where "no anchor" is the market's fault rather than a rival's.
     * Diagnostics only; it never changes a decision.
     */
    private static boolean stuckAtTop(List<Double> pool, List<Double> snapshotPrices,
                                      double buyPrice, boolean candidateInPool) {
        int poolAtPrice = 0;
        for (double price : pool) {
            if (price < buyPrice - 1e-6d) {
                return false;
            }
            if (price <= buyPrice + 1e-6d) {
                poolAtPrice++;
            }
        }
        int siblingsAtPrice = 0;
        for (double price : snapshotPrices) {
            if (price < buyPrice - 1e-6d) {
                return false;
            }
            if (price <= buyPrice + 1e-6d) {
                siblingsAtPrice++;
            }
        }
        int elsewhere = poolAtPrice - (candidateInPool ? 1 : 0) + Math.max(0, siblingsAtPrice - 1);
        return elsewhere <= 0 && nextPriceAbove(pool, snapshotPrices, buyPrice) <= 0d;
    }

    /** True when the cached pool already holds this exact auction. */
    private static boolean containsListing(List<MarketSnapshot.Listing> pool, String uuid) {
        if (pool == null || uuid == null || uuid.isEmpty()) {
            return false;
        }
        for (MarketSnapshot.Listing listing : pool) {
            if (uuid.equalsIgnoreCase(listing.uuid())) {
                return true;
            }
        }
        return false;
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
        return confidence(sorted, sorted.size());
    }

    /**
     * Confidence for a pool whose cached prices are incomplete: {@code size} is how many listings
     * the pool really holds, which is what the sample score should reward.
     */
    static double confidence(List<Double> sorted, int size) {
        int n = Math.max(1, size);
        double median = percentile(sorted, 0.5);
        if (median <= 0) {
            return 0d;
        }
        double mad = 0d;
        for (double price : sorted) {
            mad += Math.abs(price - median);
        }
        mad /= Math.max(1, sorted.size());
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
