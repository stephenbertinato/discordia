package dev.mmm.discordia.tools;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mmm.discordia.core.GlobalSales;
import dev.mmm.discordia.core.SoldStats;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Offline, deterministic failure matrix for global sales.
 *
 * <p>It covers 429 with and without Retry-After, timeouts, 5xx retry, malformed/missing contract
 * fields, explicit zero, permanent 4xx unavailable state, cache reload, and persisted retry state.
 * No request leaves loopback.
 */
public final class GlobalFailureProbe {
    private static final String SUCCESS =
            "{\"totalSales\":5,\"medianPrice\":9000000,\"salesPerDay\":0.714}";

    public static void main(String[] args) throws Exception {
        Path cacheDir = Paths.get("build");
        Files.createDirectories(cacheDir);
        for (String name : new String[]{"rate", "timeout", "five", "noheader", "malformed",
                "missing", "zero", "bad", "persist", "fault", "alias"}) {
            Files.deleteIfExists(cache(name));
        }

        AtomicInteger rateRequests = new AtomicInteger();
        AtomicInteger timeoutRequests = new AtomicInteger();
        AtomicInteger fiveRequests = new AtomicInteger();
        AtomicInteger noHeaderRequests = new AtomicInteger();
        AtomicInteger malformedRequests = new AtomicInteger();
        AtomicInteger missingRequests = new AtomicInteger();
        AtomicInteger zeroRequests = new AtomicInteger();
        AtomicInteger badRequests = new AtomicInteger();
        AtomicInteger persistRequests = new AtomicInteger();

        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/item/price/TITANIUM_RING/analysis", exchange -> {
            if (rateRequests.incrementAndGet() == 1) {
                respond(exchange, 429, "{\"error\":\"rate limited\"}", "1");
            } else {
                respond(exchange, 200, SUCCESS, null);
            }
        });
        server.createContext("/api/item/price/TIMEOUT_RING/analysis", exchange -> {
            timeoutRequests.incrementAndGet();
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, SUCCESS, null);
        });
        server.createContext("/api/item/price/FIVE_RING/analysis", exchange -> {
            if (fiveRequests.incrementAndGet() == 1) {
                respond(exchange, 500, "{\"error\":\"upstream\"}", null);
            } else {
                respond(exchange, 200, SUCCESS, null);
            }
        });
        server.createContext("/api/item/price/NOHEADER_RING/analysis", exchange -> {
            if (noHeaderRequests.incrementAndGet() == 1) {
                respond(exchange, 429, "{\"error\":\"rate limited\"}", null);
            } else {
                respond(exchange, 200, SUCCESS, null);
            }
        });
        server.createContext("/api/item/price/MALFORMED_RING/analysis", exchange -> {
            malformedRequests.incrementAndGet();
            respond(exchange, 200, "{", null);
        });
        server.createContext("/api/item/price/MISSING_RING/analysis", exchange -> {
            missingRequests.incrementAndGet();
            respond(exchange, 200, "{\"medianPrice\":1,\"salesPerDay\":1}", null);
        });
        server.createContext("/api/item/price/ZERO_RING/analysis", exchange -> {
            zeroRequests.incrementAndGet();
            respond(exchange, 200, "{\"totalSales\":0}", null);
        });
        server.createContext("/api/item/price/BAD_RING/analysis", exchange -> {
            badRequests.incrementAndGet();
            respond(exchange, 404, "{\"error\":\"unknown item\"}", null);
        });
        server.createContext("/api/item/price/PERSIST_RING/analysis", exchange -> {
            persistRequests.incrementAndGet();
            respond(exchange, 503, "{\"error\":\"upstream\"}", "5");
        });
        server.createContext("/api/item/price/ALIAS_RING/analysis", exchange ->
                respond(exchange, 404, "{\"error\":\"unknown tag\"}", null));
        server.createContext("/api/item/price/PET_ALIAS_RING/analysis", exchange ->
                respond(exchange, 200, SUCCESS, null));
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "failure-probe-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();

        try {
            System.setProperty("discordia.cofl.analysisUrl",
                    "http://127.0.0.1:" + server.getAddress().getPort()
                            + "/api/item/price/%s/analysis?days=7");
            System.setProperty("discordia.cofl.baseRetryMs", "50");
            System.setProperty("discordia.cofl.requestSpacingMs", "20");
            System.setProperty("discordia.cofl.requestTimeoutMs", "100");
            System.setProperty("discordia.cofl.unavailableTtlMs", "60000");

            SoldStats sold = new SoldStats();
            GlobalSales global = start(sold, "rate");
            global.requestPriority("TITANIUM_RING", "TITANIUM_RING");
            waitFor(rateRequests, 1, 2_000L);
            Thread.sleep(150L);
            SoldStats.Stats after429 = sold.stats("TITANIUM_RING");
            check(after429.pending(), "429 cleared the pending state");
            check(!after429.unavailable(), "429 was mislabeled unavailable");
            check(after429.count() == 0, "429 produced a zero-sale count as data");
            check(global.health().contains("HTTP 429"), "health did not record the 429");
            waitForCount(sold, "TITANIUM_RING", 5, 4_000L);
            check(global.health().contains("success"), "health did not record the successful retry");

            global.requestPriority("TIMEOUT_RING", "TIMEOUT_RING");
            waitFor(timeoutRequests, 1, 2_000L);
            Thread.sleep(200L);
            SoldStats.Stats timeout = sold.stats("TIMEOUT_RING");
            check(timeout.pending(), "timeout cleared the pending state");
            check(!timeout.unavailable(), "timeout was mislabeled unavailable");

            global.requestPriority("FIVE_RING", "FIVE_RING");
            waitFor(fiveRequests, 1, 2_000L);
            Thread.sleep(120L);
            check(sold.stats("FIVE_RING").pending(), "500 did not stay pending");
            waitForCount(sold, "FIVE_RING", 5, 3_000L);

            global.requestPriority("NOHEADER_RING", "NOHEADER_RING");
            waitFor(noHeaderRequests, 1, 2_000L);
            Thread.sleep(120L);
            check(sold.stats("NOHEADER_RING").pending(), "429 without Retry-After did not stay pending");
            waitForCount(sold, "NOHEADER_RING", 5, 3_000L);

            global.requestPriority("MALFORMED_RING", "MALFORMED_RING");
            waitFor(malformedRequests, 1, 2_000L);
            Thread.sleep(150L);
            SoldStats.Stats malformed = sold.stats("MALFORMED_RING");
            check(malformed.pending() && !malformed.unavailable(),
                    "malformed response was not kept pending");

            global.requestPriority("MISSING_RING", "MISSING_RING");
            waitFor(missingRequests, 1, 2_000L);
            Thread.sleep(150L);
            SoldStats.Stats missing = sold.stats("MISSING_RING");
            check(missing.pending() && !missing.unavailable(),
                    "missing totalSales was not kept pending");

            global.requestPriority("ZERO_RING", "ZERO_RING");
            waitFor(zeroRequests, 1, 2_000L);
            waitForState(sold, "ZERO_RING", 2_000L, stats -> !stats.pending());
            SoldStats.Stats zero = sold.stats("ZERO_RING");
            check(zero.count() == 0 && !zero.unavailable(),
                    "explicit zero was not treated as a resolved empty result");

            SoldStats badSold = new SoldStats();
            GlobalSales bad = start(badSold, "bad");
            bad.requestPriority("BAD_RING", "BAD_RING");
            waitFor(badRequests, 1, 2_000L);
            Thread.sleep(150L);
            SoldStats.Stats unavailable = badSold.stats("BAD_RING");
            check(unavailable.unavailable() && !unavailable.pending(),
                    "permanent 4xx was not marked unavailable");

            global.save();
            SoldStats cachedSold = new SoldStats();
            GlobalSales cached = new GlobalSales(cachedSold, cache("rate"));
            check(cachedSold.stats("TITANIUM_RING").count() == 5,
                    "successful summary did not survive cache reload");

            GlobalSales persist = start(sold, "persist");
            persist.requestPriority("PERSIST_RING", "PERSIST_RING");
            waitFor(persistRequests, 1, 2_000L);
            waitForCondition(() -> persist.health().contains("HTTP 503"), 2_000L,
                    "persist case did not record the 503");
            check(sold.stats("PERSIST_RING").pending(), "persist case did not stay pending");
            persist.save();
            SoldStats resumedSold = new SoldStats();
            GlobalSales resumed = new GlobalSales(resumedSold, cache("persist"));
            check(resumedSold.stats("PERSIST_RING").pending(),
                    "persisted retry state did not reload as pending");
            check(resumed.health().contains("retrying"), "persisted retry state was not visible in health");

            bad.save();
            SoldStats unavailableSold = new SoldStats();
            GlobalSales unavailableReload = new GlobalSales(unavailableSold, cache("bad"));
            check(unavailableSold.stats("BAD_RING").unavailable(),
                    "unavailable state did not survive cache reload");

            SoldStats faultSold = new SoldStats();
            GlobalSales fault = start(faultSold, "fault");
            fault.setFaultMode(GlobalSales.FaultMode.TIMEOUT);
            fault.requestPriority("FAULT_RING", "FAULT_RING");
            Thread.sleep(250L);
            check(faultSold.stats("FAULT_RING").pending(), "fault injection did not stay pending");
            check(fault.health().contains("FAULT:TIMEOUT"),
                    "fault injection was not visible in source health");
            fault.setFaultMode(GlobalSales.FaultMode.UNAVAILABLE);
            fault.requestPriority("FAULT404_RING", "FAULT404_RING");
            // The loop is still sleeping off the injected timeout above (FAULT_RETRY_MS is 2 s and
            // the timeout path also sleeps that long before the next poll), so this wait has to be
            // wider than one retry cycle. The assertion is unchanged; only the harness is patient.
            waitForState(faultSold, "FAULT404_RING", 8_000L, SoldStats.Stats::unavailable);
            check(faultSold.stats("FAULT404_RING").unavailable(),
                    "fault injection did not mark unavailable");
            fault.setFaultMode(GlobalSales.FaultMode.NONE);
            check(!faultSold.stats("FAULT404_RING").unavailable(),
                    "switching fault injection off did not clear injected unavailable state");

            System.out.println("PASS 429 with Retry-After: pending, retry resolved");
            System.out.println("PASS timeout: pending");
            System.out.println("PASS 500: pending, retry resolved");
            System.out.println("PASS 429 without Retry-After: pending, retry resolved");
            System.out.println("PASS malformed JSON: pending");
            System.out.println("PASS missing totalSales: pending");
            System.out.println("PASS explicit zero: resolved empty");
            System.out.println("PASS permanent 4xx: unavailable");
            System.out.println("PASS success cache reload");
            SoldStats aliasSold = new SoldStats();
            GlobalSales alias = start(aliasSold, "alias");
            alias.requestPriority("ALIAS_RING", "ALIAS_RING");
            waitForState(aliasSold, "ALIAS_RING", 2_000L, SoldStats.Stats::unavailable);
            alias.requestPriority("ALIAS_RING", "PET_ALIAS_RING");
            waitForCount(aliasSold, "ALIAS_RING", 5, 3_000L);
            check(!aliasSold.stats("ALIAS_RING").unavailable(),
                    "alias correction did not clear the stale unavailable marker");

            System.out.println("PASS retry and unavailable persistence");
            System.out.println("PASS in-game fault injection state and health");
            System.out.println("PASS stale tag correction: ALIAS_RING -> PET_ALIAS_RING");
        } finally {
            server.stop(0);
            for (String name : new String[]{"rate", "timeout", "five", "noheader", "malformed",
                    "missing", "zero", "bad", "persist", "fault", "alias"}) {
                Files.deleteIfExists(cache(name));
            }
        }
    }

    private static GlobalSales start(SoldStats sold, String cacheName) {
        GlobalSales global = new GlobalSales(sold, cache(cacheName));
        global.start();
        return global;
    }

    private static Path cache(String name) {
        return Paths.get("build", "failure-probe-" + name + ".json");
    }

    private static void respond(HttpExchange exchange, int status, String body,
                                String retryAfter) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private static void waitFor(AtomicInteger requests, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (requests.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        check(requests.get() >= expected, "timed out waiting for request " + expected);
    }

    private static void waitForCount(SoldStats sold, String id, int count, long timeoutMs)
            throws InterruptedException {
        waitForState(sold, id, timeoutMs, stats -> stats.count() == count && !stats.pending());
    }

    private static void waitForState(SoldStats sold, String id, long timeoutMs,
                                     Predicate<SoldStats.Stats> predicate) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!predicate.test(sold.stats(id)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        check(predicate.test(sold.stats(id)), "timed out waiting for state on " + id
                + " (last=" + sold.stats(id) + ")");
    }

    private static void waitForCondition(BooleanSupplier condition, long timeoutMs, String message)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        check(condition.getAsBoolean(), message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
