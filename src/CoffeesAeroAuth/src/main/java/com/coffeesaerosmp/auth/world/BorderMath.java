package com.coffeesaerosmp.auth.world;

/**
 * Pure math for {@link ShipBorderGuard}: the world border as a solid wall for one axis.
 *
 * <p>A ship is a box of half-width {@code half} around its centre. The wall is where the hull touches
 * the border, i.e. the centre may range over {@code [min + half, max - half]}. Past it, any velocity
 * still pointing OUT is reflected (times {@code bounce}) and an inward push proportional to how far
 * the hull has gone in is added, so a ship with thrusters full on settles at the wall instead of
 * creeping through it a tick at a time. Velocity pointing IN is never touched — a ship that is
 * already outside (an old incident) can always fly back, it just cannot go further out.
 */
public final class BorderMath {

    /** Blocks/s of inward push per block of penetration. */
    static final double PUSH_PER_BLOCK = 2.0;

    private BorderMath() {}

    /** @return the corrected velocity along this axis (unchanged when the ship is clear of the wall) */
    public static double axis(double pos, double half, double vel, double min, double max, double bounce) {
        double lo = min + half, hi = max - half;
        if (hi < lo) { lo = hi = (min + max) / 2.0; }      // ship wider than the border: pin to centre
        if (pos > hi && vel > 0) return Math.min(-vel * bounce, -(pos - hi) * PUSH_PER_BLOCK);
        if (pos < lo && vel < 0) return Math.max(-vel * bounce, (lo - pos) * PUSH_PER_BLOCK);
        return vel;
    }
}
