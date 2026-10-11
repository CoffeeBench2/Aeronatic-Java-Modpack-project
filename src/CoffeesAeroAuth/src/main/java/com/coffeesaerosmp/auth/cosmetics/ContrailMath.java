package com.coffeesaerosmp.auth.cosmetics;

/**
 * Where a ship's contrail comes out: just behind the hull, opposite the direction of travel. Pure — plain doubles —
 * so it is unit-tested without a world.
 *
 * <p>The half-extent of the ship's bounding box along the travel direction is the box's support function
 * ({@code Σ |d_i| · halfSize_i}); the tail sits 1.5 blocks beyond it. If the box does not contain the ship's centre
 * it is not in world coordinates (Sable keeps ships in a plot), so a fixed 6-block half-extent is used instead.
 */
public final class ContrailMath {

    /** Below this speed (blocks per tick, = 5 m/s) a ship is docked or hovering: no contrail. */
    public static final double MIN_SPEED = 0.25;
    private static final double FALLBACK_HALF_EXTENT = 6.0;
    private static final double GAP = 1.5;

    private ContrailMath() {}

    /**
     * @param c centre {x,y,z}; @param v velocity per tick {x,y,z};
     * @param min bounds min {x,y,z}, @param max bounds max {x,y,z} (either may be null)
     * @return the emitter point {x,y,z}, or null when the ship is too slow
     */
    public static double[] tail(double[] c, double[] v, double[] min, double[] max) {
        double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (speed < MIN_SPEED) return null;
        double[] d = {-v[0] / speed, -v[1] / speed, -v[2] / speed};
        double h = FALLBACK_HALF_EXTENT;
        if (min != null && max != null && contains(min, max, c)) {
            h = 0;
            for (int i = 0; i < 3; i++) h += Math.abs(d[i]) * (max[i] - min[i]) / 2.0;
        }
        double t = h + GAP;
        return new double[]{c[0] + d[0] * t, c[1] + d[1] * t, c[2] + d[2] * t};
    }

    private static boolean contains(double[] min, double[] max, double[] p) {
        for (int i = 0; i < 3; i++) if (p[i] < min[i] - 0.5 || p[i] > max[i] + 0.5) return false;
        return true;
    }
}
