package com.coffeesaerosmp.guard.mixin;

import com.coffeesaerosmp.guard.protect.CreateConfigGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes Create's value-settings packet respect land claims.
 *
 * <p>Create applies these settings after checking only spectator / adventure / chunk-loaded /
 * within-reach, and fires no interaction event — so nothing in the protection stack sees it and any
 * player in reach could retune a speed controller inside someone else's claim. See
 * {@link CreateConfigGuard} for the full reasoning and the bytecode this is based on.
 *
 * <p>Injected at HEAD so the packet is dropped before {@code applySettings} runs. {@code require = 0}
 * and a fail-open guard: if Create changes this signature the server still boots, and if the claim
 * lookup misbehaves players keep their access rather than losing it.
 */
@Mixin(targets = "com.simibubi.create.foundation.networking.BlockEntityConfigurationPacket", remap = false)
public abstract class CreateConfigPacketMixin {

    @Shadow(remap = false)
    protected BlockPos pos;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void aeroguard$enforceClaim(ServerPlayer player, CallbackInfo ci) {
        if (!CreateConfigGuard.allowed(player, this.pos)) {
            ci.cancel();
        }
    }
}
