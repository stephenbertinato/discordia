package dev.mmm.discordia.core;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Just enough NBT to answer one question: what SkyBlock item is this?
 *
 * <p>{@code /v2/skyblock/auctions_ended} returns no item name, only compressed NBT, so this is the
 * only way to build realized-sale statistics. The path we need is
 * {@code tag.ExtraAttributes.id}.
 */
public final class Nbt {
    private static final int TAG_END = 0;
    private static final int TAG_COMPOUND = 10;

    private Nbt() {
    }

    /** Returns the SkyBlock id (e.g. {@code HYPERION}) or null. Never throws. */
    public static String skyblockId(String itemBytesBase64) {
        Map<String, Object> attributes = extra(itemBytesBase64);
        if (attributes == null) {
            return null;
        }
        Object id = attributes.get("id");
        return id instanceof String value && !value.isEmpty() ? value : null;
    }

    /**
     * Returns the item's {@code ExtraAttributes} compound, or null. Never throws.
     *
     * <p>Used for variant keys (reforge, stars, enchants, pet level); same parse as
     * {@link #skyblockId(String)} so callers only pay for it once.
     */
    /** ExtraAttributes plus the display lore, from a single parse. */
    public record Decoded(Map<String, Object> extra, List<String> lore, String name) {
    }

    public static Map<String, Object> extra(String itemBytesBase64) {
        Decoded decoded = decode(itemBytesBase64);
        return decoded == null ? null : decoded.extra();
    }

    public static Decoded decode(String itemBytesBase64) {
        if (itemBytesBase64 == null || itemBytesBase64.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(itemBytesBase64);
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(raw)))) {
                int type = in.readUnsignedByte();
                if (type != TAG_COMPOUND) {
                    return null;
                }
                readString(in); // root name, usually empty
                Map<String, Object> root = readCompound(in);
                boolean debug = System.getProperty("discordia.debug") != null;

                // Hypixel wraps the stack in its own envelope ({i: [...]} today), and that
                // envelope has changed before. Find ExtraAttributes wherever it lives instead of
                // hard-coding the path.
                Map<String, Object> attributes = findExtra(root, 0);
                if (debug && attributes == null) {
                    System.out.println("  root keys: " + root.keySet() + " (no ExtraAttributes)");
                }
                List<String> lore = findLore(root, 0);
                return new Decoded(attributes, lore == null ? List.of() : lore, findString(root, "Name", 0));
            }
        } catch (Exception e) {
            if (System.getProperty("discordia.debug") != null) {
                e.printStackTrace();
            }
            return null;
        }
    }

    private static String findString(Object node, String key, int depth) {
        if (depth > 12) {
            return null;
        }
        if (node instanceof Map<?, ?> map) {
            Object value = map.get(key);
            if (value instanceof String text) {
                return text;
            }
            for (Object child : map.values()) {
                String found = findString(child, key, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object child : list) {
                String found = findString(child, key, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> findLore(Object node, int depth) {
        if (depth > 12) {
            return null;
        }
        if (node instanceof Map<?, ?> map) {
            Object lore = map.get("Lore");
            if (lore instanceof List<?> list) {
                List<String> lines = new ArrayList<>(list.size());
                for (Object line : list) {
                    if (line instanceof String text) {
                        lines.add(text);
                    }
                }
                if (!lines.isEmpty()) {
                    return lines;
                }
            }
            for (Object value : map.values()) {
                List<String> found = findLore(value, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                List<String> found = findLore(value, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> findExtra(Object node, int depth) {
        if (depth > 12) {
            return null;
        }
        if (node instanceof Map<?, ?> map) {
            Object extra = map.get("ExtraAttributes");
            if (extra instanceof Map<?, ?> attributes) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) attributes;
                return typed;
            }
            for (Object value : map.values()) {
                Map<String, Object> found = findExtra(value, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object value : list) {
                Map<String, Object> found = findExtra(value, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) {
                return map;
            }
            String name = readString(in);
            map.put(name, readPayload(in, type));
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        return in.readUTF();
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                int length = in.readInt();
                byte[] value = new byte[length];
                in.readFully(value);
                return value;
            }
            case 8:
                return readString(in);
            case 9: {
                int elementType = in.readUnsignedByte();
                int length = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, length));
                if (elementType == TAG_END) {
                    return list;
                }
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(in, elementType));
                }
                return list;
            }
            case TAG_COMPOUND:
                return readCompound(in);
            case 11: {
                int length = in.readInt();
                int[] value = new int[length];
                for (int i = 0; i < length; i++) {
                    value[i] = in.readInt();
                }
                return value;
            }
            case 12: {
                int length = in.readInt();
                long[] value = new long[length];
                for (int i = 0; i < length; i++) {
                    value[i] = in.readLong();
                }
                return value;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }
}
