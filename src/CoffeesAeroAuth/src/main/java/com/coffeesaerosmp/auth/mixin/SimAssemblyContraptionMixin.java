package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.exploit.AssemblyScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Hooks Simulated's assembly search — the path a SWIVEL BEARING and the Physics Assembler take.
 *
 * <h2>Why this is separate from {@link CreateContraptionMixin}</h2>
 * "Swivel Bearing" is {@code simulated:swivel_bearing}, from the {@code simulated} mod nested inside
 * {@code create-aeronautics-bundled}. It does NOT produce a Create {@code Contraption} — it
 * assembles a Sable sub-level — so Create's hook never sees it. Searching the pack for
 * "aeronautics" misses it entirely; that one bundle jar ships four mod ids.
 *
 * <h2>Why reflection rather than a compile dependency</h2>
 * The Simulated classes are not on this project's compile classpath, and adding
 * {@code libs/simulated.jar} would break a clean checkout — {@code src/*&#47;libs/} is gitignored and
 * several jars there are already untracked, which is exactly the failure {@code build.gradle}
 * documents for the Skins jar ("a clean checkout could not compile this mod at all").
 *
 * <p>So the target is named by string and {@code getBlocks()} is called reflectively, with the
 * {@link Method} resolved once and cached. This is the same trade {@code world/SableShips} already
 * makes against Sable's API, for the same reason: a renamed method degrades to an honest "no
 * detection" plus one log line, rather than a compile failure or a crash inside someone's ship.
 *
 * <p>Verified against the shipped jar (simulated 1.3.2, nested in the Aeronautics bundle):
 * {@code searchMovedStructure(Level, BlockPos)} is public and returns before any block moves,
 * {@code anchor} is a public final field, and {@code getBlocks()} is public.
 */
@Mixin(targets = "dev.simulated_team.simulated.util.assembly.SimAssemblyContraption", remap = false)
public abstract class SimAssemblyContraptionMixin {

    @Shadow(remap = false)
    public BlockPos anchor;

    /** Resolved once per run; null means Simulated's API is not where we expect it. */
    private static Method aeroauth$getBlocks;
    private static boolean aeroauth$resolved;
    private static boolean aeroauth$warned;

    @Inject(method = "searchMovedStructure", at = @At("RETURN"), require = 1, remap = false)
    private void aeroauth$scanAssembly(Level level, BlockPos pos,
                                       CallbackInfoReturnable<Boolean> cir) {
        // A failed search moved nothing and assembled nothing.
        if (!Boolean.TRUE.equals(cir.getReturnValue())) return;
        try {
            Collection<BlockPos> blocks = aeroauth$blocksOf(this);
            if (blocks == null || blocks.isEmpty()) return;
            AssemblyScanner.scan(level, this.anchor != null ? this.anchor : pos, blocks);
        } catch (Throwable t) {
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.error(
                "[Exploit] Simulated assembly hook failed (assembly unaffected): {}", t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<BlockPos> aeroauth$blocksOf(Object self) {
        if (!aeroauth$resolved) {
            aeroauth$resolved = true;
            try {
                aeroauth$getBlocks = self.getClass().getMethod("getBlocks");
            } catch (NoSuchMethodException e) {
                aeroauth$getBlocks = null;
            }
        }
        if (aeroauth$getBlocks == null) {
            if (!aeroauth$warned) {
                aeroauth$warned = true;
                // Once, not per assembly: a renamed API would otherwise flood the log.
                com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.warn(
                    "[Exploit] SimAssemblyContraption.getBlocks() not found — Swivel Bearing "
                    + "assemblies will NOT be scanned. Simulated's API has moved; update "
                    + "SimAssemblyContraptionMixin.");
            }
            return null;
        }
        try {
            return (Collection<BlockPos>) aeroauth$getBlocks.invoke(self);
        } catch (Exception e) {
            return null;
        }
    }
}
