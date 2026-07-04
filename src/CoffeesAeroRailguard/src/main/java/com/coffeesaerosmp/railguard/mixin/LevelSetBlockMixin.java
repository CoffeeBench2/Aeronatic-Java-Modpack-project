package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.PlacementTracker;
import com.coffeesaerosmp.railguard.ProtectionEvents;
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
        if (state.isAir() || !((Object) this instanceof ServerLevel serverLevel)) return;

        if (PlacementTracker.active()) {
            // Inside an RU handler — everything it sets is railway.
            PlacementTracker.record(serverLevel, pos.immutable());
            return;
        }

        // Create-path auto-record: RU delegates its big curves (S-curve/bezier diagonals) to
        // Create's own track connector, whose block placement happens OUTSIDE every RU handler.
        // Any railway-palette block appearing in or next to protected railway chunks without RU
        // context is that machinery at work — record it. If it turns out a PLAYER placed it
        // (legit track near the line), ProtectionEvents.onPlace rolls the record back.
        if (com.coffeesaerosmp.railguard.RailwayPalette.isRailwayBlock(state)) {
            com.coffeesaerosmp.railguard.RailguardData data =
                com.coffeesaerosmp.railguard.RailguardData.get(serverLevel);
            if (data.isNearProtectedChunk(pos)) {
                data.addAuto(pos.immutable());
                if (ProtectionEvents.DEBUG) {
                    com.coffeesaerosmp.railguard.CoffeesAeroRailguard.LOGGER.info(
                        "[Railguard] auto-record (create-path) at {} in {}.",
                        pos.toShortString(), serverLevel.dimension().location());
                }
            }
        }
    }
}
