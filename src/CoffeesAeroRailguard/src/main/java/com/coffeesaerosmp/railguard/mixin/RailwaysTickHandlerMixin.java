package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.PlacementTracker;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets Railways Untold's single tick entry point — every block RU generates is placed inside
 * {@code ServerTickHandler.onServerTickEnd} on the server thread. {@code @Pseudo} + require=0: if
 * RU is absent or the method moves, Railguard simply records nothing (protection of already-marked
 * positions keeps working).
 */
@Pseudo
@Mixin(targets = "com.vodmordia.railwaysuntold.ServerTickHandler", remap = false)
public class RailwaysTickHandlerMixin {

    @Inject(method = "onServerTickEnd", at = @At("HEAD"), require = 0, remap = false)
    private static void railguard$beginTracking(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.begin();
    }

    @Inject(method = "onServerTickEnd", at = @At("RETURN"), require = 0, remap = false)
    private static void railguard$endTracking(ServerTickEvent.Post event, CallbackInfo ci) {
        PlacementTracker.end();
    }
}
