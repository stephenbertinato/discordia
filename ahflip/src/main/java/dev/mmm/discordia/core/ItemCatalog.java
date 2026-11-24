package dev.mmm.discordia.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/**
 * The public SkyBlock item resource contains every item id, including items that have never
 * appeared in a local sale or auction window. Registering it makes an item's no-sale state
 * explicit instead of leaving it absent from the sale store.
 *
 * <p>The legacy and v2 item routes are compared by id in the live check; both currently return
 * the same 5,691 ids. The legacy route is tried first because it is the one quoted in the current
 * request, with v2 as a fallback.
 */
public final class ItemCatalog {
    private static final long RETRY_MS = 60L * 60L * 1000L;
    private static volatile Set<String> ids = Set.of();
    private static volatile long attemptedAt;
    private static volatile boolean loaded;

    private ItemCatalog() {
    }

    /** Fetches once per hour at most; a failed fetch is retried later, never fatal. */
    public static synchronized int register(SoldStats sold) {
        long now = System.currentTimeMillis();
        if (!loaded && now - attemptedAt >= RETRY_MS) {
            attemptedAt = now;
            for (String url : new String[]{HypixelApi.ITEMS_URL, HypixelApi.ITEMS_V2_URL}) {
                try {
                    JsonObject response = HypixelApi.getJson(url);
                    JsonArray items = response.getAsJsonArray("items");
                    if (items == null) {
                        continue;
                    }
                    Set<String> next = new HashSet<>(items.size());
                    for (JsonElement element : items) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject item = element.getAsJsonObject();
                        if (item.has("id") && !item.get("id").isJsonNull()) {
                            String id = item.get("id").getAsString();
                            if (!id.isBlank()) {
                                next.add(id);
                            }
                        }
                    }
                    if (!next.isEmpty()) {
                        ids = Set.copyOf(next);
                        loaded = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // Try the other spelling; live auction/sale ids are still registered.
                }
            }
        }
        if (!ids.isEmpty() && sold != null) {
            sold.registerKnown(ids);
        }
        return ids.size();
    }

    public static boolean loaded() {
        return loaded;
    }

    /** Every id from the public item resource. Empty until the first successful fetch. */
    public static Set<String> ids() {
        return ids;
    }
}
