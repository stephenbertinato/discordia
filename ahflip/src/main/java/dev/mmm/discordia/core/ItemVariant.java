package dev.mmm.discordia.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of an item that change what it is worth: reforge, stars, pet level and enchants.
 *
 * <p>Two keys are produced:
 * <ul>
 *   <li>{@link #exactKey} — includes the enchant signature, so a 5★ Heroic Hyperion with Ult Wise V
 *       is not pooled with a clean 3★ one;</li>
 *   <li>{@link #groupKey} — same reforge/stars/pet level, ignoring enchants, used when the exact
 *       pool is too thin to price from.</li>
 * </ul>
 *
 * <p>Pets all share the NBT id {@code PET}, so their id is taken from {@code petInfo.type} and
 * their level from the {@code [Lvl N]} prefix of the display name.
 */
public record ItemVariant(String id, String reforge, int stars, int petLevel, int enchants, String enchantHash,
                          List<String> ultimates, String heldItem, String petTier, boolean recombobulated,
                          List<String> abilityScrolls) {

    private static final Pattern LEVEL_PATTERN = Pattern.compile("\\[Lvl (\\d+)]");
    private static final List<String> WITHER_SCROLLS = List.of(
            "IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL");

    /**
     * Reforge stone costs. This is deliberately a cost band, not a hardcoded "cheap" list: two
     * reforges in the same band can share sales, while a Lustrous/Gilded reforge cannot be priced
     * from a basic reforge.
     */
    private static final Map<String, Double> REFORGE_COSTS = Map.ofEntries(
            Map.entry("withered", 2_100_000d),
            Map.entry("fabled", 2_350_000d),
            Map.entry("suspicious", 1_000_000d),
            Map.entry("ancient", 500_000d),
            Map.entry("necrotic", 1_000_000d),
            Map.entry("loving", 500_000d),
            Map.entry("renowned", 3_000_000d),
            Map.entry("warped", 2_000_000d),
            Map.entry("gilded", 50_000_000d),
            Map.entry("lustrous", 66_100_000d));
    private static final double EXPENSIVE_REFORGE_COST = 10_000_000d;

    public static double reforgeCost(String reforge) {
        return reforge == null ? 0d : REFORGE_COSTS.getOrDefault(reforge.toLowerCase(Locale.ROOT), 0d);
    }

    public static boolean expensiveReforge(String reforge) {
        return reforgeCost(reforge) >= EXPENSIVE_REFORGE_COST;
    }

    /** A cheap reforge is not part of the identity; an expensive one is. */
    private String keyReforge() {
        return expensiveReforge(reforge) ? reforge : "";
    }

    /** Same reforge always matches; otherwise only reforge stones in a similar price band do. */
    public static boolean reforgeMatches(String saleReforge, String variantReforge) {
        String left = saleReforge == null ? "" : saleReforge;
        String right = variantReforge == null ? "" : variantReforge;
        if (left.equals(right)) {
            return true;
        }
        double a = reforgeCost(left);
        double b = reforgeCost(right);
        double tolerance = Math.max(1_000_000d, Math.max(a, b) * 0.25d);
        return Math.abs(a - b) <= tolerance;
    }

    public static ItemVariant from(Map<String, Object> attributes, String displayName) {
        if (attributes == null) {
            return new ItemVariant(fallbackId(displayName), "", 0, 0, 0, "0", List.of(), "", "", false, List.of());
        }

        String id = string(attributes.get("id"));
        String reforge = string(attributes.get("modifier"));
        int stars = number(attributes.get("upgrade_level"));
        if (stars == 0) {
            stars = number(attributes.get("dungeon_item_level"));
        }

        boolean pet = "PET".equals(id);
        String petType = pet ? petType(attributes.get("petInfo")) : null;
        if (petType != null) {
            id = petType;
        }
        if (id == null || id.isBlank()) {
            id = fallbackId(displayName);
        }

        // A held pet item can change the displayed rarity (Tier Boost) or add value; it is not
        // part of the auction tier, so it must be part of the variant identity.
        String heldItem = pet ? petField(attributes.get("petInfo"), "heldItem") : "";
        String petTier = pet ? petField(attributes.get("petInfo"), "tier") : "";

        int petLevel = 0;
        if (pet && displayName != null) {
            Matcher matcher = LEVEL_PATTERN.matcher(displayName);
            if (matcher.find()) {
                petLevel = Integer.parseInt(matcher.group(1));
            }
        }

        Map<?, ?> enchantments = attributes.get("enchantments") instanceof Map<?, ?> map ? map : Map.of();
        return new ItemVariant(
                id,
                reforge == null ? "" : reforge.toLowerCase(Locale.ROOT),
                stars,
                petLevel,
                enchantments.size(),
                enchantHash(enchantments),
                ultimates(enchantments),
                heldItem == null ? "" : heldItem,
                petTier == null ? "" : petTier.toUpperCase(Locale.ROOT),
                number(attributes.get("rarity_upgrades")) > 0,
                scrolls(attributes.get("ability_scroll"))
        );
    }

    /** Applied dyes use a SkyBlock `dye_item` tag; never let that premium enter a price pool. */
    public static boolean hasAppliedDye(Map<String, Object> attributes) {
        return attributes != null
                && (attributes.containsKey("dye_item") || attributes.containsKey("dyed_color"));
    }

    /** Older dyed items can lack the tag but still say "Dyed" in their lore. */
    public static boolean hasAppliedDye(Map<String, Object> attributes, List<String> lore) {
        if (hasAppliedDye(attributes)) {
            return true;
        }
        if (lore != null) {
            for (String line : lore) {
                if (line != null && line.toLowerCase(Locale.ROOT).contains("dyed")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The item's identity without its display-name reforge prefix. "Withered Shadow Fury" and
     * "Fabled Shadow Fury" must land in the same pool; the reforge is not the item.
     */
    public String itemBaseKey(String name, String tier) {
        String clean = name;
        if (reforge != null && !reforge.isEmpty()) {
            clean = name.replaceFirst("(?i)(?<!\\S)" + Pattern.quote(reforge) + "\\s+", "");
            if (clean.isEmpty()) {
                clean = name;
            }
        }
        return baseKey(clean, tier) + scrollSuffix();
    }

    public String exactKey(String name, String tier) {
        return itemBaseKey(name, tier) + "|" + keyReforge() + "|" + stars + "|" + petLevel + "|" + enchantHash + itemSuffix();
    }

    public String groupKey(String name, String tier) {
        return itemBaseKey(name, tier) + "|" + keyReforge() + "|" + stars + "|" + petLevel + itemSuffix();
    }

    /**
     * The rarity the pet actually is. A Tier Boost makes the auction display one tier higher than
     * {@code petInfo.tier}; pricing must use the real tier, not the display tier.
     */
    public String actualTier(String displayedTier) {
        return petTier == null || petTier.isEmpty() ? displayedTier : petTier;
    }

    private String itemSuffix() {
        String item = heldItem == null || heldItem.isEmpty() ? "" : "|" + heldItem;
        return petTier == null || petTier.isEmpty() ? item : item + "|tier:" + petTier;
    }

    /** Only items carrying ability scrolls get this suffix. */
    private String scrollSuffix() {
        return abilityScrolls == null || abilityScrolls.isEmpty()
                ? "" : "|scrolls:" + String.join(",", abilityScrolls);
    }

    public String scrollSignature() {
        return abilityScrolls == null ? "" : String.join(",", abilityScrolls);
    }

    /**
     * Coflnet expands pet tags to {@code PET_<type>}, while the local NBT id is the pet type
     * itself. Non-pet ids are already the same tag on both sides.
     */
    public String marketTag() {
        return petTier != null && !petTier.isBlank() ? "PET_" + id : id;
    }

    public int witherScrollCount() {
        int count = 0;
        if (abilityScrolls != null) {
            for (String scroll : WITHER_SCROLLS) {
                if (abilityScrolls.contains(scroll)) {
                    count++;
                }
            }
        }
        return count;
    }

    public boolean hasWitherImpact() {
        return witherScrollCount() == WITHER_SCROLLS.size();
    }

    public List<String> missingWitherScrolls() {
        List<String> missing = new ArrayList<>(3);
        for (String scroll : WITHER_SCROLLS) {
            if (abilityScrolls == null || !abilityScrolls.contains(scroll)) {
                missing.add(scroll);
            }
        }
        return missing;
    }

    /**
     * Identity without name or tier, so ended sales (which carry neither) can be counted against
     * the same variant an active listing belongs to.
     */
    public String signature() {
        return id + "|" + keyReforge() + "|" + stars + "|" + petLevel + "|" + enchantHash
                + itemSuffix() + scrollSuffix() + (recombobulated ? "|recomb" : "");
    }

    public static String baseKey(String name, String tier) {
        return name.toLowerCase(Locale.ROOT) + "|" + tier.toUpperCase(Locale.ROOT);
    }

    public String describe() {
        List<String> parts = new ArrayList<>(4);
        if (stars > 0) {
            parts.add(stars + "★");
        }
        if (!reforge.isEmpty()) {
            parts.add(reforge);
        }
        if (petLevel > 0) {
            parts.add("Lv" + petLevel);
        }
        if (enchants > 0) {
            parts.add(enchants + " ench");
        }
        if (heldItem != null && !heldItem.isEmpty()) {
            parts.add("item " + heldItem);
        }
        if (recombobulated) {
            parts.add("recomb");
        }
        if (hasWitherImpact()) {
            parts.add("wither impact");
        } else if (witherScrollCount() > 0) {
            parts.add("scrolls " + witherScrollCount() + "/3");
        }
        return parts.isEmpty() ? "plain" : String.join(", ", parts);
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    /** Last-resort identity for malformed/missing NBT: never leave an item with a null id. */
    private static String fallbackId(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "UNKNOWN_ITEM";
        }
        String clean = displayName.replaceAll("§.", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return clean.isEmpty() ? "UNKNOWN_ITEM" : clean;
    }

    private static int number(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static List<String> scrolls(Object value) {
        List<String> out = new ArrayList<>(3);
        if (value instanceof String text) {
            for (String part : text.split("\\s+")) {
                addScroll(out, part);
            }
        } else if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element != null) {
                    addScroll(out, String.valueOf(element));
                }
            }
        }
        Collections.sort(out);
        return List.copyOf(out);
    }

    private static void addScroll(List<String> out, String value) {
        String scroll = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!scroll.isEmpty() && !out.contains(scroll)) {
            out.add(scroll);
        }
    }

    private static String petType(Object value) {
        return petField(value, "type");
    }

    private static String petField(Object value, String key) {
        if (!(value instanceof String json)) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            return object.has(key) ? object.get(key).getAsString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The expensive enchants only, as {@code name:level}; normal enchants are cheap filler. */
    private static List<String> ultimates(Map<?, ?> enchantments) {
        List<String> out = new ArrayList<>(2);
        for (Map.Entry<?, ?> entry : enchantments.entrySet()) {
            String name = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (name.contains("ultimate")) {
                out.add(name + ":" + entry.getValue());
            }
        }
        Collections.sort(out);
        return List.copyOf(out);
    }

    /** Stable 64-bit FNV-1a over the sorted enchant list; 0 when there are none. */
    private static String enchantHash(Map<?, ?> enchantments) {
        if (enchantments.isEmpty()) {
            return "0";
        }
        List<String> parts = new ArrayList<>(enchantments.size());
        for (Map.Entry<?, ?> entry : enchantments.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        Collections.sort(parts);

        long hash = 0xcbf29ce484222325L;
        for (String part : parts) {
            for (int i = 0; i < part.length(); i++) {
                hash ^= part.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= ';';
            hash *= 0x100000001b3L;
        }
        return Long.toHexString(hash);
    }
}
