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
import java.util.Locale;

/**
 * Kat conversions: a level-1 pet of one rarity, upgraded to the next rarity for a known coin cost,
 * known materials and known duration.
 *
 * <p>The table is bundled in {@code kat.json}, generated from the official Hypixel SkyBlock Wiki
 * {@code Kat/Costs} page on 2026-11-24. Coin costs are base (level 1) prices — Kat subtracts 0.3%
 * per level — so this only evaluates level-1 pets, where the listed price is exact. XP is unchanged
 * by the upgrade, so a level-1 pet stays level 1 and the resale pool is {@code [Lvl 1] Name|RARITY}.
 *
 * <p>Only conversions whose materials are all bazaar-traded are priced; anything else is skipped
 * rather than guessed.
 */
public final class KatConversions {
    private static final Gson GSON = new Gson();
    /** Buy at the median Lv1 listing, sell at the lower quartile of the upgraded pool. */
    private static final double BUY_QUANTILE = 0.5d;
    private static final double SELL_QUANTILE = 0.25d;

    public record Material(String name, String id, int count) {
    }

    public record Upgrade(String pet,
                          String name,
                          String from,
                          String to,
                          double coins,
                          long seconds,
                          List<Material> materials) {
    }

    public record Conversion(Upgrade upgrade,
                             double cost,
                             double petCost,
                             double materialCost,
                             double targetPrice,
                             double revenue,
                             double profit,
                             double perDay,
                             int sourceListings,
                             int targetListings) {
    }

    private static List<Upgrade> upgrades;

    private KatConversions() {
    }

    public static List<Conversion> compute(MarketSnapshot snapshot, FlipConfig config) {
        double maxCost = config.maxCost();
        List<Conversion> out = new ArrayList<>();

        for (Upgrade upgrade : upgrades()) {
            // MarketSnapshot.baseKey lowercases the display name.
            String base = "[lvl 1] " + upgrade.name().toLowerCase(Locale.ROOT);
            List<Double> source = FlipEngine.trimOutliers(
                    snapshot.basePrices.getOrDefault(base + "|" + upgrade.from(), List.of()));
            List<Double> target = FlipEngine.trimOutliers(
                    snapshot.basePrices.getOrDefault(base + "|" + upgrade.to(), List.of()));
            if (source.size() < config.minKatListings || target.size() < config.minKatListings) {
                continue;
            }

            double petCost = FlipEngine.percentile(source, BUY_QUANTILE);
            double targetPrice = FlipEngine.percentile(target, SELL_QUANTILE);
            if (petCost <= 0d || targetPrice <= 0d) {
                continue;
            }

            double materialCost = 0d;
            boolean priced = true;
            for (Material material : upgrade.materials()) {
                if (material.id() == null) {
                    priced = false;
                    break;
                }
                MarketSnapshot.BazaarQuote quote = snapshot.bazaar.get(material.id());
                if (quote == null || quote.topAsk() <= 0d) {
                    priced = false;
                    break;
                }
                materialCost += quote.topAsk() * material.count();
            }
            if (!priced) {
                continue;
            }

            double cost = petCost + upgrade.coins() + materialCost;
            if (cost > maxCost) {
                continue;
            }
            double revenue = Tax.ahNet(targetPrice);
            double profit = revenue - cost;
            if (profit < config.minKatProfit) {
                continue;
            }
            double margin = profit / cost * 100d;
            if (margin < config.minKatMarginPct) {
                continue;
            }
            double perDay = profit * (86_400d / Math.max(1d, upgrade.seconds()));
            out.add(new Conversion(upgrade, cost, petCost, materialCost, targetPrice, revenue, profit, perDay,
                    source.size(), target.size()));
        }

        out.sort(Comparator.comparingDouble(Conversion::perDay).reversed());
        return out;
    }

    public static List<Upgrade> bundledUpgrades() {
        return upgrades();
    }

    private static synchronized List<Upgrade> upgrades() {
        if (upgrades != null) {
            return upgrades;
        }
        List<Upgrade> loaded = new ArrayList<>();
        try (InputStream in = KatConversions.class.getResourceAsStream("/kat.json")) {
            if (in != null) {
                JsonObject root = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                JsonArray array = root.getAsJsonArray("upgrades");
                for (JsonElement element : array) {
                    JsonObject row = element.getAsJsonObject();
                    List<Material> materials = new ArrayList<>();
                    for (JsonElement materialElement : row.getAsJsonArray("materials")) {
                        JsonObject material = materialElement.getAsJsonObject();
                        String id = material.has("id") && !material.get("id").isJsonNull()
                                ? material.get("id").getAsString() : null;
                        materials.add(new Material(
                                material.get("name").getAsString(),
                                id,
                                material.get("count").getAsInt()));
                    }
                    loaded.add(new Upgrade(
                            row.get("pet").getAsString(),
                            row.get("name").getAsString(),
                            row.get("from").getAsString(),
                            row.get("to").getAsString(),
                            row.get("coins").getAsDouble(),
                            row.get("seconds").getAsLong(),
                            List.copyOf(materials)));
                }
            }
        } catch (Exception ignored) {
            // A missing or corrupt table disables the section; it never breaks the scanner.
        }
        upgrades = loaded;
        return upgrades;
    }
}
