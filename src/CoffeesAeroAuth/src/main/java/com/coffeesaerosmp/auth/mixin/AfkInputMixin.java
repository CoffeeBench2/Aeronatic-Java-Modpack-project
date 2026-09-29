package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.afk.AfkTracker;
import com.coffeesaerosmp.auth.afk.MacroDetector.Kind;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Player inputs the AFK tracker cannot see through NeoForge events: hotbar changes, inventory clicks,
 * arm swings, sneak/sprint toggles and vehicle steering. See {@code afk/AfkTracker}.
 *
 * <h2>🔴 Injected AFTER ensureRunningOnSameThread, not at HEAD</h2>
 * Every handler below first calls {@code PacketUtils.ensureRunningOnSameThread}, which on the netty
 * thread THROWS to re-queue the packet onto the server thread. A HEAD inject therefore runs twice per
 * packet — once on netty, once on the server — which would double every input the macro detector
 * times, and touch tracker state from a non-server thread. Shifting past that call runs exactly once,
 * on the server thread.
 *
 * <p>Real packet types in every handler signature on purpose: a descriptor mismatch with
 * {@code require = 0} fails silently (vault: mixin-object-param-fails-silently). {@code defaultRequire = 1}
 * in the config makes a mismatch a boot failure instead.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class AfkInputMixin {

    private static final String SAME_THREAD =
        "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
      + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;"
      + "Lnet/minecraft/server/level/ServerLevel;)V";

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCarriedItem", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER))
    private void coffees_aero_auth$afkHotbar(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        AfkTracker.input(player, Kind.HOTBAR);
    }

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER))
    private void coffees_aero_auth$afkInventory(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        AfkTracker.input(player, Kind.INVENTORY);
    }

    @Inject(method = "handleAnimate", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER))
    private void coffees_aero_auth$afkSwing(ServerboundSwingPacket packet, CallbackInfo ci) {
        AfkTracker.input(player, Kind.SWING);
    }

    @Inject(method = "handlePlayerCommand", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER))
    private void coffees_aero_auth$afkSneakSprint(ServerboundPlayerCommandPacket packet, CallbackInfo ci) {
        AfkTracker.input(player, Kind.SNEAK_SPRINT);
    }

    /** Sent every tick while riding; the tracker counts only a change of keys. */
    @Inject(method = "handlePlayerInput", at = @At(value = "INVOKE", target = SAME_THREAD, shift = At.Shift.AFTER))
    private void coffees_aero_auth$afkSteer(ServerboundPlayerInputPacket packet, CallbackInfo ci) {
        AfkTracker.vehicleInput(player, packet.getXxa(), packet.getZza(), packet.isJumping(), packet.isShiftKeyDown());
    }
}
