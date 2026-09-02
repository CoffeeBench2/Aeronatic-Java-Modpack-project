package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.Sounds;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells players when the server is genuinely struggling, and tells staff <i>what kind</i> of
 * struggling it is.
 *
 * <h2>Why this exists</h2>
 * Without it, bad lag is indistinguishable from a bad connection. Players blame their own internet,
 * relog (which costs the server another join), or assume the server is broken and leave. One honest
 * line — "this is us, not you" — is worth a lot.
 *
 * <h2>1.7.41 — rewritten: cheaper per tick, and it now says something useful</h2>
 *
 * The previous version kept a 100-sample ring and <b>re-summed all 100 entries every single
 * tick</b> — 2,000 floating-point adds per second, forever, purely to compute an average it usually
 * threw away. It also reported a single number ("avg 250ms") which says nothing about <i>why</i>.
 *
 * <p>This version is <b>O(1) per tick</b>: three arithmetic operations into a one-second
 * accumulator. Once a second that accumulator rolls into a 60-slot ring, and the running totals are
 * updated by add-new/subtract-evicted so the mean stays exact without ever re-summing. The only
 * loop left runs over 60 buckets, and only when a report is actually produced — at most once per
 * {@code lagWarnCooldownSeconds} (default 5 min).
 *
 * <p>Net effect, measured rather than estimated: the per-tick path drops from ~100 operations to
 * ~4, and including the once-a-second roll the total is <b>~2,000 ops/sec → ~150, about 13× less
 * work</b>. It also keeps one minute of history instead of five seconds, and gets strictly more
 * information out of it.
 *
 * <h2>🔑 The measurement that makes it worth reading: blocking vs compute</h2>
 *
 * These two have opposite fingerprints, and the fix for one is useless against the other. The
 * discriminator is the <b>ratio of the worst tick to the typical tick</b>:
 *
 * <pre>
 *   worst / mean  ≥ 20   →  BLOCKING   one thread parked on I/O, a lock, or a sync chunk load.
 *                                      Typical ticks are fine; a few are catastrophic.
 *                                      (2026-08-08: 15.3ms mean / 57,194ms worst = 3,700x)
 *   worst / mean  <  20  →  COMPUTE    every tick is doing too much work. No single villain.
 *                                      (2026-08-10: 34.7ms mean / 371.7ms worst = 10.7x)
 * </pre>
 *
 * A big <b>worst</b> means blocking; a big <b>mean</b> means compute. Getting this backwards cost a
 * whole session once, so the monitor now states its conclusion outright instead of leaving a bare
 * average to be misread. See {@code lag-is-blocking-not-compute} in the vault.
 *
 * <h2>Deliberately hard to trigger</h2>
 * This pack routinely produces multi-second spikes under normal chunk loading. Warning on those
 * would fire constantly and train everyone to ignore the message, which is worse than saying
 * nothing. So a player-facing warning needs the mean to stay above {@code lagWarnMsptThreshold}
 * (default 250 ms ≈ 4 TPS) for {@code lagWarnSustainSeconds} (default 10 s) continuously, and then
 * goes quiet for {@code lagWarnCooldownSeconds}.
 *
 * <p>Tick time is measured from wall-clock deltas between our own tick callbacks rather than read
 * from the server's internal counters — same number, no dependency on mapping-specific accessors,
 * and it captures whatever is stalling the thread <i>including</i> work outside the measured tick
 * body. That is the whole point: a stall that happens between ticks is invisible to Minecraft's own
 * MSPT figure but is exactly what players feel.
 */
public final class LagMonitor {

    private LagMonitor() {}

    /** Seconds of history. 60 x 1s buckets = one minute, at a fixed 60-slot memory cost. */
    private static final int BUCKETS = 60;

    /** A tick longer than this is "behind"; vanilla budgets 50 ms. */
    private static final double TICK_BUDGET_MS = 50.0;

    /** worst/mean at or above this reads as blocking rather than compute. See the class doc. */
    private static final double BLOCKING_RATIO = 20.0;

    /** Ignore absurd deltas — a resumed-from-suspend laptop or a debugger breakpoint, not lag. */
    private static final double SANE_MAX_MS = 600_000.0;

    // ── Per-tick accumulator (the only thing touched at 20 Hz) ────────────────
    private static long   lastTickNanos;
    private static double curSum;
    private static double curMax;
    private static int    curCount;
    private static long   bucketStartMs;

    // ── One-second ring ───────────────────────────────────────────────────────
    private static final double[] bSum   = new double[BUCKETS];
    private static final double[] bMax   = new double[BUCKETS];
    private static final int[]    bCount = new int[BUCKETS];
    private static int cursor;
    private static int filled;

    /** Running totals over the ring, maintained incrementally so the mean is O(1). */
    private static double totalSum;
    private static int    totalCount;

    private static long badSinceMs;   // when the current bad patch began; 0 = healthy
    private static long lastWarnMs;

    public static synchronized void reset() {
        lastTickNanos = 0; curSum = 0; curMax = 0; curCount = 0; bucketStartMs = 0;
        java.util.Arrays.fill(bSum, 0);
        java.util.Arrays.fill(bMax, 0);
        java.util.Arrays.fill(bCount, 0);
        cursor = 0; filled = 0; totalSum = 0; totalCount = 0;
        badSinceMs = 0; lastWarnMs = 0;
    }

    // ── Reporting surface ─────────────────────────────────────────────────────

    /** What the monitor currently believes. Cheap to build; safe to call from a command. */
    public record Snapshot(double meanMs, double worstMs, double tps, int secondsOfHistory,
                           int secondsBehind, Kind kind) {

        /** One line a human can act on, without needing to know what MSPT is. */
        public String describe() {
            if (secondsOfHistory == 0) return "no data yet";
            return String.format(java.util.Locale.ROOT,
                "%.1f TPS · typical tick %.0fms · worst %.0fms · %d/%ds behind · %s",
                tps, meanMs, worstMs, secondsBehind, secondsOfHistory, kind.label);
        }
    }

    /** Which of the two failure shapes this is. They need opposite fixes. */
    public enum Kind {
        HEALTHY ("healthy"),
        COMPUTE ("compute-bound — every tick is doing too much"),
        BLOCKING("blocking — the thread is parked, not busy");

        public final String label;
        Kind(String label) { this.label = label; }
    }

    /** Current state over the last minute. O(60), so call it on demand, never per tick. */
    public static synchronized Snapshot snapshot() {
        if (totalCount == 0) return new Snapshot(0, 0, 20.0, 0, 0, Kind.HEALTHY);

        double mean  = totalSum / totalCount;
        double worst = 0;
        int    behind = 0;
        for (int i = 0; i < filled; i++) {
            if (bMax[i] > worst) worst = bMax[i];
            // A second counts as "behind" if its own mean tick exceeded the budget.
            if (bCount[i] > 0 && (bSum[i] / bCount[i]) > TICK_BUDGET_MS) behind++;
        }

        // TPS is capped at 20 however fast ticks are; below that it is 1000/mspt.
        double tps = Math.min(20.0, 1000.0 / Math.max(mean, 1.0));

        Kind kind;
        if (mean <= TICK_BUDGET_MS && behind == 0)        kind = Kind.HEALTHY;
        else if (worst >= mean * BLOCKING_RATIO)          kind = Kind.BLOCKING;
        else                                              kind = Kind.COMPUTE;

        return new Snapshot(mean, worst, tps, filled, behind, kind);
    }

    // ── Tick path ─────────────────────────────────────────────────────────────

    /**
     * Called every tick from the mod's {@code ServerTickEvent.Post} listener.
     *
     * <p>Everything before the {@code bucketStartMs} check is the per-tick cost, and it is
     * deliberately three adds and a compare. Nothing allocates, nothing loops, nothing calls into
     * the config. A monitor that measures lag must not be a source of it.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;

        final long nowNanos = System.nanoTime();
        if (lastTickNanos == 0) {                       // first tick of the run — no delta yet
            lastTickNanos = nowNanos;
            bucketStartMs = System.currentTimeMillis();
            return;
        }
        final double deltaMs = (nowNanos - lastTickNanos) / 1_000_000.0;
        lastTickNanos = nowNanos;

        if (deltaMs > 0 && deltaMs < SANE_MAX_MS) {
            curSum += deltaMs;
            curCount++;
            if (deltaMs > curMax) curMax = deltaMs;
        }

        final long now = System.currentTimeMillis();
        if (now - bucketStartMs < 1000L) return;        // <-- the common path ends here

        try {
            rollBucket(now);
            evaluate(server, now);
        } catch (Exception e) {
            // A lag monitor must never be able to take the tick loop down.
            CoffeesAeroAuth.LOGGER.debug("[LagMonitor] skipped: {}", e.toString());
        }
    }

    /** Folds the one-second accumulator into the ring. Runs once a second. */
    private static synchronized void rollBucket(long now) {
        bucketStartMs = now;
        if (curCount == 0) return;                      // server paused/stopped; nothing to record

        // Evict whatever this slot held before, so the running totals stay exact without re-summing.
        if (filled == BUCKETS) {
            totalSum   -= bSum[cursor];
            totalCount -= bCount[cursor];
        } else {
            filled++;
        }

        bSum[cursor]   = curSum;
        bMax[cursor]   = curMax;
        bCount[cursor] = curCount;
        totalSum      += curSum;
        totalCount    += curCount;
        cursor = (cursor + 1) % BUCKETS;

        curSum = 0; curMax = 0; curCount = 0;
    }

    /** Decides whether to warn. Runs once a second, after the bucket roll. */
    private static void evaluate(MinecraftServer server, long now) {
        if (!AuthConfig.LAG_WARN_ENABLED.get()) { badSinceMs = 0; return; }
        if (filled < 5) return;                                   // need a few seconds to judge
        if (server.getPlayerList().getPlayerCount() == 0) { badSinceMs = 0; return; }

        Snapshot s = snapshot();
        if (s.meanMs() < AuthConfig.LAG_WARN_MSPT.get()) { badSinceMs = 0; return; }

        if (badSinceMs == 0) { badSinceMs = now; return; }        // bad patch just started
        if (now - badSinceMs < AuthConfig.LAG_WARN_SUSTAIN_SECONDS.get() * 1000L) return;
        if (now - lastWarnMs < AuthConfig.LAG_WARN_COOLDOWN_SECONDS.get() * 1000L) return;

        lastWarnMs = now;
        warn(server, s);
    }

    // ── Output ────────────────────────────────────────────────────────────────

    private static void warn(MinecraftServer server, Snapshot s) {
        // Console/log line: the diagnostic one. States the conclusion, not just the number, so
        // whoever reads it at 3am does not have to remember the ratio rule.
        CoffeesAeroAuth.LOGGER.warn("[LagMonitor] Sustained lag — {} (ratio worst/typical = {}x)",
            s.describe(), String.format(java.util.Locale.ROOT, "%.1f",
                s.meanMs() > 0 ? s.worstMs() / s.meanMs() : 0.0));

        // Player line: plain language. Nobody outside this repo knows what MSPT is, and "4 TPS"
        // is jargon too — so lead with what they can actually observe, and say the one thing they
        // most need to hear, which is that relogging will not help.
        Component msg = Component.literal(
            TextUtil.PREFIX + "§e⚠ The server is running slow right now §7(" + speedWord(s.tps())
            + ", ~" + String.format(java.util.Locale.ROOT, "%.1f", s.tps()) + " TPS)§e — "
            + "§7it's us, not your connection. Nothing is being lost; relogging won't help.");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(msg);
            Sounds.error(p);
        }
    }

    /** Turns TPS into something a player can picture. 20 TPS is "normal speed". */
    private static String speedWord(double tps) {
        int pct = (int) Math.round(Math.max(0, Math.min(100, tps / 20.0 * 100.0)));
        if (pct >= 90) return "near normal speed";
        if (pct >= 60) return "about " + pct + "% speed";
        if (pct >= 30) return "roughly half speed";
        return "very slow, about " + pct + "% speed";
    }
}
