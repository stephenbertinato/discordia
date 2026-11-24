package dev.mmm.discordia;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * The Controls entry for "open the best flip while held".
 *
 * <p>It only ever runs {@code /viewauction} — the command the player could have typed — so nothing
 * is bought, bid on or clicked. An open GUI is never touched, because re-opening the auction screen
 * under the cursor is exactly how a misclick happens.
 */
public final class DiscordiaKeys {
    /** Own category so the binding does not hide inside vanilla's misc group. */
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("discordia", "flips"));

    /** Unbound by default: the player picks the key in Options → Controls → Discordia. */
    public static final KeyMapping OPEN_BEST = new KeyMapping(
            "key.discordia.open_best",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            CATEGORY);

    private DiscordiaKeys() {
    }
}
