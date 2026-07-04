package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.PlacementTracker;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RU's deferred track-connection pass (station↔line links) — its own tick subscriber. */
@Pseudo
@Mixin(targets = "com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredConnectionManager", remap = false)
public class DeferredConnectionManagerMixin {

    @Inject(method = "onServerTick", at = @At("HEAD"), require = 0, remap = false)
    private static void railguard$begin(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.begin();
    }

    @Inject(method = "onServerTick", at = @At("RETURN"), require = 0, remap = false)
    private static void railguard$end(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.end();
    }
}
