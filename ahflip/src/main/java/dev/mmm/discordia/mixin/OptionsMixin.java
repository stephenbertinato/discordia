package dev.mmm.discordia.mixin;

import dev.mmm.discordia.DiscordiaKeys;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft has no API for third-party key bindings, and the Controls screen reads
 * {@code Options.keyMappings} directly. Growing that array is the least invasive way to make the
 * binding appear like a vanilla one.
 *
 * <p>The append happens at the head of {@code load()}, which the constructor calls after the array
 * exists but before {@code options.txt} is read. Adding it any later would silently drop the key the
 * player picked on every relaunch.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Shadow
    @Final
    @Mutable
    public KeyMapping[] keyMappings;

    @Inject(method = "load", at = @At("HEAD"))
    private void discordia$addKeyMappingBeforeLoad(CallbackInfo callback) {
        discordia$ensureKeyMapping();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void discordia$addKeyMapping(CallbackInfo callback) {
        discordia$ensureKeyMapping();
    }

    private void discordia$ensureKeyMapping() {
        KeyMapping[] current = this.keyMappings;
        if (current == null) {
            return;
        }
        for (KeyMapping mapping : current) {
            if (mapping == DiscordiaKeys.OPEN_BEST) {
                return;
            }
        }
        KeyMapping[] extended = Arrays.copyOf(current, current.length + 1);
        extended[current.length] = DiscordiaKeys.OPEN_BEST;
        this.keyMappings = extended;
    }
}
