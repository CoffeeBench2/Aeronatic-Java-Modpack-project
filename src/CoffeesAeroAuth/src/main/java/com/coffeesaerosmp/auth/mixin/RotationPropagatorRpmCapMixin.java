package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.kinetics.RpmCap;
import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Gives Create's rotation cap a per-context value: one ceiling in the world, a higher one inside a
 * Sable sub-level.
 *
 * <h2>Why this exact injection point</h2>
 *
 * Read from Create 6.0.10 bytecode. {@code RotationPropagator.propagateNewSource(KineticBlockEntity)}
 * contains the only enforcement of {@code maxRotationSpeed} that actually gates a network:
 *
 * <pre>
 *   abs(speed)         &gt; AllConfigs.server().kinetics.maxRotationSpeed.get().intValue()
 *   abs(oppositeSpeed) &gt; AllConfigs.server().kinetics.maxRotationSpeed.get().intValue()
 *      || getFlickerScore() &gt; 128
 *          -&gt; level.destroyBlock(pos, true); return;
 * </pre>
 *
 * <p><b>{@code Integer.intValue()} occurs exactly twice in this method, and both are that config
 * read.</b> Verified by disassembly, not assumed. That makes it an unambiguous {@link Redirect}
 * target which needs <em>only</em> Create on the compile path — redirecting
 * {@code ConfigBase$ConfigInt.get()} instead would have dragged in catnip for no benefit.
 *
 * <h2>🔴 The second redirect is the safety one</h2>
 *
 * Overspeed in Create does not stop a machine, it <b>destroys the block</b>. That is Create's own
 * designed punishment at its own ceiling, and players built with it in mind. It is not an
 * acceptable consequence of an admin lowering the ceiling underneath them — every machine already
 * running above the new cap would be destroyed on its next network update.
 *
 * <p>So {@code Level.destroyBlock} is redirected too. When <em>our</em> lowered cap is what tripped
 * it and {@code rpmCapDestroys} is false (the default), the destroy is skipped and
 * {@code propagateNewSource} returns without adopting the new source — the over-speed connection
 * simply refuses to propagate and the machine stops. Create's own ceiling still destroys as normal,
 * and so does the flicker check, because {@link RpmCap#allowDestroy()} returns true whenever we did
 * not lower anything.
 *
 * <h2>🔑 Why the handler parameters are real Create types</h2>
 *
 * A mixin handler parameter typed {@code Object} never matches the descriptor, and under
 * {@code require = 0} that failure is <b>completely silent</b> — no crash, no log line, no feature.
 * That trap cost two rebuilds on the EMI/JEI switch. Create is therefore a {@code compileOnly}
 * dependency purely so these signatures can be exact.
 *
 * <h2>Why {@code require = 0} rather than failing the boot</h2>
 *
 * This targets another mod's internals. If Create updates and moves the check, a hard failure would
 * mean the server does not start at all — a worse outcome than the cap quietly not applying. So it
 * is optional, and the loss of it is made <b>visible</b> instead: {@code /authmod rpm status}
 * reports whether the handler has ever fired, so "the cap is off" can never be mistaken for
 * "the cap is working".
 */
@Mixin(value = RotationPropagator.class, remap = false)
public abstract class RotationPropagatorRpmCapMixin {

    /**
     * Substitutes our per-context cap for Create's global {@code maxRotationSpeed}.
     *
     * <p>The trailing {@code source} parameter is {@code propagateNewSource}'s own argument,
     * captured by Mixin so the handler knows <em>which</em> block is being checked — that is the
     * whole mechanism by which a ship gets a different answer from a base.
     */
    @Redirect(
        method = "propagateNewSource",
        at = @At(value = "INVOKE", target = "Ljava/lang/Integer;intValue()I"),
        require = 0
    )
    private static int aero$contextualMaxRotationSpeed(Integer createConfigured,
                                                       KineticBlockEntity source) {
        return RpmCap.capFor(source, createConfigured == null ? Integer.MAX_VALUE : createConfigured);
    }

    /**
     * Skips Create's destroy when our lowered cap is the only reason it would fire.
     *
     * <p>Returning {@code false} matches what {@code destroyBlock} returns when nothing was
     * destroyed. {@code propagateNewSource} discards the result and returns immediately either way,
     * so the block keeps the speed it already had — which was, by definition, still under the cap.
     */
    @Redirect(
        method = "propagateNewSource",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"),
        require = 0
    )
    private static boolean aero$guardOverspeedDestroy(Level level, BlockPos pos, boolean drop) {
        if (RpmCap.allowDestroy()) return level.destroyBlock(pos, drop);
        return false;
    }
}
