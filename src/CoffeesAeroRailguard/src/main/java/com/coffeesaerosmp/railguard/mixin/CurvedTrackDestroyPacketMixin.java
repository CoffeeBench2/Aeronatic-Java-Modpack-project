package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.ProtectionEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The LONG ANGLED tracks are Create BEZIER curves: only the two anchor track blocks exist — the
 * span between them is a rendered TrackGraph edge with no blocks at all. "Breaking" one (bare hand
 * OR wrench) sends Create's {@code CurvedTrackDestroyPacket}; the server-side {@code applySettings}
 * deletes the graph edge — no BreakEvent, no destroyBlock, invisible to every block-level guard.
 * This hook cancels that packet when the anchor is protected railway (both anchors of a generated
 * curve are recorded, so checking the addressed one suffices). {@code @Coerce} keeps Create's
 * block-entity hierarchy off our compile classpath.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.trains.track.CurvedTrackDestroyPacket", remap = false)
public class CurvedTrackDestroyPacketMixin {

    @Inject(method = "applySettings(Lnet/minecraft/server/level/ServerPlayer;Lcom/simibubi/create/content/trains/track/TrackBlockEntity;)V",
        at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void railguard$guardCurveDestroy(ServerPlayer player, @Coerce Object trackBlockEntity, CallbackInfo ci) {
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!(trackBlockEntity instanceof BlockEntity blockEntity)) return;
        if (ProtectionEvents.guardCurveDestroy(level, blockEntity.getBlockPos(), player)) {
            ci.cancel();
        }
    }
}
