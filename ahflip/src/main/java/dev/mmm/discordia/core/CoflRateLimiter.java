package dev.mmm.discordia.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One shared request budget for every public SkyCofl endpoint used by the client.
 *
 * <p>SkyCofl publishes a per-IP limit of 30 requests/10 seconds and 100 requests/minute. The
 * fast auction lane and the global-sales queue used to pace themselves independently, so their
 * combined traffic could exceed that limit during a burst. All Cofl callers now acquire from this
 * single sliding window.
 *
 * <p>The fast feed has a guaranteed share, detail fetches have most of the rest, and background
 * sale lookups are capped so they cannot crowd out the low-latency path. Every category is still
 * checked against the same total limit.
 *
 * <p>This is deliberately in-process: a separate collector process is a separate client with its
 * own budget.
 */
public final class CoflRateLimiter {
    private static final int TOTAL_PER_MINUTE = 100;
    private static final int TOTAL_PER_TEN_SECONDS = 30;

    /**
     * The feed now polls about once a second instead of once every two, so it gets the larger
     * share. Detection latency is what loses a flip; a candidate that is never seen cannot be
     * resolved quickly, and the detail queue is empty most of the time.
     */
    private static final int FEED_PER_MINUTE = 60;
    private static final int FEED_PER_TEN_SECONDS = 10;

    /** Detail lookups only serve real undercuts now, so 25/min covers the demand with headroom. */
    private static final int DETAIL_PER_MINUTE = 25;
    private static final int DETAIL_PER_TEN_SECONDS = 5;

    /** Background sale lookups keep a working share: 15 per minute. */
    private static final int GLOBAL_PER_MINUTE = 15;
    private static final int GLOBAL_PER_TEN_SECONDS = 5;

    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long TEN_SECONDS_MS = 10_000L;
    private static final Object LOCK = new Object();
    private static final Deque<Long> allRequests = new ArrayDeque<>();
    private static final Deque<Long> feedRequests = new ArrayDeque<>();
    private static final Deque<Long> detailRequests = new ArrayDeque<>();
    private static final Deque<Long> globalRequests = new ArrayDeque<>();

    private CoflRateLimiter() {
    }

    /** Acquire capacity for a fast feed poll. */
    public static void acquireFast() throws InterruptedException {
        acquire(feedRequests, FEED_PER_MINUTE, FEED_PER_TEN_SECONDS);
    }

    /** Acquire capacity for an auction-detail request. */
    public static void acquireFastDetail() throws InterruptedException {
        acquire(detailRequests, DETAIL_PER_MINUTE, DETAIL_PER_TEN_SECONDS);
    }

    /** Acquire capacity for a global-sales analysis request. */
    public static void acquireGlobal() throws InterruptedException {
        acquire(globalRequests, GLOBAL_PER_MINUTE, GLOBAL_PER_TEN_SECONDS);
    }

    /** Cofl-hosted requests share this budget; a local/mock endpoint does not. */
    public static boolean isCoflUrl(String url) {
        return url != null && (url.startsWith("https://sky.coflnet.com/")
                || url.startsWith("https://sky-commands.coflnet.com/"));
    }

    private static void acquire(Deque<Long> category, int categoryPerMinute,
                                int categoryPerTenSeconds) throws InterruptedException {
        synchronized (LOCK) {
            while (true) {
                long now = System.currentTimeMillis();
                evict(now);
                boolean totalAvailable = countSince(allRequests, now - ONE_MINUTE_MS) < TOTAL_PER_MINUTE
                        && countSince(allRequests, now - TEN_SECONDS_MS) < TOTAL_PER_TEN_SECONDS;
                boolean categoryAvailable = countSince(category, now - ONE_MINUTE_MS) < categoryPerMinute
                        && countSince(category, now - TEN_SECONDS_MS) < categoryPerTenSeconds;
                if (totalAvailable && categoryAvailable) {
                    allRequests.addLast(now);
                    category.addLast(now);
                    return;
                }
                // Timestamps are millisecond-precision and the windows are short. A short poll
                // keeps the wait exact enough without maintaining a second expiry queue.
                LOCK.wait(50L);
            }
        }
    }

    private static void evict(long now) {
        evict(allRequests, now);
        evict(feedRequests, now);
        evict(detailRequests, now);
        evict(globalRequests, now);
    }

    private static void evict(Deque<Long> requests, long now) {
        while (!requests.isEmpty() && requests.peekFirst() <= now - ONE_MINUTE_MS) {
            requests.removeFirst();
        }
    }

    private static int countSince(Deque<Long> requests, long cutoff) {
        int count = 0;
        for (var iterator = requests.descendingIterator(); iterator.hasNext(); ) {
            if (iterator.next() <= cutoff) {
                break;
            }
            count++;
        }
        return count;
    }
}
