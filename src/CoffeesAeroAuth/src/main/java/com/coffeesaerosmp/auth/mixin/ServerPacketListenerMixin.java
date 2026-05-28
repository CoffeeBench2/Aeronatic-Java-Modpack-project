package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks inventory slot-click packets for unauthenticated players.
 * Events cannot intercept raw packet handling for the player's own inventory,
 * so a mixin is required for complete inventory lock.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerPacketListenerMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void coffees_aero_auth$blockContainerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (player == null) return;
        if (CoffeesAeroAuth.AUTH_MANAGER != null && !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) {
            ci.cancel();
        }
    }
}
