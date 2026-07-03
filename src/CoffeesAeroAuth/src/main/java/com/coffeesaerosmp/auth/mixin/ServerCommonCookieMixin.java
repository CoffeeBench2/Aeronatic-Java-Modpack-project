package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the gate's auth-cookie response and routes it to {@link CoffeesAeroAuth#handleAuthCookie}.
 *
 * <p>{@code handleCookieResponse} is declared on {@link ServerCommonPacketListenerImpl}; during the
 * play phase the instance is a {@link ServerGamePacketListenerImpl}, which exposes the player. We
 * only act on our own cookie key and cancel so vanilla doesn't treat it as unexpected.</p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonCookieMixin {

    @Inject(method = "handleCookieResponse", at = @At("HEAD"), cancellable = true)
    private void coffees_aero_auth$onCookieResponse(ServerboundCookieResponsePacket packet, CallbackInfo ci) {
        if (!CoffeesAeroAuth.AUTH_COOKIE_KEY.equals(packet.key())) return;
        if (!((Object) this instanceof ServerGamePacketListenerImpl game)) return;

        ServerPlayer player = game.player;
        if (player == null) return;
        byte[] payload = packet.payload();
        MinecraftServer server = player.getServer();
        if (server != null) {
            // Hop off the network thread before touching auth state.
            server.execute(() -> CoffeesAeroAuth.handleAuthCookie(player, payload));
        }
        ci.cancel();
    }
}
