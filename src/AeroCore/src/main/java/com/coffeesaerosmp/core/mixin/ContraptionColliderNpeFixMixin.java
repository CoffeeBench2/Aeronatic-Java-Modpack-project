package com.coffeesaerosmp.core.mixin;

import com.mojang.logging.LogUtils;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client copy of CoffeesAeroAuth's server guard against Create 6.0.10's contraption-collision NPE.
 *
 * <pre>
 * java.lang.NullPointerException: Cannot read field "x" because "mf.axis" is null
 *   at ContinuousOBBCollider.collideMany(ContinuousOBBCollider.java:153)
 *   at ContraptionCollider.collideEntities(ContraptionCollider.java:166)
 *   at ContraptionHandler.tick ← create ClientEvents.onTick
 * </pre>
 *
 * <p>Create runs contraption collision on the client tick too, so once the server stopped crashing
 * (auth 1.7.9) the same bug moved to every client near the trigger: a small entity (item, XP orb,
 * arrow) whose box centre lands exactly on a contraption block collider's centre. All three unit-axis
 * offsets are {@code 0.0}, {@code separate()} never assigns {@code axis}, and the discrete branch
 * dereferences the null left by {@code reset()}. Everyone who loads that spot crashes on join —
 * a client-side chunk ban (2026-10-14, 13241 71 -7262).
 *
 * <p>The fix is identical to the server's: leave {@code Vec3.ZERO} after every {@code reset()}.
 * {@code separate()} only assigns when the new separation beats {@code separation} /
 * {@code normalSeparation}, which {@code reset()} sets to {@code Double.MAX_VALUE}, so real collisions
 * still overwrite these with the true axis; the degenerate case becomes a zero push for one tick.
 * {@code require = 0} so a Create upgrade that renames this cannot stop the game launching.
 */
@Mixin(targets = "com.simibubi.create.foundation.collision.ContinuousOBBCollider$ContinuousSeparationManifold",
       remap = false)
public class ContraptionColliderNpeFixMixin {

    @Shadow Vec3 axis;
    @Shadow Vec3 normalAxis;

    /** Proof the injector applied — grep latest.log for {@code contraption-collision NPE guard}. */
    private static volatile boolean aerocore$announced;

    @Inject(method = "reset()V", at = @At("RETURN"), remap = false, require = 0)
    private void aerocore$noNullAxis(CallbackInfo ci) {
        this.axis = Vec3.ZERO;
        this.normalAxis = Vec3.ZERO;

        if (!aerocore$announced) {
            aerocore$announced = true;
            Logger log = LogUtils.getLogger();
            log.info("[AeroCore] Create contraption-collision NPE guard is active (ContinuousOBBCollider).");
        }
    }
}
