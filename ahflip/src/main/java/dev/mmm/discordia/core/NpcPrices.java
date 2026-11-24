package dev.mmm.discordia.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * NPC sell prices straight from {@code /v2/resources/skyblock/items}.
 *
 * <p>Verified: 2,375 of 5,691 items carry a positive {@code npc_sell_price}. Item lore does not
 * contain the NPC line in the auction API, so this endpoint is the only reliable source.
 */
public final class NpcPrices {
    public static final String URL = "https://api.hypixel.net/v2/resources/skyblock/items";

    private final Map<String, Double> byId;

    private NpcPrices(Map<String, Double> byId) {
        this.byId = byId;
    }

    public static NpcPrices fetch() throws IOException, InterruptedException {
        JsonObject root = HypixelApi.getJson(URL);
        Map<String, Double> prices = new HashMap<>();
        JsonElement items = root.get("items");
        if (items != null && items.isJsonArray()) {
            for (JsonElement element : items.getAsJsonArray()) {
                JsonObject item = element.getAsJsonObject();
                if (!item.has("id") || !item.has("npc_sell_price")) {
                    continue;
                }
                double price = item.get("npc_sell_price").getAsDouble();
                if (price > 0) {
                    prices.put(item.get("id").getAsString(), price);
                }
            }
        }
        return new NpcPrices(prices);
    }

    public static NpcPrices empty() {
        return new NpcPrices(Map.of());
    }

    public double price(String id) {
        return id == null ? 0d : byId.getOrDefault(id, 0d);
    }

    public int size() {
        return byId.size();
    }
}
