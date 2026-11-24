package dev.mmm.discordia.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Forge conversions: known inputs, known duration, live bazaar cost.
 *
 * <p>Recipes are bundled from the NotEnoughUpdates repo ({@code items/*.json}, MIT) in
 * {@code src/main/resources/forge.json} — {@code recipes[type=forge].inputs}, {@code count} and
 * {@code duration} seconds. Only conversions whose inputs and output are all bazaar-traded are
 * evaluated; anything else is skipped rather than guessed.
 */
public final class ForgeConversions {
    private static final Gson GSON = new Gson();
    private static final double BAZAAR_TAX = 0.0125;

    public record Recipe(String output, int count, long durationSeconds, List<String> inputs) {
    }

    public record Conversion(Recipe recipe, double cost, double revenue, double profit, double perDay) {
    }

    private static List<Recipe> recipes;

    private ForgeConversions() {
    }

    public static List<Conversion> compute(Map<String, MarketSnapshot.BazaarQuote> bazaar, FlipConfig config) {
        List<Conversion> out = new ArrayList<>();
        for (Recipe recipe : recipes()) {
            double cost = 0d;
            boolean priced = true;
            for (String input : recipe.inputs()) {
                int colon = input.lastIndexOf(':');
                if (colon <= 0) {
                    priced = false;
                    break;
                }
                String id = input.substring(0, colon);
                int quantity = Integer.parseInt(input.substring(colon + 1));
                MarketSnapshot.BazaarQuote quote = bazaar.get(id);
                if (quote == null) {
                    priced = false;
                    break;
                }
                // Walk the ask book: 160 units are rarely all available at the top price.
                MarketSnapshot.BazaarQuote.Fill fill = quote.buy(quantity);
                if (!fill.complete()) {
                    priced = false;
                    break;
                }
                cost += fill.total();
            }
            if (!priced || cost <= 0d) {
                continue;
            }
            MarketSnapshot.BazaarQuote output = bazaar.get(recipe.output());
            if (output == null) {
                continue;
            }
            // Walk the bid book for the whole output, not just the best unit price.
            MarketSnapshot.BazaarQuote.Fill sale = output.sell(recipe.count());
            if (!sale.complete()) {
                continue;
            }
            double revenue = sale.total() * (1d - BAZAAR_TAX);
            double profit = revenue - cost;
            if (profit <= 0) {
                continue;
            }
            double margin = profit / cost * 100d;
            // A huge forge margin usually means one cheap level or one lonely bid, not an edge.
            if (margin > config.maxForgeMarginPct) {
                continue;
            }
            double perDay = profit * (86_400d / Math.max(1d, recipe.durationSeconds()));
            out.add(new Conversion(recipe, cost, revenue, profit, perDay));
        }
        out.sort(Comparator.comparingDouble(Conversion::perDay).reversed());
        return out;
    }

    public static List<Recipe> bundledRecipes() {
        return recipes();
    }

    private static synchronized List<Recipe> recipes() {
        if (recipes != null) {
            return recipes;
        }
        List<Recipe> loaded = new ArrayList<>();
        try (InputStream in = ForgeConversions.class.getResourceAsStream("/forge.json")) {
            if (in != null) {
                JsonObject root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                JsonArray array = root.getAsJsonArray("recipes");
                for (JsonElement element : array) {
                    JsonObject row = element.getAsJsonObject();
                    List<String> inputs = new ArrayList<>();
                    for (JsonElement input : row.getAsJsonArray("inputs")) {
                        inputs.add(input.getAsString());
                    }
                    loaded.add(new Recipe(
                            row.get("output").getAsString(),
                            row.get("count").getAsInt(),
                            row.get("durationSeconds").getAsLong(),
                            List.copyOf(inputs)));
                }
            }
        } catch (Exception ignored) {
            // A missing or corrupt table disables the section; it never breaks the scanner.
        }
        recipes = loaded;
        return recipes;
    }
}
