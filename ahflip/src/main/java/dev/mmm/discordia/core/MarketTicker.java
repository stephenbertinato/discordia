package dev.mmm.discordia.core;

/**
 * Predicts Hypixel's next market tick so the cheap heartbeat can be polled hard only when it matters.
 *
 * <p>The public auction dump is republished on a fixed tick. Measured against the live API it is
 * exactly {@code 60_000 ms} apart, and <em>every</em> newly created auction in a tick lands on
 * page 0 of the dump (verified over one tick: 130 of 130 new listings), so a page-0 fetch is what
 * turns a tick into flips.
 *
 * <p>The heartbeat is ~172 KB, which is why a plain 2 s poll costs about 300 MB an hour. Polling
 * it every 150 ms all the time would be indefensible. Instead this class measures the tick period
 * from the transitions it has seen and wakes the scan thread to poll hard for the ~1.2 s window
 * around the predicted boundary only. The cost is a handful of extra requests a minute, and the
 * detection latency after Hypixel publishes drops from up to a full interval to the poll period.
 *
 * <p>It is deliberately conservative about being wrong: if a predicted tick does not arrive, the
 * window widens and a bounded hunt keeps looking, and the next observed transition re-measures the
 * period. A missed prediction costs one late scan, never a silently dead feed.
 */
public final class MarketTicker {
    /** Burst poll period while the predicted boundary is open. */
    public static final long BURST_MS = 150L;
    /** How long before the predicted boundary the burst starts. */
    private static final long LEAD_MS = 1_200L;
    /** How long after the predicted boundary the burst keeps running. */
    private static final long TAIL_MS = 2_000L;
    /** Bounded hunt when the boundary has drifted, before falling back to a slow poll. */
    private static final long HUNT_MS = 400L;
    private static final long HUNT_FOR_MS = 10_000L;
    /** Nothing may sleep longer than this, so a tick can never go unnoticed for long. */
    private static final long MAX_IDLE_MS = 15_000L;
    /** Used until the first transition has been observed. */
    private static final long INITIAL_PERIOD_MS = 60_000L;

    private long lastAt;
    private long periodMs = INITIAL_PERIOD_MS;
    private long burstPolls;
    private long idlePolls;
    private long lastLatencyMs = -1L;

    /**
     * Records the heartbeat observation. {@code tickChanged} must be true only on the transition
     * to a different {@code lastUpdated}, which is the moment the dump was republished.
     */
    public synchronized void observe(boolean tickChanged, long now) {
        if (!tickChanged) {
            return;
        }
        if (lastAt > 0L) {
            long interval = now - lastAt;
            // A plausible tick is 20-120 s. Anything outside that is a restart, a stalled
            // machine or a malformed reading, and must not poison the average.
            if (interval >= 20_000L && interval <= 120_000L) {
                lastLatencyMs = Math.max(0L, interval - periodMs);
                periodMs = (periodMs * 3L + interval) / 4L;
                return;
            }
        }
        lastAt = now;
    }

    /** How long the scan thread may sleep before its next heartbeat poll. */
    public synchronized long nextDelayMs(long now) {
        if (lastAt <= 0L) {
            // No transition seen yet: poll briskly until one arrives, then predict.
            return BURST_MS;
        }
        long expected = lastAt + periodMs;
        long early = expected - LEAD_MS;
        long late = expected + TAIL_MS;
        if (now < early) {
            idlePolls++;
            return Math.max(BURST_MS, Math.min(early - now, MAX_IDLE_MS));
        }
        if (now <= late) {
            burstPolls++;
            return BURST_MS;
        }
        if (now <= late + HUNT_FOR_MS) {
            burstPolls++;
            return HUNT_MS;
        }
        // The cadence moved or stopped. Poll slowly, and re-measure on the next transition.
        idlePolls++;
        return MAX_IDLE_MS;
    }

    /** The measured period, for the status row. */
    public synchronized long periodMs() {
        return periodMs;
    }

    /** Heartbeat requests made inside the burst window, for the status row. */
    public synchronized long burstPolls() {
        return burstPolls;
    }

    /** Heartbeat requests made outside it, for the status row. */
    public synchronized long idlePolls() {
        return idlePolls;
    }

    /** Observed tick period minus what was predicted, for the status row; -1 until measured. */
    public synchronized long lastLatencyMs() {
        return lastLatencyMs;
    }
}
