package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.PlacementTracker;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Terrain clearing (mostly air, which the recorder skips) — bracketed so any solid fill blocks it places are covered. */
@Pseudo
@Mixin(targets = "com.vodmordia.railwaysuntold.worldgen.terrain.clearing.DeferredTerrainClearer", remap = false)
public class DeferredTerrainClearerMixin {

    @Inject(method = "onServerTick", at = @At("HEAD"), require = 0, remap = false)
    private static void railguard$beginTick(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.begin();
    }

    @Inject(method = "onServerTick", at = @At("RETURN"), require = 0, remap = false)
    private static void railguard$endTick(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.end();
    }
}
