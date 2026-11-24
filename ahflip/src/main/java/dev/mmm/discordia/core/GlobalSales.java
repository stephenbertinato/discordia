package dev.mmm.discordia.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collection;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Market-wide finished-sale summaries.
 *
 * <p>The Hypixel {@code auctions_ended} feed only contains what this client happened to observe.
 * That is not a market history: a sale that happened while Minecraft was closed is invisible. The
 * public SkyCofl analysis endpoint returns a completed-sales summary over a requested window
 * (7 days here), including sales that happened before this client started.
 *
 * <p>Requests are deliberately serialized at about one per second. SkyCofl's public limits are
 * 30 requests/10 seconds and 100 requests/minute per IP; one per second stays below both. Active
 * auction item ids are queued with priority, then the complete item catalog. Successful summaries,
 * retry deadlines, and unavailable markers are cached to disk so a restart during an outage does
 * not throw away the backoff state or flood the source with the whole catalog.
 *
 * <p>A failed request is never treated as zero sales. Timeouts, rate limits, server errors and
 * malformed responses stay explicitly pending and are retried with exponential backoff. A
 * permanent item-level 4xx is marked unavailable instead of zero. Coflnet is the data source for
 * these global numbers; exact variant sale rows still come from the locally observed Hypixel feed.
 */
public final class GlobalSales {
    private static final String ANALYSIS_URL = System.getProperty(
            "discordia.cofl.analysisUrl",
            "https://sky.coflnet.com/api/item/price/%s/analysis?days=7");
    private static final long CACHE_TTL_MS = 6L * 60L * 60L * 1000L;
    private static final long CACHE_TTL_JITTER_MS = 60L * 60L * 1000L;
    private static final long BASE_RETRY_MS = Math.max(50L, Long.getLong(
            "discordia.cofl.baseRetryMs", 30_000L));
    private static final long MAX_RETRY_MS = Math.max(BASE_RETRY_MS, Long.getLong(
            "discordia.cofl.maxRetryMs", 30L * 60L * 1000L));
    private static final long REQUEST_SPACING_MS = Math.max(50L, Long.getLong(
            "discordia.cofl.requestSpacingMs", 1_000L));
    private static final long REQUEST_TIMEOUT_MS = Math.max(100L, Long.getLong(
            "discordia.cofl.requestTimeoutMs", 30_000L));
    private static final long UNAVAILABLE_TTL_MS = Math.max(1_000L, Long.getLong(
            "discordia.cofl.unavailableTtlMs", 24L * 60L * 60L * 1000L));
    private static final long FAULT_RETRY_MS = 2_000L;
    private static final int MAX_ERROR_LENGTH = 180;
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** In-game-only switch for exercising the failure UI without touching Cofl. */
    public enum FaultMode {
        NONE,
        TIMEOUT,
        RATE_LIMIT,
        SERVER_ERROR,
        MALFORMED,
        UNAVAILABLE
    }

    private record Request(String id, String tag) {
    }

    /** A failed request that did not produce a usable global summary. */
    private static final class FetchFailure extends IOException {
        private final boolean permanent;
        private final long retryAfterMs;

        private FetchFailure(String message, boolean permanent, long retryAfterMs) {
            super(message);
            this.permanent = permanent;
            this.retryAfterMs = Math.max(0L, retryAfterMs);
        }
    }

    private final SoldStats sold;
    private final Path cachePath;
    private final Deque<Request> queue = new ConcurrentLinkedDeque<>();
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> fetchedAt = new ConcurrentHashMap<>();
    /** Maps an active listing id to its known Cofl tag, notably PET_<type> for pets. */
    private final Map<String, String> marketTags = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private final Map<String, String> retryTags = new ConcurrentHashMap<>();
    private final Map<String, Long> retryGeneration = new ConcurrentHashMap<>();
    private final Map<String, Long> unavailableUntil = new ConcurrentHashMap<>();
    private final Set<String> injectedUnavailable = ConcurrentHashMap.newKeySet();
    private final Map<String, SoldStats.Stats> stats = new ConcurrentHashMap<>();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private volatile Thread loopThread;
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "discordia-global-retry");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean started;
    private volatile FaultMode faultMode = FaultMode.NONE;
    private volatile int lastStatus;
    private volatile long lastAttemptAt;
    private volatile long lastSuccessAt;
    private volatile long lastErrorAt;
    private volatile String lastError = "";
    private volatile String lastTag = "";
    private int savedSinceLastWrite;

    public GlobalSales(SoldStats sold, Path cachePath) {
        this.sold = sold;
        this.cachePath = cachePath;
        loadCache();
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        resumeRetries();
        Thread thread = new Thread(this::loop, "discordia-global-sales");
        thread.setDaemon(true);
        loopThread = thread;
        thread.start();
    }

    /** Stops the current request loop. A later start() resumes from the same queue and cache. */
    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        Thread current = loopThread;
        loopThread = null;
        if (current != null) {
            current.interrupt();
        }
    }

    /** Turns deterministic Cofl failure injection on or off for in-game verification. */
    public void setFaultMode(FaultMode mode) {
        if (!injectedUnavailable.isEmpty()) {
            for (String id : injectedUnavailable) {
                unavailableUntil.remove(id);
                sold.clearGlobalUnavailable(id);
            }
            injectedUnavailable.clear();
            scheduleSave();
        }
        faultMode = mode == null ? FaultMode.NONE : mode;
        if (faultMode == FaultMode.NONE) {
            lastError = "";
            lastErrorAt = 0L;
        } else {
            lastError = "fault injection " + faultMode.name().toLowerCase(Locale.ROOT);
            lastErrorAt = System.currentTimeMillis();
        }
    }

    public FaultMode faultMode() {
        return faultMode;
    }

    public static FaultMode parseFaultMode(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off", "none", "clear" -> FaultMode.NONE;
            case "timeout", "time" -> FaultMode.TIMEOUT;
            case "429", "rate", "ratelimit", "rate-limit" -> FaultMode.RATE_LIMIT;
            case "500", "server", "servererror", "server-error" -> FaultMode.SERVER_ERROR;
            case "malformed", "badjson", "bad-json" -> FaultMode.MALFORMED;
            case "404", "unavailable", "missing" -> FaultMode.UNAVAILABLE;
            default -> null;
        };
    }

    /** Queues an item unless it is fresh, waiting for a retry, unavailable, or already queued. */
    public void request(String id, String tag) {
        queueRequest(id, tag, false);
    }

    /** Queues an item at the front; used for ids that are in the current auction book. */
    public void requestPriority(String id, String tag) {
        queueRequest(id, tag, true);
    }

    /** Queues every item id from the public catalog. */
    public void requestCatalog(Collection<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            // Active listings record the authoritative tag first: a pet id such as LION must be
            // requested as PET_LION, not with the raw catalog id.
            request(id, marketTags.getOrDefault(id, id));
        }
    }

    private void queueRequest(String id, String tag, boolean priority) {
        if (id == null || id.isBlank()) {
            return;
        }
        String resolvedTag = tag == null || tag.isBlank() ? id : tag;
        String previousTag = marketTags.put(id, resolvedTag);
        if (previousTag != null && !previousTag.equals(resolvedTag)) {
            // An active listing can correct a raw catalog id to a Cofl alias (LION -> PET_LION).
            // Do not let a previous request for the wrong tag keep the item unavailable.
            unavailableUntil.remove(id);
            retryAt.remove(id);
            retryTags.remove(id);
            failures.remove(id);
            retryGeneration.remove(id);
            stats.remove(id);
            fetchedAt.remove(id);
            sold.clearGlobal(id);
            sold.clearGlobalUnavailable(id);
            queue.removeIf(request -> request.id().equals(id));
            queued.remove(id);
            lastError = "";
            lastErrorAt = 0L;
            lastStatus = 0;
        }
        long now = System.currentTimeMillis();
        if (fresh(id, now)) {
            return;
        }
        if (unavailableActive(id, now)) {
            return;
        }
        // Mark the unresolved state before the retryAt check. A persisted retry deadline must not
        // make an item look like a resolved zero after a restart.
        if (sold.hasLocalSales(id)) {
            sold.clearGlobalPending(id);
        } else {
            sold.setGlobalPending(id);
        }
        Long retry = retryAt.get(id);
        if (retry != null && now < retry) {
            return;
        }
        if (!queued.add(id)) {
            return;
        }
        Request request = new Request(id, resolvedTag);
        if (priority) {
            queue.addFirst(request);
        } else {
            queue.addLast(request);
        }
    }

    /** Synchronous helper for diagnostics and the console. */
    public SoldStats.Stats fetchNow(String id, String tag) throws IOException, InterruptedException {
        if (id == null || id.isBlank()) {
            return SoldStats.EMPTY;
        }
        SoldStats.Stats value = fetch(tag == null || tag.isBlank() ? id : tag);
        if (value != null) {
            merge(id, value);
            save();
        }
        return value == null ? SoldStats.EMPTY : value;
    }

    public int cached() {
        return stats.size();
    }

    public int pending() {
        return queue.size();
    }

    public int unavailable() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Map.Entry<String, Long> entry : unavailableUntil.entrySet()) {
            if (now < entry.getValue()) {
                count++;
            } else {
                unavailableUntil.remove(entry.getKey(), entry.getValue());
                injectedUnavailable.remove(entry.getKey());
                sold.clearGlobalUnavailable(entry.getKey());
            }
        }
        return count;
    }

    public int retrying() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (long at : retryAt.values()) {
            if (at > now) {
                count++;
            }
        }
        return count;
    }

    public int completed() {
        return completed.get();
    }

    /** One-line source health suitable for `/discordia status`. */
    public String health() {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (long at : retryAt.values()) {
            if (at > now && at < next) {
                next = at;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(faultMode == FaultMode.NONE ? "source=SkyCofl" : "source=FAULT:" + faultMode.name());
        if (!lastTag.isBlank()) {
            out.append(", item=").append(lastTag);
        }
        if (lastSuccessAt > 0L) {
            out.append(", success ").append(age(now - lastSuccessAt)).append(" ago");
        }
        if (!lastError.isBlank()) {
            out.append(", last ");
            out.append(lastStatus > 0 ? "HTTP " + lastStatus : "network");
            out.append(": ").append(lastError);
            if (lastErrorAt > 0L) {
                out.append(" (").append(age(now - lastErrorAt)).append(" ago)");
            }
        }
        out.append(", retrying ").append(retrying());
        if (next != Long.MAX_VALUE) {
            out.append(", next retry in ").append(Math.max(0L, (next - now + 999L) / 1_000L)).append("s");
        }
        out.append(", unavailable ").append(unavailable());
        return out.toString();
    }

    private String age(long millis) {
        if (millis < 60_000L) {
            return Math.max(0L, millis / 1_000L) + "s";
        }
        if (millis < 3_600_000L) {
            return (millis / 60_000L) + "m";
        }
        return (millis / 3_600_000L) + "h";
    }

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            Request request = queue.pollFirst();
            if (request == null) {
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            queued.remove(request.id());
            long now = System.currentTimeMillis();
            if (fresh(request.id(), now)) {
                // A cached summary is already installed; clearing a stale pending marker is safe.
                sold.clearGlobalPending(request.id());
                continue;
            }
            Long retry = retryAt.get(request.id());
            if (retry != null && now < retry) {
                // Keep pending; the retry scheduler (or a later request after retryAt) re-enqueues it.
                continue;
            }
            if (unavailableActive(request.id(), now)) {
                continue;
            }

            try {
                SoldStats.Stats value = fetch(request.tag());
                if (value == null) {
                    // Malformed/unexpected response: unresolved, not zero.
                    scheduleRetry(request.id(), request.tag(), 0L);
                } else {
                    merge(request.id(), value);
                }
            } catch (FetchFailure failure) {
                noteFailure(failure.getMessage());
                if (failure.permanent) {
                    markUnavailable(request.id(), request.tag());
                } else {
                    scheduleRetry(request.id(), request.tag(), failure.retryAfterMs);
                    if (failure.retryAfterMs > 0L) {
                        sleepQuietly(Math.min(failure.retryAfterMs, 60_000L));
                    }
                }
            } catch (InterruptedException e) {
                // Shutdown/interruption: leave the pending marker intact.
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Timeout, connection reset, malformed JSON, etc. Never turn this into zero.
                noteFailure(e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage()));
                scheduleRetry(request.id(), request.tag(), 0L);
            }

            try {
                Thread.sleep(REQUEST_SPACING_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private SoldStats.Stats fetch(String tag) throws IOException, InterruptedException {
        lastAttemptAt = System.currentTimeMillis();
        lastTag = tag;
        FaultMode injected = faultMode;
        if (injected != FaultMode.NONE) {
            switch (injected) {
                case TIMEOUT -> {
                    lastStatus = 0;
                    throw new FetchFailure("injected Cofl timeout", false, FAULT_RETRY_MS);
                }
                case RATE_LIMIT -> {
                    lastStatus = 429;
                    throw new FetchFailure("injected Cofl HTTP 429", false, FAULT_RETRY_MS);
                }
                case SERVER_ERROR -> {
                    lastStatus = 500;
                    throw new FetchFailure("injected Cofl HTTP 500", false, FAULT_RETRY_MS);
                }
                case MALFORMED -> {
                    lastStatus = 200;
                    throw new FetchFailure("injected malformed analysis response", false, FAULT_RETRY_MS);
                }
                case UNAVAILABLE -> {
                    lastStatus = 404;
                    throw new FetchFailure("injected Cofl item unavailable", true, 0L);
                }
                case NONE -> {
                    // Continue to the real request.
                }
            }
        }

        String url = String.format(ANALYSIS_URL,
                URLEncoder.encode(tag, StandardCharsets.UTF_8));
        if (CoflRateLimiter.isCoflUrl(url)) {
            CoflRateLimiter.acquireGlobal();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(REQUEST_TIMEOUT_MS))
                .header("User-Agent", "Discordia/1.0 (client-side flip finder; SkyCofl data)")
                .GET()
                .build();
        lastStatus = 0;
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        lastStatus = status;
        if (status == 429) {
            throw new FetchFailure("HTTP 429 from " + url, false, retryAfterMs(response));
        }
        if (status == 408 || status == 425 || status >= 500) {
            throw new FetchFailure("HTTP " + status + " from " + url, false, 0L);
        }
        if (status >= 400) {
            // A real item-level rejection (for example an unknown tag); do not retry forever.
            throw new FetchFailure("HTTP " + status + " from " + url, true, 0L);
        }
        if (status != 200) {
            throw new FetchFailure("HTTP " + status + " from " + url, false, 0L);
        }
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) {
            throw new FetchFailure("non-object response from " + url, false, 0L);
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("totalSales") || root.get("totalSales").isJsonNull()) {
            throw new FetchFailure("missing totalSales from " + url, false, 0L);
        }
        int count = root.get("totalSales").getAsInt();
        if (count < 0) {
            // A negative volume is malformed, not evidence of an empty market.
            throw new FetchFailure("negative totalSales from " + url, false, 0L);
        }
        if (count == 0) {
            return SoldStats.EMPTY;
        }
        // These two fields are required when volume is present. Falling back to zero would turn a
        // schema break into a fake price/rate, which is exactly the failure this layer must avoid.
        if (!root.has("medianPrice") || root.get("medianPrice").isJsonNull()
                || !root.has("salesPerDay") || root.get("salesPerDay").isJsonNull()) {
            throw new FetchFailure("incomplete analysis contract from " + url, false, 0L);
        }
        double median = root.get("medianPrice").getAsDouble();
        double perDay = root.get("salesPerDay").getAsDouble();
        if (!Double.isFinite(median) || median < 0d) {
            throw new FetchFailure("invalid medianPrice from " + url, false, 0L);
        }
        if (!Double.isFinite(perDay) || perDay < 0d) {
            throw new FetchFailure("invalid salesPerDay from " + url, false, 0L);
        }
        // SkyCofl's analysis endpoint does not expose a p25; the median is the safest available
        // lower-percentile value and p25 is not currently used by the flip engine.
        return new SoldStats.Stats(count, median, median, perDay, false, false);
    }

    private long retryAfterMs(HttpResponse<String> response) {
        String value = response.headers().firstValue("Retry-After").orElse("");
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds > 0L) {
                long millis = seconds > MAX_RETRY_MS / 1_000L
                        ? MAX_RETRY_MS : seconds * 1_000L;
                return Math.min(MAX_RETRY_MS, millis);
            }
        } catch (NumberFormatException ignored) {
            // HTTP-date or missing header; use the normal exponential delay.
        }
        return 0L;
    }

    private void scheduleRetry(String id, String tag, long retryAfterMs) {
        long now = System.currentTimeMillis();
        int attempts = failures.merge(id, 1, Integer::sum);
        long shift = Math.min(attempts - 1, 6);
        long exponential = Math.min(MAX_RETRY_MS, BASE_RETRY_MS << shift);
        // Retry-After is an explicit server instruction; without one, use exponential backoff.
        long delay = retryAfterMs > 0L
                ? Math.min(MAX_RETRY_MS, retryAfterMs)
                : exponential;
        if (retryAfterMs <= 0L && delay >= 1_000L) {
            // Spread exponential retries so many items do not wake at the same millisecond.
            delay = Math.min(MAX_RETRY_MS,
                    delay + Math.floorMod((long) id.hashCode(), 1_000L));
        }
        retryAt.put(id, now + delay);
        retryTags.put(id, tag);
        // Keep the explicit unresolved state. A timeout or rate limit is not evidence of zero.
        if (sold.hasLocalSales(id)) {
            sold.clearGlobalPending(id);
        } else {
            sold.setGlobalPending(id);
        }
        scheduleSave();
        long generation = retryGeneration.merge(id, 1L, Long::sum);
        scheduleRetryTask(id, tag, delay, generation);
    }

    private void scheduleRetryTask(String id, String tag, long delay, long generation) {
        retryScheduler.schedule(() -> {
            Long current = retryGeneration.get(id);
            if (current == null || current.longValue() != generation) {
                return;
            }
            long now = System.currentTimeMillis();
            if (unavailableActive(id, now)) {
                return;
            }
            if (queued.add(id)) {
                queue.addLast(new Request(id, tag));
            }
        }, Math.max(0L, delay) + 50L, TimeUnit.MILLISECONDS);
    }

    private void markUnavailable(String id, String tag) {
        String currentTag = marketTags.get(id);
        if (tag != null && currentTag != null && !tag.equals(currentTag)) {
            // A catalog request used the raw id while an active listing discovered an alias.
            // Do not poison the item with that stale request; retry with the current tag.
            if (queued.add(id)) {
                queue.addLast(new Request(id, currentTag));
            }
            return;
        }
        long now = System.currentTimeMillis();
        failures.remove(id);
        retryAt.remove(id);
        retryTags.remove(id);
        retryGeneration.remove(id);
        unavailableUntil.put(id, now + UNAVAILABLE_TTL_MS);
        if (faultMode == FaultMode.UNAVAILABLE) {
            injectedUnavailable.add(id);
        }
        sold.setGlobalUnavailable(id);
        scheduleSave();
    }

    private void merge(String id, SoldStats.Stats value) {
        stats.put(id, value);
        sold.setGlobal(id, value);
        fetchedAt.put(id, System.currentTimeMillis());
        failures.remove(id);
        retryAt.remove(id);
        retryTags.remove(id);
        retryGeneration.remove(id);
        unavailableUntil.remove(id);
        injectedUnavailable.remove(id);
        completed.incrementAndGet();
        lastStatus = 200;
        lastSuccessAt = System.currentTimeMillis();
        lastError = "";
        lastErrorAt = 0L;
        if (++savedSinceLastWrite >= 20) {
            save();
        }
    }

    private boolean unavailableActive(String id, long now) {
        Long until = unavailableUntil.get(id);
        if (until == null) {
            return false;
        }
        if (now < until) {
            return true;
        }
        unavailableUntil.remove(id, until);
        injectedUnavailable.remove(id);
        sold.clearGlobalUnavailable(id);
        return false;
    }

    private boolean fresh(String id, long now) {
        Long at = fetchedAt.get(id);
        if (at == null) {
            return false;
        }
        long ttl = CACHE_TTL_MS
                + Math.floorMod((long) id.hashCode(), CACHE_TTL_JITTER_MS);
        return now - at < ttl;
    }

    private void scheduleSave() {
        if (saveScheduled.compareAndSet(false, true)) {
            retryScheduler.schedule(() -> {
                saveScheduled.set(false);
                save();
            }, 500L, TimeUnit.MILLISECONDS);
        }
    }

    private void noteFailure(String message) {
        lastErrorAt = System.currentTimeMillis();
        lastError = shorten(message);
    }

    private String shorten(String message) {
        String value = message == null ? "" : message.trim();
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH) + "...";
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public synchronized void save() {
        savedSinceLastWrite = 0;
        if (cachePath == null) {
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 2);
        JsonObject items = new JsonObject();
        for (Map.Entry<String, SoldStats.Stats> entry : stats.entrySet()) {
            Long at = fetchedAt.get(entry.getKey());
            if (at == null) {
                continue;
            }
            SoldStats.Stats value = entry.getValue();
            JsonArray row = new JsonArray();
            row.add(value.count());
            row.add(value.median());
            row.add(value.p25());
            row.add(value.perDay());
            row.add(at);
            items.add(entry.getKey(), row);
        }
        root.add("items", items);

        long now = System.currentTimeMillis();
        JsonObject retries = new JsonObject();
        for (Map.Entry<String, Long> entry : retryAt.entrySet()) {
            if (entry.getValue() <= now) {
                continue;
            }
            JsonArray row = new JsonArray();
            row.add(entry.getValue());
            row.add(failures.getOrDefault(entry.getKey(), 1));
            row.add(retryTags.getOrDefault(entry.getKey(), entry.getKey()));
            retries.add(entry.getKey(), row);
        }
        root.add("retry", retries);

        JsonObject unavailable = new JsonObject();
        for (Map.Entry<String, Long> entry : unavailableUntil.entrySet()) {
            if (entry.getValue() > now && !injectedUnavailable.contains(entry.getKey())) {
                // Fault-injected 404s are a UI exercise, not a real 24-hour source verdict.
                unavailable.addProperty(entry.getKey(), entry.getValue());
            }
        }
        root.add("unavailable", unavailable);

        Path temp = null;
        try {
            if (cachePath.getParent() != null) {
                Files.createDirectories(cachePath.getParent());
            }
            String name = cachePath.getFileName() == null
                    ? "discordia-global-sales.cache" : cachePath.getFileName().toString();
            temp = cachePath.resolveSibling(name + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temp, cachePath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, cachePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // The cache is an optimization; live data still works without it.
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }

    private void loadCache() {
        if (cachePath == null || !Files.exists(cachePath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject items = root.getAsJsonObject("items");
            if (items != null) {
                for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
                    JsonArray row = entry.getValue().getAsJsonArray();
                    if (row.size() < 5) {
                        continue;
                    }
                    int count = row.get(0).getAsInt();
                    if (count < 0) {
                        continue;
                    }
                    double median = row.get(1).getAsDouble();
                    double p25 = row.get(2).getAsDouble();
                    double perDay = row.get(3).getAsDouble();
                    long at = row.get(4).getAsLong();
                    if (at <= 0L || !Double.isFinite(median) || median < 0d
                            || !Double.isFinite(p25) || p25 < 0d
                            || !Double.isFinite(perDay) || perDay < 0d) {
                        continue;
                    }
                    SoldStats.Stats value = count == 0
                            ? SoldStats.EMPTY
                            : new SoldStats.Stats(count, median, p25, perDay, false, false);
                    stats.put(entry.getKey(), value);
                    sold.setGlobal(entry.getKey(), value);
                    fetchedAt.put(entry.getKey(), at);
                }
            }

            long now = System.currentTimeMillis();
            JsonObject retries = root.getAsJsonObject("retry");
            if (retries != null) {
                for (Map.Entry<String, JsonElement> entry : retries.entrySet()) {
                    JsonArray row = entry.getValue().getAsJsonArray();
                    if (row.size() < 2) {
                        continue;
                    }
                    long at = row.get(0).getAsLong();
                    if (at <= now) {
                        continue;
                    }
                    int attempts = row.get(1).getAsInt();
                    String tag = row.size() > 2 ? row.get(2).getAsString() : entry.getKey();
                    retryAt.put(entry.getKey(), at);
                    failures.put(entry.getKey(), Math.max(1, attempts));
                    retryTags.put(entry.getKey(), tag);
                    if (!sold.hasLocalSales(entry.getKey())) {
                        sold.setGlobalPending(entry.getKey());
                    }
                }
            }

            JsonObject unavailable = root.getAsJsonObject("unavailable");
            if (unavailable != null) {
                for (Map.Entry<String, JsonElement> entry : unavailable.entrySet()) {
                    long until = entry.getValue().getAsLong();
                    if (until > now) {
                        unavailableUntil.put(entry.getKey(), until);
                        sold.setGlobalUnavailable(entry.getKey());
                    }
                }
            }
        } catch (Exception ignored) {
            // A corrupt cache is discarded; the background queue will refill it.
        }
    }

    private void resumeRetries() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : retryAt.entrySet()) {
            long at = entry.getValue();
            if (at <= now) {
                continue;
            }
            String tag = retryTags.getOrDefault(entry.getKey(), entry.getKey());
            long generation = retryGeneration.merge(entry.getKey(), 1L, Long::sum);
            scheduleRetryTask(entry.getKey(), tag, at - now, generation);
        }
    }
}
