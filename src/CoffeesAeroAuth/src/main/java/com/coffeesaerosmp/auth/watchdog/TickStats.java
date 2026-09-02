package com.coffeesaerosmp.auth.watchdog;

/**
 * True per-tick timing — the numbers spark reports, measured the way spark measures them.
 *
 * <h2>Why this exists next to {@link LagMonitor}</h2>
 * {@code LagMonitor} times the gap between consecutive {@code ServerTickEvent.Post} events. That
 * interval is the right basis for TPS, but it is <b>not</b> MSPT: when the server is keeping up it
 * sleeps off the remainder of the 50 ms budget, so the interval sits at ~50 ms however little work
 * the tick actually did. A healthy server whose ticks cost 16 ms of work still reports ~50 ms that
 * way, which is why our figure never matched spark's.
 *
 * <p>MSPT is <b>tick start → tick end</b>. This class measures Pre→Post for the work time and
 * Post→Post for the interval, so both numbers are right for what they claim to be:</p>
 *
 * <ul>
 *   <li><b>MSPT</b> — mean and worst of {@code Post - Pre}. Comparable to spark's MSPT.</li>
 *   <li><b>TPS</b> — {@code min(20, 1000 / meanInterval)}. Capped at 20 because a server that
 *       finishes early sleeps rather than running fast.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * The per-tick path is two nanoTime reads, three adds and a compare. Nothing allocates, nothing
 * loops, nothing touches config. A monitor that measures lag must never be a source of it — the
 * same rule {@link LagMonitor} follows.
 */
public final class TickStats {

    private TickStats() {}

    /** Seconds of history: 60 one-second buckets, fixed memory. */
    private static final int BUCKETS = 60;

    /** Vanilla budgets 50 ms per tick. */
    public static final double TICK_BUDGET_MS = 50.0;

    /** Ignore absurd deltas — a suspended host or a debugger breakpoint is not lag. */
    private static final double SANE_MAX_MS = 600_000.0;

    // ── Per-tick accumulator ──────────────────────────────────────────────────
    private static volatile long tickStartNanos;   // written by Pre, read by Post
    private static long lastPostNanos;

    private static double curWorkSum, curWorkMax, curIntervalSum;
    private static int    curCount;
    private static long   bucketStartMs;

    // ── One-second ring ───────────────────────────────────────────────────────
    private static final double[] bWorkSum  = new double[BUCKETS];
    private static final double[] bWorkMax  = new double[BUCKETS];
    private static final double[] bIntSum   = new double[BUCKETS];
    private static final int[]    bCount    = new int[BUCKETS];
    private static int cursor, filled;

    private static double totalWork, totalInterval;
    private static int    totalCount;

    /** A point-in-time reading. {@code seconds} is how much history backs it. */
    public record Reading(double msptMean, double msptWorst, double tps, int seconds) {
        /** True once there is enough history to be worth showing. */
        public boolean ready() { return seconds > 0; }
    }

    public static synchronized void reset() {
        tickStartNanos = 0; lastPostNanos = 0;
        curWorkSum = curWorkMax = curIntervalSum = 0; curCount = 0; bucketStartMs = 0;
        java.util.Arrays.fill(bWorkSum, 0);
        java.util.Arrays.fill(bWorkMax, 0);
        java.util.Arrays.fill(bIntSum, 0);
        java.util.Arrays.fill(bCount, 0);
        cursor = 0; filled = 0; totalWork = 0; totalInterval = 0; totalCount = 0;
    }

    /** Stamp the tick's start. Must run on {@code ServerTickEvent.Pre}. */
    public static void onTickPre() {
        tickStartNanos = System.nanoTime();
    }

    /** Close the tick out. Must run on {@code ServerTickEvent.Post}. */
    public static void onTickPost() {
        final long now = System.nanoTime();

        final long started = tickStartNanos;
        if (started != 0) {
            double workMs = (now - started) / 1_000_000.0;
            if (workMs >= 0 && workMs < SANE_MAX_MS) {
                curWorkSum += workMs;
                if (workMs > curWorkMax) curWorkMax = workMs;

                // Interval only counts when we have a previous Post to measure from.
                double intervalMs = lastPostNanos == 0 ? workMs : (now - lastPostNanos) / 1_000_000.0;
                if (intervalMs > 0 && intervalMs < SANE_MAX_MS) curIntervalSum += intervalMs;
                else curIntervalSum += workMs;

                curCount++;
            }
        }
        lastPostNanos = now;

        final long wall = System.currentTimeMillis();
        if (bucketStartMs == 0) { bucketStartMs = wall; return; }
        if (wall - bucketStartMs < 1000L) return;   // <-- the common path ends here

        try {
            rollBucket(wall);
        } catch (Exception ignored) {
            // A stats collector must never be able to take the tick loop down.
        }
    }

    private static synchronized void rollBucket(long wall) {
        // Subtract the slot we are about to overwrite so the running totals stay O(1).
        if (filled == BUCKETS) {
            totalWork     -= bWorkSum[cursor];
            totalInterval -= bIntSum[cursor];
            totalCount    -= bCount[cursor];
        }
        bWorkSum[cursor] = curWorkSum;
        bWorkMax[cursor] = curWorkMax;
        bIntSum[cursor]  = curIntervalSum;
        bCount[cursor]   = curCount;

        totalWork     += curWorkSum;
        totalInterval += curIntervalSum;
        totalCount    += curCount;

        cursor = (cursor + 1) % BUCKETS;
        if (filled < BUCKETS) filled++;

        curWorkSum = curWorkMax = curIntervalSum = 0;
        curCount = 0;
        bucketStartMs = wall;
    }

    /** Current reading over the rolling window. Cheap enough to call a few times a second. */
    public static synchronized Reading read() {
        if (totalCount == 0) return new Reading(0, 0, 20.0, 0);

        double msptMean = totalWork / totalCount;
        double meanInterval = totalInterval / totalCount;

        double worst = 0;
        for (int i = 0; i < filled; i++) if (bWorkMax[i] > worst) worst = bWorkMax[i];

        // Capped at 20: a server that finishes a tick early sleeps, it does not run faster.
        double tps = Math.min(20.0, 1000.0 / Math.max(meanInterval, 1.0));

        return new Reading(msptMean, worst, tps, filled);
    }
}
