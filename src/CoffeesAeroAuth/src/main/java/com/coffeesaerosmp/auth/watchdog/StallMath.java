package com.coffeesaerosmp.auth.watchdog;

/**
 * The arithmetic behind the lag tooling, kept free of Minecraft types so it is unit-tested.
 *
 * <p>2026-10-11 audit (Obaa {@code planning/lag-audit-2026-10-11.md}): 45% of spike reports had no real backlog,
 * the player warning never fired once in 99 h, and 80% of the lost time happened BETWEEN ticks where nothing
 * was sampling. These helpers are the fixed rules.
 */
public final class StallMath {

    private StallMath() {}

    /** Where a stall happened. Ticks run Pre→Post; everything Post→next Pre is "between ticks". */
    public enum Kind {
        IN_TICK("inside a tick"),
        BETWEEN_TICKS("between ticks");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    /**
     * What a stall most likely was. The sampler is a separate thread: if the WHOLE process was frozen (a long
     * garbage-collection pause, or the host throttling our CPU quota) the sampler froze too and collected far
     * fewer samples than the stall's length allows. Fewer than a fifth of the expected samples = a pause.
     */
    public static String cause(Kind kind, long stallMs, int samples, long sampleIntervalMs, long sampleAfterMs) {
        long sampleable = Math.max(0, stallMs - sampleAfterMs);
        long expected = sampleable / Math.max(1, sampleIntervalMs);
        if (expected >= 5 && samples * 5L < expected) {
            return "WHOLE-SERVER PAUSE (sampler froze too: garbage collection or CPU throttling, see GC/CPU)";
        }
        return kind == Kind.IN_TICK
            ? "busy inside a tick (entities, machines, spawning, saving)"
            : "busy between ticks (queued tasks: packets, chunk loads, mod jobs)";
    }

    /**
     * TPS over roughly the newest {@code windowMs} of WALL time, from a ring of one-second buckets that hold the
     * summed tick-to-tick time ({@code sums}) and tick count ({@code counts}). A frozen server leaves one bucket
     * holding a single huge delta, which this counts at its true weight; a mean of deltas would dilute it, which
     * is why the old warning never fired. Walks back from {@code newest}. Returns 20 with no data.
     */
    public static double tpsOverWindow(double[] sums, int[] counts, int filled, int newest, double windowMs) {
        double wall = 0;
        long ticks = 0;
        int n = sums.length;
        for (int k = 0; k < filled && wall < windowMs; k++) {
            int i = ((newest - k) % n + n) % n;
            wall += sums[i];
            ticks += counts[i];
        }
        if (wall <= 0) return 20.0;
        return Math.min(20.0, ticks * 1000.0 / wall);
    }

    /** Longest single tick-to-tick gap among the same buckets the TPS was computed over. */
    public static double worstOverWindow(double[] sums, double[] maxes, int filled, int newest, double windowMs) {
        double wall = 0, worst = 0;
        int n = sums.length;
        for (int k = 0; k < filled && wall < windowMs; k++) {
            int i = ((newest - k) % n + n) % n;
            wall += sums[i];
            worst = Math.max(worst, maxes[i]);
        }
        return worst;
    }
}
