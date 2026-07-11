package com.coffeesaerosmp.skins.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla only renders the tab-list face column when the connection is encrypted (online-mode) or
 * the server is local — {@code render}'s
 * {@code this.minecraft.isLocalServer() || ...getConnection().isEncrypted()} gate. Our backend is
 * offline-mode behind the transfer gate, so nobody ever saw head icons in Tab. Force the gate open:
 * faces come from each entry's skin lookup, which {@code SkinSyncHandler} already patches to the
 * server-assigned skin for every player.
 */
@Mixin(PlayerTabOverlay.class)
public class TabListHeadsMixin {

    @Redirect(method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/network/Connection;isEncrypted()Z"))
    private boolean aeroskins$alwaysShowTabHeads(Connection connection) {
        return true;
    }
}
