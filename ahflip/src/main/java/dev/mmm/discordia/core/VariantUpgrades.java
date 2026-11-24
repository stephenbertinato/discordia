package dev.mmm.discordia.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the modifiers on an item cost to recreate: dungeon stars, master stars and the reforge stone.
 *
 * <p>Star essence counts and reforge coin costs come from the NotEnoughUpdates constants; the
 * <em>prices</em> come from the live bazaar, which carries every essence and every master star as a
 * real product. Enchantments are deliberately not modelled: merging enchantment books is a
 * different cost problem, and leaving them out can only understate the floor.
 */
public final class VariantUpgrades {

    /** Essence needed for each of the five normal stars, and the coins the blacksmith charges. */
    public record StarLine(String essence, List<Integer> cost, List<Integer> coins) {
    }

    /** A reforge stone and the coins charged per rarity. */
    public record Reforge(String stone, Map<String, Double> coins) {
    }

    private static volatile Map<String, StarLine> stars;
    private static volatile Map<String, Reforge> reforges;

    private VariantUpgrades() {
    }

    public static StarLine starLine(String id) {
        load();
        return stars.get(id);
    }

    /** Looked up case-insensitively; reforge names arrive from item NBT in mixed case. */
    public static Reforge reforge(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        load();
        return reforges.get(name.toLowerCase(Locale.ROOT));
    }

    public static int starLines() {
        load();
        return stars.size();
    }

    public static int reforgeStones() {
        load();
        return reforges.size();
    }

    private static void load() {
        if (stars != null) {
            return;
        }
        synchronized (VariantUpgrades.class) {
            if (stars != null) {
                return;
            }
            Map<String, StarLine> parsedStars = new HashMap<>();
            Map<String, Reforge> parsedReforges = new HashMap<>();
            try (InputStream in = VariantUpgrades.class.getResourceAsStream("/upgrades.json")) {
                if (in == null) {
                    throw new IllegalStateException("upgrades.json is missing from the jar");
                }
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject starTable = root.getAsJsonObject("stars");
                for (Map.Entry<String, JsonElement> entry : starTable.entrySet()) {
                    JsonObject node = entry.getValue().getAsJsonObject();
                    List<Integer> costs = new ArrayList<>();
                    List<Integer> coins = new ArrayList<>();
                    for (JsonElement value : node.getAsJsonArray("cost")) {
                        costs.add(value.getAsInt());
                    }
                    JsonElement coinNode = node.get("coins");
                    if (coinNode != null && coinNode.isJsonArray()) {
                        for (JsonElement value : coinNode.getAsJsonArray()) {
                            coins.add(value.getAsInt());
                        }
                    }
                    if (costs.size() == 5) {
                        parsedStars.put(entry.getKey(),
                                new StarLine(node.get("essence").getAsString(), List.copyOf(costs),
                                        List.copyOf(coins)));
                    }
                }
                JsonObject reforgeTable = root.getAsJsonObject("reforges");
                for (Map.Entry<String, JsonElement> entry : reforgeTable.entrySet()) {
                    JsonObject node = entry.getValue().getAsJsonObject();
                    Map<String, Double> coins = new LinkedHashMap<>();
                    JsonElement coinNode = node.get("coins");
                    if (coinNode != null && coinNode.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> rarity : coinNode.getAsJsonObject().entrySet()) {
                            coins.put(rarity.getKey(), rarity.getValue().getAsDouble());
                        }
                    }
                    parsedReforges.put(entry.getKey().toLowerCase(Locale.ROOT),
                            new Reforge(node.get("stone").getAsString(), Map.copyOf(coins)));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Could not read upgrades.json", e);
            }
            stars = parsedStars;
            reforges = parsedReforges;
        }
    }
}
