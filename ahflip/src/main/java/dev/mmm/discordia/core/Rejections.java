package dev.mmm.discordia.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Why candidates were not shown.
 *
 * <p>“Did not pass filters” is not a diagnosis. Every rejection in the auction path increments one
 * stable reason code, so an empty feed can be told apart from a disabled one: a thousand
 * {@code THIN_POOL} entries means the book is thin, a thousand {@code GLOBAL_PENDING} entries mean
 * the weekly source has not answered yet, and either is a different problem from
 * {@code CAPITAL_TRAP} on a listing that would otherwise have been announced.
 *
 * <p>Counters accumulate from the last {@link #clear()} and are safe to bump from the fast and the
 * normal lane at the same time.
 */
public final class Rejections {

    /** Stable reason codes. Add new values at the end; never renumber or rename silently. */
    public enum Reason {
        /** Another copy is cheaper, or this exact price is listed twice. */
        NO_ANCHOR,
        /** This is the most expensive copy in its pool: there is nothing above it to undercut. */
        NOT_CHEAPEST,
        /** The pool around the resale anchor is too thin to be a market. */
        THIN_SUPPORT,
        /** The book is too thin to price from and no attribute comparables exist either. */
        THIN_POOL,
        /** The pool exists but its prices disagree too much to be one market. */
        WIDE_SPREAD,
        /** No book reference at all, and the item is only priced from an unsupported ask. */
        NO_BOOK,
        /** Book-only path is switched off. */
        BOOK_ONLY_DISABLED,
        /** The pool would have to be the mixed base-item pool to price this. */
        MIXED_POOL,
        /** No realized sale anywhere: not "unknown", but proof there is no demand. */
        NO_SALES,
        /** Realized volume is below the configured floor. */
        LOW_VOLUME,
        /** Plain item: the market-wide weekly summary is still pending. */
        GLOBAL_PENDING,
        /** Plain item: the weekly summary has too few sales to trust. */
        GLOBAL_ZERO,
        /** Plain item: the entry is above the weekly median multiple. */
        BUY_ABOVE_GLOBAL,
        /** The next listing is many times the realized sale price: a pumped ask, not a target. */
        MANIPULATED_ANCHOR,
        /** A non-recombed copy would not be worth more than a recombed one after the recomb cost. */
        RECOMB_CEILING,
        /** A missing Wither scroll has no bazaar depth, so the blade cannot be completed. */
        SCROLL_UNAVAILABLE,
        /** A partial blade with no full-blade comparable to price it against. */
        NO_FULL_BLADE,
        /** Buy price plus add-on cost (for example missing scrolls) exceeds the item cap. */
        OVER_BUDGET,
        /** No defensible target at all. */
        NO_TARGET,
        /** Target confidence below the configured floor. */
        LOW_CONFIDENCE,
        /** Not enough profit after fees at the conservative exit. */
        MIN_PROFIT,
        /** Not enough return on capital at the conservative exit. */
        MARGIN,
        /** Margin so large the target cannot be real. */
        IMPLAUSIBLE_MARGIN,
        /** Expected hold time exceeds the horizon: a capital trap, however large the headline. */
        CAPITAL_TRAP,
        /** Expected value after sale risk and locked capital is not positive. */
        RISK_ADJUSTED,
        /** Craft flip: at least one ingredient has no price we can prove, so the floor is unknown. */
        CRAFT_UNPRICED,
        /** Craft flip: the finished item has no market-wide sales evidence to sell into. */
        CRAFT_NO_EXIT
    }

    private final Map<Reason, AtomicInteger> counts = new ConcurrentHashMap<>();
    private volatile long since = System.currentTimeMillis();

    /** Increments one reason. Cheap enough to call per listing. */
    public void count(Reason reason) {
        if (reason == null) {
            return;
        }
        counts.computeIfAbsent(reason, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /** A stable, sorted snapshot; empty when nothing was rejected. */
    public Map<Reason, Integer> snapshot() {
        Map<Reason, Integer> out = new EnumMap<>(Reason.class);
        for (Map.Entry<Reason, AtomicInteger> entry : counts.entrySet()) {
            int value = entry.getValue().get();
            if (value > 0) {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    public int total() {
        int total = 0;
        for (AtomicInteger value : counts.values()) {
            total += value.get();
        }
        return total;
    }

    /** How long these counters have been accumulating. */
    public long sinceMillis() {
        return since;
    }

    /** The most common reasons, compact enough for one status row. */
    public String summary(int limit) {
        Map<Reason, Integer> snapshot = snapshot();
        if (snapshot.isEmpty()) {
            return "none";
        }
        return snapshot.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, limit))
                .map(entry -> entry.getKey().name() + " " + entry.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }

    /** One reason by name, for tests and diagnostics. */
    public int get(Reason reason) {
        AtomicInteger value = counts.get(reason);
        return value == null ? 0 : value.get();
    }

    public void clear() {
        counts.clear();
        since = System.currentTimeMillis();
    }
}
