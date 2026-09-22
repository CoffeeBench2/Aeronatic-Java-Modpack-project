package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

import java.lang.reflect.Method;

/**
 * Reads TPS and MSPT from <b>spark's own API</b>, so the HUD reports the same numbers spark does.
 *
 * <h2>Why not our own counters</h2>
 * {@link TickStats} measures {@code ServerTickEvent} Pre→Post (the tick's WORK time) and derives TPS
 * from the Post→Post interval. That interval floors at ~50 ms whenever the server keeps up, so our
 * TPS can never exceed 20 and never quite agrees with spark's. Spark computes TPS from its own
 * tick-counting hook and is the number everyone — us, Lagless support, every guide — actually quotes.
 * Two different "correct" numbers on the same screen is worse than one; owner's call 2026-09-07 is
 * that spark wins.
 *
 * <h2>Reflection, not a compile dependency</h2>
 * Spark bundles {@code me.lucko.spark.api.*} inside its own mod jar rather than publishing it on our
 * build classpath, and this build runs offline. Reflection also keeps spark a genuinely OPTIONAL
 * dependency: a server without it simply falls back to {@link TickStats} instead of failing to load
 * the mod. Same soft-dependency style used for Easy NPC elsewhere in this codebase.
 *
 * <p>Everything is resolved once and cached. On any failure the bridge marks itself unavailable and
 * never retries — a HUD that reports lag must not create any, and a reflective lookup on every tick
 * would be exactly that.
 */
public final class SparkStats {

    private SparkStats() {}

    private static volatile boolean resolved;
    private static volatile boolean available;

    private static Object  tpsStat;        // DoubleStatistic<TicksPerSecond>
    private static Object  msptStat;       // GenericStatistic<DoubleAverageInfo, MillisPerTick>
    private static Method  tpsPoll;        // DoubleStatistic#poll(W) -> double
    private static Method  msptPoll;       // GenericStatistic#poll(W) -> DoubleAverageInfo
    private static Method  avgMean;        // DoubleAverageInfo#mean() -> double
    private static Method  avgMax;         // DoubleAverageInfo#max()  -> double
    private static Object  tpsWindow;      // TicksPerSecond.SECONDS_10
    private static Object  msptWindow;     // MillisPerTick.SECONDS_10

    /** Spark's reading. {@code mean}/{@code max} are MSPT in ms; {@code tps} is ticks/second. */
    public record Reading(double tps, double msptMean, double msptMax) {}

    /**
     * Binds to spark once. Safe to call repeatedly; only the first call does work.
     *
     * <p>⚠️ Must NOT run during mod construction — {@code SparkProvider.get()} throws until spark has
     * finished starting. Resolution is therefore lazy, on first read from the tick loop.
     */
    private static void resolve() {
        if (resolved) return;
        synchronized (SparkStats.class) {
            if (resolved) return;
            resolved = true;
            try {
                Class<?> provider = Class.forName("me.lucko.spark.api.SparkProvider");
                Object spark = provider.getMethod("get").invoke(null);
                if (spark == null) {
                    CoffeesAeroAuth.LOGGER.info("[SparkStats] spark present but not started yet — using our own counters.");
                    return;
                }

                tpsStat  = spark.getClass().getMethod("tps").invoke(spark);
                msptStat = spark.getClass().getMethod("mspt").invoke(spark);
                if (tpsStat == null || msptStat == null) return;

                Class<?> tpsWinCls  = Class.forName("me.lucko.spark.api.statistic.StatisticWindow$TicksPerSecond");
                Class<?> msptWinCls = Class.forName("me.lucko.spark.api.statistic.StatisticWindow$MillisPerTick");
                // SECONDS_10 on both: long enough to be stable, short enough that the bar still
                // reacts while a player is watching it. Both enums declare it.
                tpsWindow  = Enum.valueOf(tpsWinCls.asSubclass(Enum.class), "SECONDS_10");
                msptWindow = Enum.valueOf(msptWinCls.asSubclass(Enum.class), "SECONDS_10");

                Class<?> statWin = Class.forName("me.lucko.spark.api.statistic.StatisticWindow");
                tpsPoll  = Class.forName("me.lucko.spark.api.statistic.types.DoubleStatistic")
                    .getMethod("poll", Enum.class);
                msptPoll = Class.forName("me.lucko.spark.api.statistic.types.GenericStatistic")
                    .getMethod("poll", Enum.class);

                Class<?> avg = Class.forName("me.lucko.spark.api.statistic.misc.DoubleAverageInfo");
                avgMean = avg.getMethod("mean");
                avgMax  = avg.getMethod("max");

                // Prove the whole chain works NOW rather than discovering it on the tick loop.
                if (read() == null) {
                    available = false;
                    CoffeesAeroAuth.LOGGER.warn("[SparkStats] bound to spark but the first read failed — using our own counters.");
                    return;
                }
                available = true;
                CoffeesAeroAuth.LOGGER.info("[SparkStats] Using spark's TPS/MSPT for the HUD (10s window).");
            } catch (ClassNotFoundException e) {
                CoffeesAeroAuth.LOGGER.info("[SparkStats] spark not installed — HUD uses our own tick counters.");
            } catch (Throwable t) {
                CoffeesAeroAuth.LOGGER.warn("[SparkStats] could not bind to spark ({}) — using our own counters.", t.toString());
            }
        }
    }

    /** True once spark has been bound and has produced at least one reading. */
    public static boolean available() {
        resolve();
        return available;
    }

    /** Spark's current numbers, or {@code null} if unavailable — callers fall back to {@link TickStats}. */
    public static Reading read() {
        if (tpsPoll == null || msptPoll == null) return null;
        try {
            double tps = (double) tpsPoll.invoke(tpsStat, tpsWindow);
            Object info = msptPoll.invoke(msptStat, msptWindow);
            if (info == null) return null;
            double mean = (double) avgMean.invoke(info);
            double max  = (double) avgMax.invoke(info);
            // Spark returns -1 for a window it has not filled yet. Showing "-1 TPS" would look like a
            // fault; treat it as "no reading" so the caller keeps using its own figures until spark
            // has warmed up.
            if (tps < 0 || mean < 0) return null;
            return new Reading(tps, mean, max);
        } catch (Throwable t) {
            return null;
        }
    }
}
