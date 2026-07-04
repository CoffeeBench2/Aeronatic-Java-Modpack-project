package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.PlacementTracker;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RU expands track into freshly loaded chunks BOTH from ChunkEvent.Load and its own tick — a major placement path missed by 1.0.1. */
@Pseudo
@Mixin(targets = "com.vodmordia.railwaysuntold.worldgen.placement.ChunkLoadTrackExpander", remap = false)
public class ChunkLoadTrackExpanderMixin {

    @Inject(method = "onChunkLoad", at = @At("HEAD"), require = 0, remap = false)
    private static void railguard$beginChunkLoad(ChunkEvent.Load event, CallbackInfo ci) {
        PlacementTracker.begin();
    }

    @Inject(method = "onChunkLoad", at = @At("RETURN"), require = 0, remap = false)
    private static void railguard$endChunkLoad(ChunkEvent.Load event, CallbackInfo ci) {
        PlacementTracker.end();
    }

    @Inject(method = "onServerTick", at = @At("HEAD"), require = 0, remap = false)
    private static void railguard$beginTick(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.begin();
    }

    @Inject(method = "onServerTick", at = @At("RETURN"), require = 0, remap = false)
    private static void railguard$endTick(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.end();
    }
}
