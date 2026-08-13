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
 * Same claim enforcement as {@link CreateConfigPacketMixin}, for the toolbox equip packet.
 *
 * <p>The toolbox packets are separate records rather than subclasses of
 * {@code BlockEntityConfigurationPacket}, so the base-class injection does not reach them — they
 * needed their own hook. Field is {@code toolboxPos}, not {@code pos}.
 */
@Mixin(targets = "com.simibubi.create.content.equipment.toolbox.ToolboxEquipPacket", remap = false)
public abstract class ToolboxEquipPacketMixin {

    @Shadow(remap = false)
    private BlockPos toolboxPos;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void aeroguard$enforceClaim(ServerPlayer player, CallbackInfo ci) {
        if (!CreateConfigGuard.allowed(player, this.toolboxPos)) {
            ci.cancel();
        }
    }
}
