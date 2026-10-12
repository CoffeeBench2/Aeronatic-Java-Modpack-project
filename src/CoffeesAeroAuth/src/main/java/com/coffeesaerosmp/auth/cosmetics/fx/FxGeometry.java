package com.coffeesaerosmp.auth.cosmetics.fx;

/**
 * The shapes the effects are built from, as plain math (pure, unit-tested). Angles in radians, distances in
 * blocks. "Forward" is the direction of travel on the horizontal plane, given as (fx, fz) unit vector.
 */
public final class FxGeometry {

    private FxGeometry() {}

    /** Point on a horizontal ring of radius r around the origin. */
    public static double[] ring(double r, double angle) {
        return new double[]{Math.cos(angle) * r, 0, Math.sin(angle) * r};
    }

    /** The side vector (to the right of travel) for a horizontal forward direction. */
    public static double[] right(double fx, double fz) {
        return new double[]{-fz, 0, fx};
    }

    /** Which foot this step lands on: alternates every call index. +1 right, -1 left. */
    public static int foot(long step) {
        return (step & 1) == 0 ? 1 : -1;
    }

    /** A rising helix: angle advances with t, height climbs 0..h then restarts. */
    public static double[] helix(double r, double h, double t, double turnsPerCycle) {
        double cycle = t - Math.floor(t);
        double a = cycle * turnsPerCycle * Math.PI * 2;
        return new double[]{Math.cos(a) * r, cycle * h, Math.sin(a) * r};
    }

    /** Clock hand: angle for a hand that jumps one of {@code marks} steps every {@code ticksPerMark}. */
    public static double clockAngle(long tick, int marks, int ticksPerMark) {
        long step = (tick / Math.max(1, ticksPerMark)) % Math.max(1, marks);
        return -Math.PI / 2 + step * (Math.PI * 2 / marks);
    }

    /**
     * Sideways offset of a waving ribbon behind a ship: a sine across the beam and a slower one up and down,
     * so the ribbon both snakes and breathes.
     */
    public static double[] ribbon(double halfWidth, long tick) {
        double side = Math.sin(tick * 0.22) * halfWidth * 0.8;
        double up = Math.cos(tick * 0.11) * 0.6;
        return new double[]{side, up};
    }

    /** Hue-cycle index (0..n-1) that changes every {@code period} ticks. */
    public static int cycle(long tick, int n, int period) {
        return (int) ((tick / Math.max(1, period)) % Math.max(1, n));
    }
}
