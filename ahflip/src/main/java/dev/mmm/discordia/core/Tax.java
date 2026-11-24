package dev.mmm.discordia.core;

/**
 * Hypixel's marketplace fees.
 *
 * <p>Auction house BIN listing fee: 1% under 10M, 2% from 10M to 100M, 2.5% above 100M.
 * Claim tax: up to 1% on sales above 1M, capped so the claim never drops below 1M.
 */
public final class Tax {
    private Tax() {
    }

    public static double bazaarNet(double gross, double rate) {
        return gross * (1.0 - rate);
    }

    public static double ahListingFee(double price) {
        double rate = price < 10_000_000 ? 0.01 : price <= 100_000_000 ? 0.02 : 0.025;
        return price * rate;
    }

    public static double ahClaimTax(double sale) {
        if (sale <= 1_000_000) {
            return 0;
        }
        return Math.min(sale * 0.01, sale - 1_000_000);
    }

    public static double ahNet(double sale) {
        return sale - ahListingFee(sale) - ahClaimTax(sale);
    }

    /** Profit from buying an item at {@code buy} and selling it at {@code sale} on the AH. */
    public static double ahProfit(double buy, double sale) {
        return ahNet(sale) - buy;
    }

    /** Gross price whose after-fee value is exactly {@code net}. Used when adding a cost. */
    public static double grossForNet(double net) {
        if (net <= 0d) {
            return 0d;
        }
        double low = 0d;
        double high = Math.max(net * 2d, 1_000_000d);
        for (int i = 0; i < 60 && ahNet(high) < net; i++) {
            high *= 2d;
        }
        for (int i = 0; i < 80; i++) {
            double mid = (low + high) / 2d;
            if (ahNet(mid) < net) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return high;
    }

    public static double bazaarProfit(double buy, double sell, double rate) {
        return bazaarNet(sell, rate) - buy;
    }
}
