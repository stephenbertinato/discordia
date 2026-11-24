package dev.mmm.discordia.core;

/**
 * One actionable flip.
 *
 * @param kind         bazaar order flip or auction BIN flip
 * @param name         display name
 * @param id           bazaar product id, or auction uuid
 * @param tier         rarity tier, or "-" for bazaar
 * @param buyPrice     what you pay
 * @param targetPrice  what you sell at
 * @param profit       profit after tax, per unit
 * @param marginPct    profit / buyPrice * 100
 * @param volumePerDay observed or estimated units moved per day (negative when unknown)
 * @param minutesToSell expected wait before this sells, 0 when there is not enough sale data
 * @param coinsPerHour conservative estimate, 0 when unknown
 * @param confidence   0..1, how much we trust the target price
 * @param detail       short human explanation
 * @param medianPrice  realized median used for the target, 0 when not applicable
 */
public record Flip(
        Kind kind,
        String name,
        String id,
        String tier,
        double buyPrice,
        double targetPrice,
        double profit,
        double marginPct,
        double volumePerDay,
        double minutesToSell,
        double coinsPerHour,
        double confidence,
        String detail,
        double medianPrice
) {
    public enum Kind {BAZAAR, AUCTION, NPC, FORGE, SNIPE, KAT}

    /** Compatibility constructor for flips that do not have a realized median. */
    public Flip(Kind kind, String name, String id, String tier, double buyPrice, double targetPrice,
                double profit, double marginPct, double volumePerDay, double minutesToSell,
                double coinsPerHour, double confidence, String detail) {
        this(kind, name, id, tier, buyPrice, targetPrice, profit, marginPct, volumePerDay,
                minutesToSell, coinsPerHour, confidence, detail, 0d);
    }

    /**
     * A copyable command. The mod never runs it: the user decides when to execute.
     */
    public String command() {
        return switch (kind) {
            case AUCTION, NPC, SNIPE -> "/viewauction " + id;
            case BAZAAR -> "/bz " + id;
            case FORGE, KAT -> "";
        };
    }
}
