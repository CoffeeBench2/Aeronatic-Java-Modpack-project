package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.PlacementTracker;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RU places STATIONS through this deferred processor, which subscribes to the server tick on its
 * own — completely outside {@code ServerTickHandler.onServerTickEnd} (which is why 1.0.0 protected
 * the track but not the stations). Same bracket pattern as {@link RailwaysTickHandlerMixin}.
 */
@Pseudo
@Mixin(targets = "com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredSchematicPlacer", remap = false)
public class DeferredSchematicPlacerMixin {

    @Inject(method = "onServerTick", at = @At("HEAD"), require = 0, remap = false)
    private static void railguard$begin(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.begin();
    }

    @Inject(method = "onServerTick", at = @At("RETURN"), require = 0, remap = false)
    private static void railguard$end(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.end();
    }
}
