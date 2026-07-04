package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.PlacementTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every non-air block set while {@link PlacementTracker} is active (i.e. inside Railways
 * Untold's tick — see {@code RailwaysTickHandlerMixin}). All placement paths RU uses (direct
 * setBlock, StructureTemplate.placeInWorld for stations/bridges) funnel through this master
 * overload. Outside the bracket the cost is a single thread-local read.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), require = 0)
    private void railguard$recordRailwayBlock(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!PlacementTracker.active() || state.isAir()) return;
        if ((Object) this instanceof ServerLevel serverLevel) {
            PlacementTracker.record(serverLevel, pos.immutable());
        }
    }
}
