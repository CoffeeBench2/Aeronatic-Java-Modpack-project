package com.coffeesaerosmp.railguard.mixin;

import com.coffeesaerosmp.railguard.ClaimFireGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fireproofs FTB-claimed chunks. Every block change funnels through this master {@code setBlock}
 * overload — including Burnt's procedure-driven fire spread/charring and vanilla fire, none of which
 * fire a cancellable Forge event. {@link ClaimFireGuard} cancels only fire/smoldering appearing in a
 * claim and fire-driven destruction of claimed blocks; normal edits pass through untouched.
 */
@Mixin(Level.class)
public abstract class ClaimFireMixin {

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void railguard$guardClaimFire(BlockPos pos, BlockState state, int flags, int recursionLeft,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerLevel serverLevel)) return;
        if (ClaimFireGuard.shouldBlock(serverLevel, pos, state)) {
            cir.setReturnValue(false);   // block change refused — the claim stays intact
        }
    }
}
