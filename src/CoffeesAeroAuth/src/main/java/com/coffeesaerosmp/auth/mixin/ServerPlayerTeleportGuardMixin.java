package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.protect.PlotGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Refuses teleports whose DESTINATION is inside Sable plot space — see
 * {@link PlotGuard#shouldBlockTeleport}.
 *
 * <p>Reported 2026-08-05: warping to a waystone placed inside a ship crashed the server with heavy
 * lag first. A waystone stores its own BLOCK position, and ship blocks live in plot space at
 * ~20,000,000, so the warp is a teleport to plot space — the same
 * {@code ChunkMap.acquireGeneration} NPE as the 08-04 crash, reached through a completely ordinary
 * player action instead of a corrupted playerdata file.
 *
 * <p>WHY AT HEAD, AND WHY THE TICK SWEEP CANNOT COVER THIS: the Set-based overload adds a
 * {@code POST_TELEPORT} region ticket at the destination on its first two lines
 * (ServerPlayer.java:1425) — before the player moves and before any tick handler runs. That ticket
 * is what triggers plot-space chunk generation, so the guard has to be earlier than the ticket, not
 * merely earlier than the next tick.
 *
 * <p>Both public overloads are covered: the {@code Set<RelativeMovement>} one (commands, most mod
 * teleports, waystones) and the plain float-rotation one. Cancelling leaves the player exactly where
 * they were, which is the safe outcome — a redirect to spawn would be a surprising silent yank.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportGuardMixin {

    /**
     * Proof-of-application flags, one per injector — see {@code ContraptionColliderNpeFixMixin}.
     *
     * <p>{@code require = 0} means a failed injector is <b>completely silent</b>: no crash, no log
     * line, no guard. This one protects against a SERVER CRASH (the plot-space
     * {@code ChunkMap.acquireGeneration} NPE), so "silently stopped existing" is the worst possible
     * failure mode and the least visible. Both descriptors were verified against the decompiled
     * 1.21.1 {@code ServerPlayer} on 2026-09-11 and matched exactly — but a future Minecraft or
     * NeoForge update can change a signature, and nothing would say so.
     *
     * <p>Announced on first invocation rather than at class-load, because a mixin that applied but
     * whose target is never reached is a different (and fine) state from one that never applied.
     * Grep the log for {@code PlotGuard teleport guard}.
     *
     * <p>Two separate flags on purpose: the overloads are injected independently, so one can apply
     * while the other silently does not, and a single shared flag would hide exactly that case.
     * Declared without initialisers and with the logger fetched inline, to avoid giving this mixin
     * a {@code <clinit>} to merge into the target.
     */
    private static volatile boolean aeroauth$announcedRelatives;
    private static volatile boolean aeroauth$announcedPlain;

    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void aeroauth$guardTeleportWithRelatives(ServerLevel level, double x, double y, double z,
                                                     java.util.Set<?> relatives, float yaw, float pitch,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (!aeroauth$announcedRelatives) {
            aeroauth$announcedRelatives = true;
            com.mojang.logging.LogUtils.getLogger().info(
                "PlotGuard teleport guard active (ServerPlayer.teleportTo + RelativeMovement set) "
              + "— this is the overload that adds the POST_TELEPORT ticket.");
        }
        if (PlotGuard.shouldBlockTeleport((ServerPlayer) (Object) this, x, z)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
        method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void aeroauth$guardTeleport(ServerLevel level, double x, double y, double z,
                                        float yaw, float pitch, CallbackInfo ci) {
        if (!aeroauth$announcedPlain) {
            aeroauth$announcedPlain = true;
            com.mojang.logging.LogUtils.getLogger().info(
                "PlotGuard teleport guard active (ServerPlayer.teleportTo, plain rotation).");
        }
        if (PlotGuard.shouldBlockTeleport((ServerPlayer) (Object) this, x, z)) {
            ci.cancel();
        }
    }
}
