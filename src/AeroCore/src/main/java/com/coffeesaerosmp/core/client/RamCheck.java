package com.coffeesaerosmp.core.client;

/**
 * Checks how much heap this instance was launched with, and warns in BOTH directions.
 *
 * <p><b>A mod cannot fix this itself.</b> {@code -Xmx} is fixed when the JVM starts, long before any
 * mod loads, so all we can do is tell the player. Neither the .mrpack nor the CurseForge manifest
 * carries JVM arguments either — it has to be set in the launcher.
 *
 * <p><b>Why the upper bound matters as much as the lower one.</b> Over-allocation is a real, common
 * failure here, not a theoretical one: a client given 22 GB of a 32 GB machine died during loading
 * with NO crash report — the log simply stopped. The heap starves everything that allocates OUTSIDE
 * it, which on this pack is Distant Horizons and the GPU driver. "More RAM is better" is actively
 * wrong advice for this pack and the warning says so.
 */
public final class RamCheck {

    public enum Verdict { OK, TOO_LOW, TOO_HIGH }

    /** Below this and the pack thrashes. Slightly under 5 G because a -Xmx5G JVM reports ~4.9 GiB. */
    private static final long LOW_BYTES  = (long) (4.5 * 1024 * 1024 * 1024);
    /** Above this and native allocations start losing. */
    private static final long HIGH_BYTES = 10L * 1024 * 1024 * 1024;

    private RamCheck() {}

    public static long allocatedMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    public static Verdict verdict() {
        long max = Runtime.getRuntime().maxMemory();
        if (max < LOW_BYTES) return Verdict.TOO_LOW;
        if (max > HIGH_BYTES) return Verdict.TOO_HIGH;
        return Verdict.OK;
    }

    /** One line for the title screen, or null when the allocation is sensible. */
    public static String message() {
        long gb = Math.round(allocatedMb() / 1024.0);
        return switch (verdict()) {
            case TOO_LOW  -> "⚠ Only " + gb + " GB RAM allocated — this pack needs 5-8 GB. Expect stutter.";
            case TOO_HIGH -> "⚠ " + gb + " GB RAM allocated — too much. Use 5-8 GB or the game can crash with no report.";
            case OK       -> null;
        };
    }
}
