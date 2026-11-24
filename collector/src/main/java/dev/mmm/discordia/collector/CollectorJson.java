package dev.mmm.discordia.collector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mmm.discordia.core.Flip;

import java.util.List;

/** The small JSON protocol used by the private collector WebSocket. */
public final class CollectorJson {
    private CollectorJson() {
    }

    public static JsonObject snapshot(List<Flip> bin, List<Flip> nonBin, long lastUpdated,
                                      int pending, double budget) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "snapshot");
        root.addProperty("generatedAt", System.currentTimeMillis());
        root.addProperty("lastUpdated", lastUpdated);
        root.addProperty("status", "ok");
        root.addProperty("pending", pending);
        root.addProperty("budget", budget);
        root.add("bin", array(bin));
        root.add("nonBin", array(nonBin));
        JsonObject counts = new JsonObject();
        counts.addProperty("bin", bin.size());
        counts.addProperty("nonBin", nonBin.size());
        root.add("counts", counts);
        return root;
    }

    public static JsonObject status(String status) {
        return status(status, 0L);
    }

    /** A lightweight heartbeat so the client can distinguish the poll interval from the market tick. */
    public static JsonObject status(String status, long lastUpdated) {
        JsonObject root = new JsonObject();
        root.addProperty("type", "status");
        root.addProperty("generatedAt", System.currentTimeMillis());
        root.addProperty("status", status == null ? "unknown" : status);
        if (lastUpdated > 0L) {
            root.addProperty("lastUpdated", lastUpdated);
        }
        return root;
    }

    private static JsonArray array(List<Flip> flips) {
        JsonArray array = new JsonArray();
        if (flips != null) {
            for (Flip flip : flips) {
                array.add(flip(flip));
            }
        }
        return array;
    }

    private static JsonObject flip(Flip flip) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", flip.kind().name());
        value.addProperty("name", text(flip.name()));
        value.addProperty("id", text(flip.id()));
        value.addProperty("tier", text(flip.tier()));
        value.addProperty("buyPrice", number(flip.buyPrice()));
        value.addProperty("targetPrice", number(flip.targetPrice()));
        value.addProperty("profit", number(flip.profit()));
        value.addProperty("marginPct", number(flip.marginPct()));
        value.addProperty("volumePerDay", number(flip.volumePerDay()));
        value.addProperty("minutesToSell", number(flip.minutesToSell()));
        value.addProperty("coinsPerHour", number(flip.coinsPerHour()));
        value.addProperty("confidence", number(flip.confidence()));
        value.addProperty("detail", text(flip.detail()));
        value.addProperty("medianPrice", number(flip.medianPrice()));
        value.addProperty("command", text(flip.command()));
        return value;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static double number(double value) {
        return Double.isFinite(value) ? value : 0d;
    }
}
