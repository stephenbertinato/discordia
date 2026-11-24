package dev.mmm.discordia.mixin;

import dev.mmm.discordia.DiscordiaClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the held-keybind check on the client thread, where sending a command is safe. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void discordia$openBestWhileHeld(CallbackInfo callback) {
        DiscordiaClient.openBestWhileHeld((Minecraft) (Object) this);
    }
}
