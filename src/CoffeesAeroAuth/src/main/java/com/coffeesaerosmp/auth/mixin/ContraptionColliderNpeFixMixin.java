package com.coffeesaerosmp.auth.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a Create contraption-collision NPE from killing the server tick loop.
 *
 * <p><b>The crash</b> (2026-08-08, {@code Exception in server tick loop}):
 * <pre>
 * java.lang.NullPointerException: Cannot read field "x" because "mf.axis" is null
 *   at ContinuousOBBCollider.collideMany(ContinuousOBBCollider.java:153)
 *   at ContraptionCollider.collideEntities(ContraptionCollider.java:166)
 * </pre>
 *
 * <p><b>Root cause,</b> read off the bytecode of create 6.0.10. {@code collideMany} runs a
 * separating-axis test per contraption collider box: {@code mf.reset()}, then six
 * {@code mf.separate(axis, distance, …)} calls. {@code reset()} nulls {@code axis} and
 * {@code normalAxis}; {@code separate()} only ever assigns them inside
 * {@code if (Math.abs(distance) != 0.0)}. The first three axes are the world unit axes, so
 * {@code distance} is the centre-to-centre offset on X, Y and Z — and if the entity's collision box
 * lands <i>exactly</i> on a block collider's centre, all three are exactly {@code 0.0} and neither
 * field is ever written. {@code isDiscreteCollision} meanwhile stays {@code true} (it is only
 * cleared by an axis that shows a positive gap, and a zero offset always reports overlap), so
 * {@code collideMany} takes the discrete-collision branch and dereferences the still-null
 * {@code axis}. There is no null check on either field anywhere in the class.
 *
 * <p><b>The fix.</b> Leave {@code Vec3.ZERO} in place of {@code null} after every {@code reset()}.
 * The assignment conditions in {@code separate()} are gated on {@code separation} /
 * {@code normalSeparation} (both {@code Double.MAX_VALUE} after a reset, untouched by this mixin),
 * so a normal collision still overwrites these fields with the real axis exactly as before — this
 * changes nothing except in the degenerate case that used to throw. There the offset works out as
 * {@code 0.0 * Double.MAX_VALUE == 0.0}: no push this tick, and the coincidence breaks on the next
 * one as soon as the entity moves at all.
 *
 * <p>Fixing it here rather than waiting on Create keeps a single stuck entity from crash-looping the
 * server. {@code require = 0} so a future Create version that renames or repairs this cannot stop
 * the server booting — worst case the injector silently no-ops.
 *
 * <p>Not remapped: create ships under its own package names, unobfuscated.
 */
@Mixin(targets = "com.simibubi.create.foundation.collision.ContinuousOBBCollider$ContinuousSeparationManifold",
       remap = false)
public class ContraptionColliderNpeFixMixin {

    @Shadow Vec3 axis;
    @Shadow Vec3 normalAxis;

    /**
     * Proof-of-application flag. {@code require = 0} means a failed injector is silent, and a silent
     * no-op here looks exactly like the fix working right up until the server crash-loops again —
     * so announce once, the first time a contraption actually collides with something. Grep the
     * server log for {@code contraption-collision NPE guard}. One static read per call is nothing
     * next to the six separating-axis tests that follow it.
     *
     * <p>Declared without an initialiser and the logger fetched inline rather than held in a
     * {@code static final} field: both would give this mixin a {@code <clinit>} to merge into the
     * target, which is avoidable complexity for a one-line log.
     */
    private static volatile boolean aerosmp$announced;

    @Inject(method = "reset()V", at = @At("RETURN"), remap = false, require = 0)
    private void aerosmp$noNullAxis(CallbackInfo ci) {
        this.axis = Vec3.ZERO;
        this.normalAxis = Vec3.ZERO;

        if (!aerosmp$announced) {
            aerosmp$announced = true;
            Logger log = LogUtils.getLogger();
            log.info("Create contraption-collision NPE guard is active (ContinuousOBBCollider).");
        }
    }
}
