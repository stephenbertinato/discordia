package dev.mmm.discordia.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an attribute is actually worth <em>on a given item</em>.
 *
 * <p>Two rules keep this honest:
 * <ul>
 *   <li>a premium only exists if the same item has realized sales that carry the attribute — a
 *       Soul Eater V book on an Aspect of the Jerry has no comparables, so it is worth nothing
 *       here, which is exactly right;</li>
 *   <li>a few lucky sales are not evidence: an attribute needs more than three sales and at least
 *       5% of everything the item sells before it can create a premium;</li>
 *   <li>every premium is capped by the bazaar cost of the book and scaled down by how often the
 *       base item sells at all.</li>
 * </ul>
 */
public final class AttributePricing {
    private static final int MIN_PLAIN_SALES = 5;
    /** More than three: three sales can be luck, four are a pattern worth checking. */
    private static final int MIN_ATTRIBUTE_SALES = 4;
    /** And the attribute must be a real part of the item's trade, not a rounding error. */
    private static final double MIN_ATTRIBUTE_SHARE = 0.05d;

    public record Worth(String attribute, double premium, int samples) {
    }

    public record Valuation(double plain, double comparable, double premium, double perDay, List<Worth> worth) {
    }

    private final SoldStats sold;
    private final Map<String, Double> bazaarBuy;
    private final Map<String, Optional<Valuation>> cache = new HashMap<>();

    public AttributePricing(SoldStats sold, Map<String, Double> bazaarBuy) {
        this.sold = sold;
        this.bazaarBuy = bazaarBuy == null ? Map.of() : bazaarBuy;
    }

    public Valuation valuation(String id, ItemVariant variant) {
        if (id == null || variant == null) {
            return null;
        }
        String key = id + "|" + variant.signature();
        Optional<Valuation> cached = cache.get(key);
        if (cached != null) {
            return cached.orElse(null);
        }
        Valuation value = compute(id, variant);
        cache.put(key, Optional.ofNullable(value));
        return value;
    }

    private Valuation compute(String id, ItemVariant variant) {
        boolean hasAttribute = variant.stars() > 0 || ItemVariant.expensiveReforge(variant.reforge())
                || variant.petLevel() > 0 || !variant.ultimates().isEmpty();
        if (!hasAttribute) {
            return null;
        }
        List<SoldStats.Sale> sales = sold.salesFor(id);
        if (sales.size() < MIN_PLAIN_SALES) {
            return null;
        }

        List<Double> plainPrices = new ArrayList<>();
        List<Double> comparablePrices = new ArrayList<>();
        for (SoldStats.Sale sale : sales) {
            if (sale.stars() == 0 && sale.reforge().isEmpty() && sale.petLevel() == 0
                    && sale.enchants() <= 2 && !sale.recombobulated()
                    && !carriesUltimates(sale, variant)) {
                plainPrices.add(sale.price());
            }
            if (sameBucket(sale, variant) && carriesAttributes(sale, variant)) {
                comparablePrices.add(sale.price());
            }
        }
        if (plainPrices.size() < MIN_PLAIN_SALES) {
            // Pets never have level 0; use the lowest observed level as the baseline instead.
            int minLevel = sales.stream()
                    .filter(sale -> sale.petLevel() > 0)
                    .mapToInt(SoldStats.Sale::petLevel)
                    .min().orElse(0);
            if (minLevel > 0) {
                for (SoldStats.Sale sale : sales) {
                    if (sale.petLevel() == minLevel && sale.stars() == 0 && sale.reforge().isEmpty()
                            && !sale.recombobulated() && !carriesUltimates(sale, variant)) {
                        plainPrices.add(sale.price());
                    }
                }
            }
        }
        if (plainPrices.size() < MIN_PLAIN_SALES) {
            return null;
        }
        // A handful of lucky sales must not create a premium. Demand more than three
        // attribute-bearing comparables and a real share of everything this item sells.
        if (comparablePrices.size() < MIN_ATTRIBUTE_SALES
                || comparablePrices.size() < sales.size() * MIN_ATTRIBUTE_SHARE) {
            return null;
        }

        double plain = median(plainPrices);
        double comparable = median(comparablePrices);
        double premium = comparable - plain;
        if (premium <= 0) {
            return null;
        }

        double cap = bazaarCap(variant);
        if (cap > 0) {
            premium = Math.min(premium, cap);
        }

        SoldStats.Stats stats = sold.stats(id);
        double perDay = stats.perDay();
        premium *= liquidityDiscount(perDay);

        List<Worth> worth = new ArrayList<>();
        addUltimateWorth(worth, sales, variant);
        addStarWorth(worth, sales, variant);
        addReforgeWorth(worth, sales, variant);
        addPetWorth(worth, sales, variant);

        return new Valuation(plain, comparable, premium, perDay, List.copyOf(worth));
    }

    /** Comparables: same stars, reforge and pet level. Enchants are what we are valuing. */
    private static boolean sameBucket(SoldStats.Sale sale, ItemVariant variant) {
        return sale.stars() == variant.stars()
                && ItemVariant.reforgeMatches(sale.reforge(), variant.reforge())
                && sale.petLevel() == variant.petLevel()
                && sale.recombobulated() == variant.recombobulated()
                && matches(sale.scrolls(), variant.scrollSignature());
    }

    /** Old rows have no scroll field; treat a blank stored value as a wildcard. */
    private static boolean matches(String saleValue, String variantValue) {
        return saleValue == null || saleValue.isEmpty() || saleValue.equals(variantValue);
    }

    /** For a starred/reforged/pet variant the bucket already carries the attribute. */
    private static boolean carriesAttributes(SoldStats.Sale sale, ItemVariant variant) {
        return variant.ultimates().isEmpty() || carriesUltimates(sale, variant);
    }

    /** True only when every ultimate on the variant is present on the sale. */
    private static boolean carriesUltimates(SoldStats.Sale sale, ItemVariant variant) {
        if (variant.ultimates().isEmpty()) {
            return false;
        }
        for (String ultimate : variant.ultimates()) {
            if (!containsUltimate(sale, ultimate)) {
                return false;
            }
        }
        return true;
    }

    private void addUltimateWorth(List<Worth> worth, List<SoldStats.Sale> sales, ItemVariant variant) {
        for (String ultimate : variant.ultimates()) {
            List<Double> with = new ArrayList<>();
            List<Double> without = new ArrayList<>();
            for (SoldStats.Sale sale : sales) {
                if (!sameBucket(sale, variant)) {
                    continue;
                }
                if (containsUltimate(sale, ultimate)) {
                    with.add(sale.price());
                } else {
                    without.add(sale.price());
                }
            }
            if (with.size() < MIN_ATTRIBUTE_SALES
                    || with.size() < sales.size() * MIN_ATTRIBUTE_SHARE
                    || without.size() < MIN_ATTRIBUTE_SALES) {
                continue;
            }
            double premium = median(with) - median(without);
            double book = bookPrice(ultimate);
            if (book > 0) {
                premium = Math.min(premium, book * 0.7d);
            }
            if (premium > 0) {
                worth.add(new Worth(ultimate, premium, with.size()));
            }
        }
    }

    private void addStarWorth(List<Worth> worth, List<SoldStats.Sale> sales, ItemVariant variant) {
        if (variant.stars() <= 0) {
            return;
        }
        List<Double> with = new ArrayList<>();
        List<Double> without = new ArrayList<>();
        for (SoldStats.Sale sale : sales) {
            if (!ItemVariant.reforgeMatches(sale.reforge(), variant.reforge())
                    || sale.petLevel() != variant.petLevel()
                    || sale.recombobulated() != variant.recombobulated()) {
                continue;
            }
            if (sale.stars() >= variant.stars()) {
                with.add(sale.price());
            } else if (sale.stars() == 0) {
                without.add(sale.price());
            }
        }
        add(worth, variant.stars() + "★", with, without, sales.size());
    }

    private void addReforgeWorth(List<Worth> worth, List<SoldStats.Sale> sales, ItemVariant variant) {
        if (!ItemVariant.expensiveReforge(variant.reforge())) {
            return;
        }
        List<Double> with = new ArrayList<>();
        List<Double> without = new ArrayList<>();
        for (SoldStats.Sale sale : sales) {
            if (sale.stars() != variant.stars() || sale.petLevel() != variant.petLevel()
                    || sale.recombobulated() != variant.recombobulated()) {
                continue;
            }
            if (sale.reforge().equals(variant.reforge())) {
                with.add(sale.price());
            } else if (sale.reforge().isEmpty()) {
                without.add(sale.price());
            }
        }
        add(worth, "reforge " + variant.reforge(), with, without, sales.size());
    }

    private void addPetWorth(List<Worth> worth, List<SoldStats.Sale> sales, ItemVariant variant) {
        if (variant.petLevel() <= 0) {
            return;
        }
        List<Double> with = new ArrayList<>();
        List<Double> without = new ArrayList<>();
        for (SoldStats.Sale sale : sales) {
            if (sale.stars() != variant.stars()
                    || !ItemVariant.reforgeMatches(sale.reforge(), variant.reforge())) {
                continue;
            }
            if (Math.abs(sale.petLevel() - variant.petLevel()) <= 15) {
                with.add(sale.price());
            } else if (sale.petLevel() > 0) {
                without.add(sale.price());
            }
        }
        add(worth, "pet Lv" + variant.petLevel(), with, without, sales.size());
    }

    private void add(List<Worth> worth, String label, List<Double> with, List<Double> without, int totalSales) {
        if (with.size() < MIN_ATTRIBUTE_SALES
                || with.size() < totalSales * MIN_ATTRIBUTE_SHARE
                || without.size() < MIN_ATTRIBUTE_SALES) {
            return;
        }
        double premium = median(with) - median(without);
        if (premium > 0) {
            worth.add(new Worth(label, premium, with.size()));
        }
    }

    private static boolean containsUltimate(SoldStats.Sale sale, String ultimate) {
        if (sale.ultimates() == null || sale.ultimates().isEmpty()) {
            return false;
        }
        for (String part : sale.ultimates().split(",")) {
            if (part.equals(ultimate)) {
                return true;
            }
        }
        return false;
    }

    /** Sum of bazaar book cost for the ultimates, as a hard ceiling on what they can add. */
    private double bazaarCap(ItemVariant variant) {
        double total = 0d;
        for (String ultimate : variant.ultimates()) {
            total += bookPrice(ultimate);
        }
        return total;
    }

    /** {@code ultimate_wise:5} -> bazaar product {@code ENCHANTMENT_ULTIMATE_WISE_5}. */
    private double bookPrice(String ultimate) {
        int colon = ultimate.lastIndexOf(':');
        if (colon <= 0) {
            return 0d;
        }
        String name = ultimate.substring(0, colon);
        String level = ultimate.substring(colon + 1);
        String product = "ENCHANTMENT_" + name.toUpperCase(java.util.Locale.ROOT) + "_" + level;
        return bazaarBuy.getOrDefault(product, 0d);
    }

    private static double liquidityDiscount(double perDay) {
        if (perDay >= 20d) {
            return 1d;
        }
        if (perDay >= 5d) {
            return 0.75d;
        }
        if (perDay >= 1d) {
            return 0.5d;
        }
        return 0.25d;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        return FlipEngine.percentile(sorted, 0.5);
    }
}
