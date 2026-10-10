package com.coffeesaerosmp.guard.mixin;

import com.coffeesaerosmp.guard.config.GuardConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecart contraptions are OFF (owner 2026-10-10). {@code tryAssemble(AbstractMinecart)} is the single
 * assembly entry of Create 6.0.10's cart assembler (javap-verified), so cancelling at HEAD covers every
 * assembler — including ones already placed in the world, which removing the recipe alone would miss.
 * Disassembly is a different path and keeps working.
 *
 * <p>require = 1: if a Create update renames the method, the boot fails loudly instead of the
 * restriction silently doing nothing.
 */
@Mixin(targets = "com.simibubi.create.content.contraptions.mounted.CartAssemblerBlockEntity", remap = false)
public abstract class CartAssemblerBlockEntityMixin {

    /** Last warning time per assembler position — a cart sitting on a powered assembler retries every tick. */
    @Unique
    private static final Map<BlockPos, Long> aeroguard$lastWarn = new ConcurrentHashMap<>();

    @Inject(method = "tryAssemble", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void aeroguard$noCartContraptions(AbstractMinecart cart, CallbackInfo ci) {
        if (!GuardConfig.CART_CONTRAPTIONS_DISABLED.get()) return;
        ci.cancel();
        BlockEntity self = (BlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = self.getBlockPos().immutable();
        long now = System.currentTimeMillis();
        Long last = aeroguard$lastWarn.get(pos);
        if (last != null && now - last < 5000) return;
        aeroguard$lastWarn.put(pos, now);
        if (aeroguard$lastWarn.size() > 512) aeroguard$lastWarn.clear();
        Component msg = Component.literal("§cCart contraptions are disabled §7— build an Aeronautics drill instead.");
        for (ServerPlayer p : level.players())
            if (p.blockPosition().distSqr(pos) <= 16 * 16) p.displayClientMessage(msg, true);
    }
}
