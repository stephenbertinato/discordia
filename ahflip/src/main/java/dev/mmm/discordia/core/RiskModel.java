package dev.mmm.discordia.core;

import java.util.List;

/**
 * The arithmetic that turns an optimistic ask into a defensible conservative exit.
 *
 * <p>Every number here is deliberately pessimistic, and every assumption is documented so a
 * reader can disagree with it in one place:
 *
 * <ul>
 *   <li><b>Lower bound.</b> The hard profitability gate uses the realized lower quartile
 *       ({@code conservativeSalesQuantile}) when there are enough sales to know one, never the
 *       optimistic next listing. A median is a description of the past, not a price you can get.</li>
 *   <li><b>Buffer.</b> {@code riskBufferPct} is reserved from the exit for price drift while the
 *       item sits listed, a second listing fee if it does not sell, and execution slippage. A
 *       flip that only profits before that reserve is not a flip.</li>
 *   <li><b>Liquidity.</b> Time to sell is Little's law over the pool being priced
 *       ({@code (listings + 1) / sales per hour}), because Hypixel exposes no join key between a
 *       sold auction and the listing it came from.</li>
 *   <li><b>Risk.</b> A sale within the hold horizon is modelled as a Poisson arrival, so
 *       {@code p = 1 - exp(-horizon / expected)}. Expected profit is {@code p * profit} minus the
 *       cost of NOT selling (a relist fee plus a markdown to force a sale) minus the opportunity
 *       cost of the coins locked up in the meantime.</li>
 * </ul>
 *
 * <p>None of this makes a player-driven market safe. It makes an alert that was produced by
 * arithmetic that assumed the best case impossible.
 */
public final class RiskModel {
    private RiskModel() {
    }

    /** How fast the priced pool sells, and the wait that implies. */
    public record Liquidity(double salesPerHour, double expectedHours, boolean known) {
        public static final Liquidity UNKNOWN = new Liquidity(0d, 0d, false);
    }

    /**
     * Little's law over the active pool: {@code (listings + 1) / sales per hour}.
     *
     * <p>Unknown is not zero, and it is not instant either. A rate of zero means "not observed",
     * which the caller must treat as unknown and handle with a wider margin rather than as proof
     * that the item cannot be sold.
     */
    public static Liquidity liquidity(double salesPerHour, int listings) {
        if (!(salesPerHour > 0d) || !Double.isFinite(salesPerHour) || listings < 0) {
            return Liquidity.UNKNOWN;
        }
        return new Liquidity(salesPerHour, (listings + 1d) / salesPerHour, true);
    }

    /**
     * Probability that at least one sale happens inside {@code horizonHours} when sales arrive at
     * rate {@code 1 / expectedHours}. Conservative for a listing that must be the one that sells,
     * and honest about the fact that this is a rate estimate, not a stopwatch.
     */
    public static double saleProbability(double expectedHours, double horizonHours) {
        if (!(expectedHours > 0d) || !(horizonHours > 0d)) {
            return 0d;
        }
        return 1d - Math.exp(-horizonHours / expectedHours);
    }

    /** What an unsold item costs: one more listing fee plus the markdown that forces a sale. */
    public static double unsoldCost(double askPrice, double markdownPct) {
        if (!(askPrice > 0d)) {
            return 0d;
        }
        return Tax.ahListingFee(askPrice) + askPrice * Math.max(0d, markdownPct) / 100d;
    }

    /**
     * Expected profit after sale risk and the cost of the coins it ties up.
     *
     * @param profit                fee-inclusive profit at the conservative exit
     * @param probability           chance of selling inside the hold horizon
     * @param unsoldCost            cost of failing to sell inside the horizon
     * @param expectedHours         Little's-law wait for this pool
     * @param capital               coins committed (buy price plus any add-on cost)
     * @param chargePerDayPct       opportunity cost of locked capital, percent per day
     */
    public static double riskAdjustedProfit(double profit, double probability, double unsoldCost,
                                            double expectedHours, double capital,
                                            double chargePerDayPct) {
        double p = clamp01(probability);
        double expectation = p * profit - (1d - p) * Math.max(0d, unsoldCost);
        double hold = Math.max(0d, expectedHours) / 24d;
        double capitalCharge = Math.max(0d, capital) * Math.max(0d, chargePerDayPct) / 100d * hold;
        return expectation - capitalCharge;
    }

    /**
     * The gross exit the profitability gate is allowed to use: the lower of the target and a
     * known realized lower bound, less the risk buffer.
     */
    public static double conservativeGross(double target, double lowerBound, double bufferPct) {
        double gross = target;
        if (lowerBound > 0d && lowerBound < gross) {
            gross = lowerBound;
        }
        return Math.max(0d, gross * (1d - clampPct(bufferPct) / 100d));
    }

    /** Lower quantile of an already sorted price list; 0 when there is nothing to measure. */
    public static double lowerQuantile(List<Double> sorted, double quantile) {
        if (sorted == null || sorted.isEmpty()) {
            return 0d;
        }
        double q = Math.max(0d, Math.min(1d, quantile));
        return FlipEngine.percentile(sorted, q);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    private static double clampPct(double value) {
        if (!Double.isFinite(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(50d, value));
    }
}
