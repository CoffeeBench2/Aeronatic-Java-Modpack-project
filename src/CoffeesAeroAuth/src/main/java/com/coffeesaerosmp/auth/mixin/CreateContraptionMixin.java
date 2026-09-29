package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.exploit.AssemblyScanner;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks Create's assembly search — every Create-family bearing, including Create: Aeronautics'
 * Propeller Bearing and Offroad's Borehead Bearing, since all of them extend {@code Contraption}.
 *
 * <p>Verified against create-1.21.1-6.0.10: {@code searchMovedStructure(Level, BlockPos, Direction)}
 * is public and returns BEFORE any block is moved, {@code anchor} is public, and there is a public
 * {@code getBlocks()} — preferred over shadowing the protected {@code blocks} field.
 *
 * <p>Lives in the COMPAT mixin config ({@code required: false}) so a server without Create still
 * boots, while {@code require = 1} still fails loudly if Create is present and the method has moved.
 * A silent failure here means the exploit detector quietly does nothing, which is the one outcome
 * worse than a loud one.
 */
@Mixin(value = Contraption.class, remap = false)
public abstract class CreateContraptionMixin {

    @Shadow(remap = false)
    public BlockPos anchor;

    @Inject(method = "searchMovedStructure", at = @At("RETURN"), require = 1, remap = false)
    private void aeroauth$scanAssembly(Level level, BlockPos pos, Direction dir,
                                       CallbackInfoReturnable<Boolean> cir) {
        // A failed search moved nothing and assembled nothing — there is no structure to judge.
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        try {
            var blocks = ((Contraption) (Object) this).getBlocks();
            if (blocks == null || blocks.isEmpty()) return;
            AssemblyScanner.scan(level, this.anchor != null ? this.anchor : pos, blocks.keySet());
        } catch (Throwable t) {
            // Never let the detector break an assembly. AssemblyScanner guards itself too; this
            // covers the accessor call that happens before we reach it.
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.error(
                "[Exploit] Create assembly hook failed (assembly unaffected): {}", t.toString());
        }
    }
}
