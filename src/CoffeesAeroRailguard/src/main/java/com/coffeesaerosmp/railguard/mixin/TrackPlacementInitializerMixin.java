package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.PlacementTracker;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The ORIGIN placement (starting station + first segments) fires on PLAYER LOGIN, not any tick — this was the unprotected area right at spawn. */
@Pseudo
@Mixin(targets = "com.vodmordia.railwaysuntold.worldgen.placement.TrackPlacementInitializer", remap = false)
public class TrackPlacementInitializerMixin {

    @Inject(method = "onPlayerLogin", at = @At("HEAD"), require = 0, remap = false)
    private static void railguard$beginLogin(PlayerEvent.PlayerLoggedInEvent event, CallbackInfo ci) {
        PlacementTracker.begin();
    }

    @Inject(method = "onPlayerLogin", at = @At("RETURN"), require = 0, remap = false)
    private static void railguard$endLogin(PlayerEvent.PlayerLoggedInEvent event, CallbackInfo ci) {
        PlacementTracker.end();
    }
}
