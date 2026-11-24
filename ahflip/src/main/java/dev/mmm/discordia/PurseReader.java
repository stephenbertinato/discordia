package dev.mmm.discordia;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the Hypixel sidebar line, e.g. {@code Purse: 1,234,567} or {@code Purse: 1.2B}. */
public final class PurseReader {
    private static final Pattern PURSE_PATTERN = Pattern.compile(
            "(?i)Purse:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?\\s*[kmb]?)");

    private PurseReader() {
    }

    /** @return the purse value, or -1 when the text does not contain a valid purse line */
    public static double parse(String text) {
        if (text == null || text.isEmpty()) {
            return -1d;
        }
        Matcher matcher = PURSE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return -1d;
        }
        String raw = matcher.group(1).replace(",", "").trim().toLowerCase(Locale.ROOT);
        double multiplier = 1d;
        if (raw.endsWith("k")) {
            multiplier = 1_000d;
            raw = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("m")) {
            multiplier = 1_000_000d;
            raw = raw.substring(0, raw.length() - 1);
        } else if (raw.endsWith("b")) {
            multiplier = 1_000_000_000d;
            raw = raw.substring(0, raw.length() - 1);
        }
        try {
            return Double.parseDouble(raw.trim()) * multiplier;
        } catch (NumberFormatException ignored) {
            return -1d;
        }
    }
}