package com.coffeesaerosmp.auth.kinetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Decides the maximum rotational speed a given kinetic block is allowed.
 *
 * <h2>The rule</h2>
 * <pre>
 *   inside a Sable sub-level (an assembled ship)  →  rpmCapSubLevel   (default 256)
 *   anywhere else                                 →  rpmCapWorld      (default 256)
 * </pre>
 *
 * <b>The shipped defaults cap nothing.</b> Both ceilings are 256, which is also Create's own
 * {@code maxRotationSpeed}, so out of the box this changes no behaviour at all — and because the
 * two values are equal, the Sable lookup is skipped entirely, so it costs nothing either. The
 * machinery is present and adjustable; lowering {@code rpmCapWorld} (128 being the usual choice) is
 * what turns it into a real cap.
 *
 * <p>The reason one might: RPM is a direct multiplier on how much work Create performs per tick — a
 * belt at 256 RPM moves twice the items of one at 128, a mixer completes twice the recipes, and all
 * of it lands on the single-threaded tick loop. Ships need speed to fly; a stamping farm parked in
 * the overworld does not.
 *
 * <h2>🔴 What Create does at the cap, and why the default here is not to destroy</h2>
 *
 * Read from Create 6.0.10 bytecode, {@code RotationPropagator.propagateNewSource}: when a network's
 * speed exceeds {@code maxRotationSpeed}, Create calls <b>{@code Level.destroyBlock(pos, true)}</b>.
 * Overspeed does not stop a machine — it <b>breaks the block</b>.
 *
 * <p>That is fine as Create's own designed punishment at its own configured ceiling, because players
 * built with that ceiling in mind. It is <b>not</b> fine as the result of an admin lowering the
 * ceiling underneath them: every machine already running between the new cap and Create's ceiling
 * would be destroyed the moment its network next updated, with no warning and no way back.
 *
 * <p>So {@code rpmCapDestroys} defaults to <b>false</b>: the over-speed connection simply refuses to
 * propagate, and the machine stops instead of exploding. {@code /authmod rpm scan} exists so the
 * blast radius can be measured before anyone considers turning destruction on.
 *
 * <h2>Sable lookup</h2>
 * Via reflection on {@code Sable.HELPER.getContaining(BlockEntity)}, which returns the sub-level
 * containing a block entity or {@code null} for the main world. Reflection rather than a compile
 * dependency for the same reason {@code ShipNameCommand} uses it: Sable is not a build dep, and a
 * missing class must degrade rather than crash. The {@link Method} is resolved once and cached, so
 * the per-call cost is one virtual invoke.
 *
 * <p>⚠ Use the {@code BlockEntity} overload specifically. The vault records that the
 * position-based {@code getContaining(Level, pos)} returns null for a world-space coordinate that
 * actually belongs to a sub-level; the block-entity overload resolves through the entity's own
 * level and position and does not have that problem.
 *
 * <h2>Fail-open, always</h2>
 * Every path that cannot answer confidently returns Create's own configured value, i.e. changes
 * nothing. A bug in a speed cap must never be able to destroy a build or stall a network.
 */
public final class RpmCap {

    private RpmCap() {}

    /** Resolved once. {@code null} = Sable absent or its API moved; we then treat everything as world. */
    private static Method  getContaining;
    private static Object  sableHelper;
    private static boolean sableResolved;

    /** Set true the first time the mixin handler actually runs, so status can prove it applied. */
    private static volatile boolean everFired;
    /** Last cap handed out, for /authmod rpm status. */
    private static volatile int lastCap;

    /**
     * Set by the speed handler and read by the destroy handler within the same
     * {@code propagateNewSource} call. Safe as a plain static because kinetics propagation runs on
     * the server thread only; the mixin additionally verifies that before trusting it.
     */
    private static boolean loweredThisCall;

    // ── The two questions the mixin asks ──────────────────────────────────────

    /**
     * The cap to apply to {@code be}, given Create's own configured ceiling.
     *
     * @param createConfigured what {@code maxRotationSpeed} would have returned
     * @return the effective cap; never above {@code createConfigured}
     */
    public static int capFor(BlockEntity be, int createConfigured) {
        everFired = true;
        try {
            if (!AuthConfig.RPM_CAP_ENABLED.get()) {
                loweredThisCall = false;
                lastCap = createConfigured;
                return createConfigured;
            }

            int world = AuthConfig.RPM_CAP_WORLD.get();
            int ship  = AuthConfig.RPM_CAP_SUBLEVEL.get();

            // Both ceilings equal (the shipped default, 256/256) means the answer cannot depend on
            // where the block is — so skip the Sable lookup entirely. That is what makes leaving
            // this feature switched on genuinely free until someone actually lowers a number.
            int wanted = (world == ship) ? world
                       : (inSubLevel(be) ? ship : world);

            // Never raise Create's own ceiling — only ever lower it. Raising it would let networks
            // past a limit Create's other systems (speed gauges, the controller's slider range)
            // still assume, and we would own every consequence of that.
            int effective = Math.min(wanted, createConfigured);
            loweredThisCall = effective < createConfigured;
            lastCap = effective;
            return effective;

        } catch (Throwable t) {
            loweredThisCall = false;
            lastCap = createConfigured;
            CoffeesAeroAuth.LOGGER.debug("[RpmCap] fell back to Create's value: {}", t.toString());
            return createConfigured;
        }
    }

    /**
     * Should Create be allowed to destroy this block?
     *
     * <p>Only consulted for the destroy inside {@code propagateNewSource}. Returns false only when
     * <b>our</b> lowered cap is what could have tripped it and destruction is switched off.
     */
    public static boolean allowDestroy() {
        try {
            if (!AuthConfig.RPM_CAP_ENABLED.get()) return true;   // not our doing
            if (!loweredThisCall)                  return true;   // Create's own ceiling, not ours
            return AuthConfig.RPM_CAP_DESTROYS.get();
        } catch (Throwable t) {
            return true;                                          // fail open = vanilla behaviour
        }
    }

    // ── Sable ─────────────────────────────────────────────────────────────────

    /** True if this block entity sits inside a Sable sub-level (i.e. on an assembled ship). */
    public static boolean inSubLevel(BlockEntity be) {
        if (be == null) return false;
        Method m = resolve();
        if (m == null) return false;
        try {
            return m.invoke(sableHelper, be) != null;
        } catch (Throwable t) {
            return false;                                         // unknown => treat as world
        }
    }

    private static synchronized Method resolve() {
        if (sableResolved) return getContaining;
        sableResolved = true;
        try {
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
            Field helper = sable.getDeclaredField("HELPER");
            helper.setAccessible(true);
            sableHelper = helper.get(null);
            getContaining = sableHelper.getClass()
                .getMethod("getContaining", BlockEntity.class);
            getContaining.setAccessible(true);
            CoffeesAeroAuth.LOGGER.info("[RpmCap] Sable sub-level lookup wired up.");
        } catch (Throwable t) {
            getContaining = null;
            CoffeesAeroAuth.LOGGER.warn(
                "[RpmCap] Sable sub-level lookup unavailable ({}) — every kinetic block will be "
                + "treated as world, so ships get the world cap too.", t.toString());
        }
        return getContaining;
    }

    // ── Status, for /authmod rpm ──────────────────────────────────────────────

    /** True once the Create mixin has actually run — proves the injection applied. */
    public static boolean hasFired()     { return everFired; }
    public static boolean sableWired()   { return resolve() != null; }
    public static int     lastCapSeen()  { return lastCap; }
}
