package dev.mmm.discordia.tools;

import com.google.gson.JsonObject;
import dev.mmm.discordia.core.FastAuctionFeed;
import dev.mmm.discordia.core.FlipConfig;
import dev.mmm.discordia.core.HypixelApi;
import dev.mmm.discordia.core.MarketSnapshot;

import java.util.List;

/**
 * Measures the live fast lane against SkyCofl: how often the feed is actually polled, how many
 * candidates survive the price filter, and how many details are resolved. This is the turnaround
 * that decides whether a flip reaches chat before someone else buys it.
 *
 * <p>It uses page 0 of the public Hypixel dump only as the tag reference, exactly like the client
 * does with its cached book. Nothing is bought or clicked.
 */
public final class FeedCadenceProbe {
    private static final long RUN_MS = Math.max(4_000L,
            Long.getLong("discordia.probe.feedRunMs", 15_000L));

    public static void main(String[] args) throws Exception {
        FlipConfig config = new FlipConfig();
        config.bazaar = false;
        config.deterministic = false;

        boolean fullBook = Boolean.getBoolean("discordia.probe.fullBook");
        System.out.println("Loading " + (fullBook ? "full book" : "page 0") + " as the tag reference...");
        long fetchStart = System.nanoTime();
        List<JsonObject> pages = fullBook
                ? HypixelApi.auctionPages()
                : List.of(HypixelApi.auctionPage(0));
        MarketSnapshot reference = MarketSnapshot.from(null, pages);
        long fetchMs = (System.nanoTime() - fetchStart) / 1_000_000L;
        System.out.printf("Reference: %d listings in %d ms%n",
                reference.listingsByKey.values().stream().mapToInt(List::size).sum(), fetchMs);

        FastAuctionFeed feed = new FastAuctionFeed(() -> reference, listing ->
                System.out.printf("  candidate %s %s @ %,.0f%n",
                        listing.skyblockId(), listing.uuid(), listing.price()), config);
        long started = System.currentTimeMillis();
        feed.start();
        long lastReport = 0L;
        while (true) {
            Thread.sleep(1_000L);
            long elapsedMs = System.currentTimeMillis() - started;
            if (elapsedMs >= RUN_MS) {
                break;
            }
            if (elapsedMs - lastReport >= 30_000L) {
                lastReport = elapsedMs;
                System.out.printf("[t=%ds] polls=%d enqueued=%d details=%d%n",
                        elapsedMs / 1000L, feed.pollCount(), feed.enqueuedCount(),
                        feed.detailCount());
            }
        }
        feed.stop();
        long elapsed = System.currentTimeMillis() - started;

        long polls = feed.pollCount();
        long details = feed.detailCount();
        double seconds = elapsed / 1000d;
        System.out.printf("polls=%d in %.1fs -> %.2f/s (%.0f ms apart)%n",
                polls, seconds, polls / seconds, polls == 0 ? 0d : elapsed / (double) polls);
        System.out.printf("details=%d -> %.2f/s%n", details, details / seconds);
        System.out.printf("enqueued=%d -> %.2f/s (demand)%n", feed.enqueuedCount(),
                feed.enqueuedCount() / seconds);
        System.out.println("status: " + feed.status());
    }
}
