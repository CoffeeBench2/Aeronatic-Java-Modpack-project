package com.coffeesaerosmp.guard.mixin;

import com.coffeesaerosmp.guard.protect.AdminBypass;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Extends {@code /aerobypass} to cover aeroclaims' <b>packet-level</b> protection.
 *
 * <p><b>Why this exists.</b> {@link AdminBypass} reverses protection by un-cancelling NeoForge
 * events ({@code RightClickBlock}, {@code RightClickItem}, {@code BreakEvent}, {@code EntityPlaceEvent}).
 * That covers aeroclaims' {@code ProtectionEvents} path, but aeroclaims <i>also</i> protects a whole
 * second surface through 15 mixins on Create/Aeronautics <b>packets</b> — assemble/disassemble,
 * super glue place+remove, honey glue spawn+resize, merging glue, toolbox equip + dispose-all,
 * clipboard edit, radial wrench, block-entity configuration, throttle lever, kinetic placer and both
 * block-breaking hooks. Those are not NeoForge events, so {@code AdminBypass} never saw them and an
 * op with bypass ON was still blocked from, say, disassembling a claimed ship.</p>
 *
 * <p>Every one of those packet mixins funnels through the single static helper
 * {@code CreateProtectionHelper.isBlockAccessAllowed(BlockPos, ServerPlayer)} and cancels the packet
 * when it returns {@code false}. Injecting at HEAD and returning {@code true} for a bypassing op
 * therefore covers all 15 in one place.</p>
 *
 * <p><b>This became necessary on 2026-07-27.</b> Until then aeroclaims' mixin config was being
 * skipped entirely (its {@code AeroClaimsMixinPlugin} NPE'd on {@code ModList.get()} under Sinytra
 * Connector), so <i>nothing</i> was protected and ops noticed no restriction. With that fixed, the
 * packet protections switch back on — and without this mixin, admins would suddenly lose access they
 * had the day before.</p>
 *
 * <p>Fails soft: {@code require = 0} so the server still boots if aeroclaims is absent or changes
 * the signature. Same guard as the event path — permission level 2 <i>and</i> bypass explicitly
 * toggled on, which is per-session and cleared on logout.</p>
 *
 * @see AdminBypass
 */
@Mixin(targets = "com.mapter.aeroclaims.protect.CreateProtectionHelper", remap = false)
public class AeroClaimsProtectionBypassMixin {

    @Inject(
        method = "isBlockAccessAllowed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/server/level/ServerPlayer;)Z",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private static void aerosmp$adminBypass(BlockPos pos, ServerPlayer player,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (player != null && player.hasPermissions(2) && AdminBypass.isEnabled(player.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
