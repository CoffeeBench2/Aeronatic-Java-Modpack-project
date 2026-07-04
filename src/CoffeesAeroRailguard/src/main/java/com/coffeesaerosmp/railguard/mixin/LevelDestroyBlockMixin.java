package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.ProtectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The event-less destruction net. Create's wrench removal, contraption block breakers (train
 * drills), fluid washouts, support-block pops, and several mob paths all remove blocks via
 * {@code Level.destroyBlock} WITHOUT firing {@code BlockEvent.BreakEvent} — this master overload
 * is their common bottleneck. Protected positions/chunks survive; the generator's own work
 * (PlacementTracker active) and bypassing admins pass through.
 */
@Mixin(Level.class)
public abstract class LevelDestroyBlockMixin {

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void railguard$guardDestroy(BlockPos pos, boolean dropBlock, Entity breaker, int recursionLeft,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) return;
        if (ProtectionEvents.guardDestroy(serverLevel, pos, breaker)) {
            cir.setReturnValue(false);
        }
    }
}
