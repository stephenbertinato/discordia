package dev.mmm.discordia.collector;

import dev.mmm.discordia.core.FlipConfig;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/** Starts a private, single-purpose AH collector service. */
public final class CollectorMain {
    private CollectorMain() {
    }

    public static void main(String[] args) throws Exception {
        String token = env("COLLECTOR_TOKEN", "");
        if (token.isBlank()) {
            System.err.println("COLLECTOR_TOKEN is required. It is the bearer token for /ws and /health.");
            System.exit(2);
            return;
        }

        String host = env("COLLECTOR_HOST", "127.0.0.1");
        int port = (int) amount(env("COLLECTOR_PORT", "8787"));
        Path stateDir = Path.of(env("COLLECTOR_STATE_DIR", "collector-state"));

        FlipConfig config = new FlipConfig();
        config.bazaar = false;
        config.deterministic = false;
        config.kat = false;
        config.auctions = true;
        config.snipes = true;
        config.budget = amount(env("COLLECTOR_BUDGET", "10b"));
        config.minMarginPct = amount(env("COLLECTOR_MARGIN", "8"));
        config.minSnipeMarginPct = config.minMarginPct;
        config.minProfit = amount(env("COLLECTOR_MIN_PROFIT", "400k"));
        config.minSnipeProfit = amount(env("COLLECTOR_MIN_SNIPE_PROFIT", "100k"));
        config.scanIntervalMs = Math.max(1_000L, (long) amount(env("COLLECTOR_SCAN_INTERVAL_MS", "3000")));
        config.fullRefreshMs = Math.max(60_000L, (long) amount(env("COLLECTOR_FULL_REFRESH_MS", "600000")));
        config.bookOnlyMinMarginPct = Math.max(8d, config.minMarginPct);
        config.bookOnlyMinProfit = Math.max(1_000_000d, config.minProfit);

        CollectorScanner scanner = new CollectorScanner(config, stateDir);
        CollectorServer server = new CollectorServer(host, port, token, scanner::snapshot, scanner::status,
                scanner::applyFilters);
        server.start();
        scanner.start(server::broadcast, server::broadcast);

        System.out.println("Discordia collector listening on ws://" + host + ":" + port + "/ws");
        System.out.println("Health: http://" + host + ":" + port + "/health (Authorization: Bearer <token>)");
        System.out.println("Market budget " + config.budget + ", margin " + config.minMarginPct
                + "%, min profit " + config.minProfit + ", non-BIN snipes on");
        System.out.println("State: " + stateDir.toAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scanner.stop();
            server.close();
        }, "collector-shutdown"));

        new CountDownLatch(1).await();
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double amount(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replace(",", "").replace("_", "");
        if (text.isEmpty()) {
            return 0d;
        }
        double multiplier = 1d;
        char suffix = text.charAt(text.length() - 1);
        if (suffix == 'k' || suffix == 'm' || suffix == 'b') {
            multiplier = suffix == 'k' ? 1_000d : suffix == 'm' ? 1_000_000d : 1_000_000_000d;
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            double value = Double.parseDouble(text) * multiplier;
            return Double.isFinite(value) && value >= 0d ? value : 0d;
        } catch (NumberFormatException e) {
            return 0d;
        }
    }
}
