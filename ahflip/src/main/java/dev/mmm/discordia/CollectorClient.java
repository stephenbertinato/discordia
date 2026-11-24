package dev.mmm.discordia;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mmm.discordia.core.Flip;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only WebSocket client for a private Discordia collector. */
final class CollectorClient {
    record Snapshot(List<Flip> bin, List<Flip> nonBin, long lastUpdated, int pending, String status) {
    }

    interface Events {
        void onSnapshot(Snapshot snapshot);

        void onStatus(String status);

        /** A poll-loop heartbeat; lastUpdated is the market tick, not the receipt time. */
        default void onHeartbeat(long generatedAt, long lastUpdated) {
        }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long RECONNECT_SECONDS = 5L;

    private final URI uri;
    private final String token;
    private final Events events;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "discordia-collector");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final StringBuilder partial = new StringBuilder();

    private volatile WebSocket socket;
    private volatile boolean stopped;

    CollectorClient(URI uri, String token, Events events) {
        this.uri = uri;
        this.token = token == null ? "" : token;
        this.events = events;
    }

    void start() {
        stopped = false;
        connect();
    }

    /** Sends the current local filter set to the collector so it can recompute server-side. */
    void sendFilters(JsonObject filters) {
        WebSocket current = socket;
        if (current == null || filters == null) {
            return;
        }
        try {
            current.sendText(filters.toString(), true);
        } catch (Exception ignored) {
            // The reconnect path will resend the latest filters on the next connected event.
        }
    }

    void stop() {
        stopped = true;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "collector disabled");
            } catch (Exception ignored) {
                try {
                    current.abort();
                } catch (Exception ignoredAbort) {
                    // The socket may already be gone.
                }
            }
        }
        scheduler.shutdownNow();
    }

    private void connect() {
        if (stopped) {
            return;
        }
        reconnectScheduled.set(false);
        events.onStatus("connecting");
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.buildAsync(uri, new Listener())
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        scheduleReconnect("reconnecting: " + shortError(error));
                    }
                });
    }

    private void scheduleReconnect(String status) {
        if (stopped) {
            return;
        }
        events.onStatus(status);
        if (reconnectScheduled.compareAndSet(false, true)) {
            scheduler.schedule(() -> {
                reconnectScheduled.set(false);
                connect();
            }, RECONNECT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static String shortError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String text = cause.getMessage();
        if (text == null || text.isBlank()) {
            text = cause.getClass().getSimpleName();
        }
        return text.length() > 120 ? text.substring(0, 120) : text;
    }

    private final class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            partial.setLength(0);
            events.onStatus("connected");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                accept(text);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            if (!stopped) {
                scheduleReconnect("disconnected");
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            if (!stopped) {
                scheduleReconnect("reconnecting: " + shortError(error));
            }
        }
    }

    private void accept(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            String type = root.has("type") ? root.get("type").getAsString() : "";
            if ("status".equals(type)) {
                long lastUpdated = root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : 0L;
                if (lastUpdated > 0L) {
                    long generatedAt = root.has("generatedAt")
                            ? root.get("generatedAt").getAsLong() : System.currentTimeMillis();
                    events.onHeartbeat(generatedAt, lastUpdated);
                } else {
                    events.onStatus(root.has("status") ? root.get("status").getAsString() : "unknown");
                }
                return;
            }
            if (!"snapshot".equals(type)) {
                return;
            }
            List<Flip> bin = flips(root, "bin");
            List<Flip> nonBin = flips(root, "nonBin");
            long lastUpdated = root.has("lastUpdated") ? root.get("lastUpdated").getAsLong() : 0L;
            int pending = root.has("pending") ? root.get("pending").getAsInt() : 0;
            String status = root.has("status") ? root.get("status").getAsString() : "ok";
            events.onSnapshot(new Snapshot(bin, nonBin, lastUpdated, pending, status));
        } catch (Exception error) {
            events.onStatus("bad snapshot: " + shortError(error));
        }
    }

    private static List<Flip> flips(JsonObject root, String name) {
        JsonElement element = root.get(name);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<Flip> result = new ArrayList<>(array.size());
        for (JsonElement value : array) {
            if (!value.isJsonObject()) {
                continue;
            }
            JsonObject item = value.getAsJsonObject();
            try {
                Flip.Kind kind = Flip.Kind.valueOf(string(item, "kind", "AUCTION"));
                result.add(new Flip(
                        kind,
                        string(item, "name", ""),
                        string(item, "id", ""),
                        string(item, "tier", "-"),
                        number(item, "buyPrice"),
                        number(item, "targetPrice"),
                        number(item, "profit"),
                        number(item, "marginPct"),
                        number(item, "volumePerDay"),
                        number(item, "minutesToSell"),
                        number(item, "coinsPerHour"),
                        number(item, "confidence"),
                        string(item, "detail", ""),
                        number(item, "medianPrice")
                ));
            } catch (IllegalArgumentException ignored) {
                // An unknown kind from a newer server is skipped rather than breaking the feed.
            }
        }
        return List.copyOf(result);
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString() : fallback;
    }

    private static double number(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return 0d;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
