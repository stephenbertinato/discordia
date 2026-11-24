package dev.mmm.discordia.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What it would cost to recreate an item from its ingredients, right now.
 *
 * <p>This is a <em>replacement cost floor</em>, not a price prediction. It answers one question:
 * "if I had to build this from scratch this minute, what would the inputs cost?" Buying an auction
 * below that number is buying below replacement cost, which is the whole idea behind a craft-cost
 * finder.
 *
 * <p>Every ingredient is taken at its cheapest <em>provable</em> acquisition route:
 * <ul>
 *   <li>bazaar instant buy, walking the real ask book for the quantity needed;</li>
 *   <li>NPC price, when the item is sold by an NPC;</li>
 *   <li>auction LBIN, only when at least two copies are listed so one stale listing cannot price
 *       a whole recipe;</li>
 *   <li>crafting it in turn, down to {@code maxDepth}.</li>
 * </ul>
 *
 * <p>Anything that cannot be priced makes the whole floor unknown ({@link #UNKNOWN}) rather than
 * optimistic. A cycle in the recipe graph resolves to unknown instead of looping. Results are
 * memoised per instance, and an instance is created per scan because the prices move.
 */
public final class CraftCosts {

    /** Returned whenever a cost cannot be proven from the data at hand. */
    public static final double UNKNOWN = -1d;

    /** Master stars, in the order they are applied to a five-star item. */
    private static final String[] MASTER_STARS = {
            "FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR",
            "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"
    };

    /** The three live price sources, already keyed by item id. */
    public record Prices(Map<String, MarketSnapshot.BazaarQuote> bazaar,
                         NpcPrices npc,
                         Map<String, Double> lbin,
                         Map<String, Integer> lbinDepth) {
    }

    private final Prices prices;
    private final int maxDepth;
    private final Map<String, Double> crafted = new HashMap<>();
    private final Map<String, Double> units = new HashMap<>();
    private final Set<String> busy = new HashSet<>();

    public CraftCosts(Prices prices, int maxDepth) {
        this.prices = prices;
        this.maxDepth = Math.max(1, maxDepth);
    }

    /** Cost to craft one unit of {@code id}, or {@link #UNKNOWN}. */
    public double craft(String id) {
        return craft(id, 0);
    }

    private double craft(String id, int depth) {
        if (depth > maxDepth) {
            return UNKNOWN;
        }
        Double memo = crafted.get(id);
        if (memo != null) {
            return memo;
        }
        List<CraftRecipes.Ingredient> recipe = CraftRecipes.of(id);
        if (recipe == null || recipe.isEmpty()) {
            return UNKNOWN;
        }
        if (!busy.add(id)) {
            // The recipe graph is cyclic here; refusing beats guessing a price.
            return UNKNOWN;
        }
        try {
            double total = 0d;
            for (CraftRecipes.Ingredient ingredient : recipe) {
                double cost = acquire(ingredient.id(), ingredient.count(), depth + 1);
                if (cost == UNKNOWN) {
                    return UNKNOWN;
                }
                total += cost;
            }
            if (total <= 0d) {
                return UNKNOWN;
            }
            crafted.put(id, total);
            return total;
        } finally {
            busy.remove(id);
        }
    }

    /** Cheapest provable way to obtain {@code count} units, including crafting more of them. */
    public double acquire(String id, int count) {
        return acquire(id, count, 0);
    }

    private double acquire(String id, int count, int depth) {
        double best = UNKNOWN;

        MarketSnapshot.BazaarQuote quote = prices.bazaar().get(id);
        if (quote != null) {
            MarketSnapshot.BazaarQuote.Fill fill = quote.buy(count);
            if (fill.complete() && fill.total() > 0d) {
                best = better(best, fill.total());
            }
        }

        if (prices.npc() != null) {
            double npc = prices.npc().price(id);
            if (npc > 0d) {
                best = better(best, npc * count);
            }
        }

        Double lbin = prices.lbin().get(id);
        Integer depthCount = prices.lbinDepth().get(id);
        if (lbin != null && lbin > 0d && depthCount != null && depthCount >= 2) {
            best = better(best, lbin * count);
        }

        if (CraftRecipes.craftable(id)) {
            double craft = craft(id, depth);
            if (craft != UNKNOWN) {
                best = better(best, craft * count);
            }
        }
        return best;
    }

    /**
     * Replacement cost of one concrete listing: the base craft plus the stars, master stars,
     * recombobulator and reforge stone it carries.
     *
     * <p>Returns {@link #UNKNOWN} when any component cannot be priced. That is deliberate: a floor
     * missing its modifier costs would make a heavily upgraded item look cheap to recreate.
     */
    public double variantFloor(ItemVariant variant, String tier) {
        if (variant == null) {
            return UNKNOWN;
        }
        double base = craft(variant.id());
        if (base == UNKNOWN) {
            return UNKNOWN;
        }
        double modifiers = modifierCost(variant, tier);
        if (modifiers == UNKNOWN) {
            return UNKNOWN;
        }
        return base + modifiers;
    }

    /** Stars, master stars, recombobulator and reforge, priced from the bazaar. */
    public double modifierCost(ItemVariant variant, String tier) {
        double total = 0d;
        int stars = Math.max(0, variant.stars());

        if (stars > 0) {
            int normal = Math.min(stars, 5);
            VariantUpgrades.StarLine line = VariantUpgrades.starLine(variant.id());
            if (line == null || line.cost().size() < normal) {
                return UNKNOWN;
            }
            String essence = "ESSENCE_" + line.essence();
            for (int level = 0; level < normal; level++) {
                double unit = unit(essence);
                if (unit == UNKNOWN) {
                    return UNKNOWN;
                }
                total += unit * line.cost().get(level);
                if (level < line.coins().size()) {
                    total += line.coins().get(level);
                }
            }
            for (int level = 6; level <= stars; level++) {
                double unit = unit(MASTER_STARS[level - 6]);
                if (unit == UNKNOWN) {
                    return UNKNOWN;
                }
                total += unit;
            }
        }

        if (variant.recombobulated()) {
            double unit = unit("RECOMBOBULATOR_3000");
            if (unit == UNKNOWN) {
                return UNKNOWN;
            }
            total += unit;
        }

        if (variant.reforge() != null && !variant.reforge().isEmpty()) {
            VariantUpgrades.Reforge reforge = VariantUpgrades.reforge(variant.reforge());
            if (reforge == null) {
                return UNKNOWN;
            }
            double unit = unit(reforge.stone());
            if (unit == UNKNOWN) {
                return UNKNOWN;
            }
            total += unit;
            if (tier != null) {
                total += reforge.coins().getOrDefault(tier.toUpperCase(java.util.Locale.ROOT), 0d);
            }
        }
        return total;
    }

    /** Cheapest single unit from bazaar, NPC or LBIN. Never recurses into crafting. */
    public double unit(String id) {
        Double memo = units.get(id);
        if (memo != null) {
            return memo;
        }
        double best = UNKNOWN;
        MarketSnapshot.BazaarQuote quote = prices.bazaar().get(id);
        if (quote != null) {
            MarketSnapshot.BazaarQuote.Fill fill = quote.buy(1d);
            if (fill.complete() && fill.total() > 0d) {
                best = better(best, fill.total());
            }
        }
        if (prices.npc() != null) {
            double npc = prices.npc().price(id);
            if (npc > 0d) {
                best = better(best, npc);
            }
        }
        Double lbin = prices.lbin().get(id);
        if (lbin != null && lbin > 0d) {
            best = better(best, lbin);
        }
        units.put(id, best);
        return best;
    }

    private static double better(double current, double candidate) {
        if (candidate <= 0d) {
            return current;
        }
        return current == UNKNOWN ? candidate : Math.min(current, candidate);
    }
}
