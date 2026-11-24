package dev.mmm.discordia.mixin;

import dev.mmm.discordia.DiscordiaClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Handles {@code /discordia ...} locally instead of sending it to the server. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void discordia$handleCommand(String command, CallbackInfo callback) {
        if (DiscordiaClient.handleClientCommand(command)) {
            callback.cancel();
        }
    }

    /** Add the local command root after the server command tree has been installed. */
    @Inject(method = "handleCommands", at = @At("TAIL"))
    private void discordia$registerCommands(ClientboundCommandsPacket packet, CallbackInfo callback) {
        DiscordiaClient.registerClientCommands(((ClientPacketListener) (Object) this).getCommands());
    }
}