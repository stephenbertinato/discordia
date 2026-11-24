package dev.mmm.discordia.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flips found between normal market ticks by the optional SkyCofl newest-auction lane.
 *
 * <p>These entries are not a replacement for the normal book. They are short-lived additions that
 * are merged into the next regular snapshot and expire so a sold or stale auction cannot remain in
 * the feed forever. The normal scan always wins when both contain the same auction UUID.
 */
public final class FastFlipBook {
    private static final long TTL_MS = 120_000L;

    private record Entry(Flip flip, long at) {
    }

    private final Map<String, Entry> entries = new HashMap<>();

    public synchronized void putAll(List<Flip> flips) {
        if (flips == null || flips.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Flip flip : flips) {
            if (flip != null && flip.kind() == Flip.Kind.AUCTION && !flip.id().isBlank()) {
                entries.put(flip.id(), new Entry(flip, now));
            }
        }
    }

    /** Merges fresh fast flips with the normal engine result, deduplicated by auction UUID. */
    public synchronized List<Flip> merge(List<Flip> normal, int limit) {
        long now = System.currentTimeMillis();
        entries.values().removeIf(entry -> now - entry.at() > TTL_MS);

        Map<String, Flip> byId = new LinkedHashMap<>();
        if (normal != null) {
            for (Flip flip : normal) {
                if (flip != null && !flip.id().isBlank()) {
                    byId.put(flip.id(), flip);
                }
            }
        }
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            byId.putIfAbsent(entry.getKey(), entry.getValue().flip());
        }

        List<Flip> merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparingDouble(Flip::profit).reversed());
        if (limit > 0 && merged.size() > limit) {
            merged = new ArrayList<>(merged.subList(0, limit));
        }
        return List.copyOf(merged);
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        long now = System.currentTimeMillis();
        entries.values().removeIf(entry -> now - entry.at() > TTL_MS);
        return entries.size();
    }
}
