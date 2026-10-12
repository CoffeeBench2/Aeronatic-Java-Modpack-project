package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Measures the two things that freeze the WHOLE server and that nothing inside the tick loop can see
 * (2026-10-11 audit: 25–32 s stalls while the tick itself measured normal):
 * <ul>
 *   <li><b>Garbage-collection pauses</b> — every collection, via the JVM's GC notifications. G1's concurrent
 *       phases run alongside the game and are not pauses, so they are skipped.</li>
 *   <li><b>CPU throttling</b> — when the host's CPU quota for the container runs out within a period, every
 *       thread waits for the next one. Read from {@code cpu.stat} ({@link CgroupCpuStat}).</li>
 * </ul>
 * A daemon thread samples every {@value #SAMPLE_SECONDS} s. It logs a WARN at once when a 30-s window holds a
 * GC pause or throttling over a second, an INFO summary every 5 minutes, and {@link #summary} is attached to
 * every stall report. Nothing here touches the server thread.
 */
public final class RuntimePressure {

    static final int SAMPLE_SECONDS = 30;
    private static final long KEEP_MS = 15 * 60_000L;
    private static final String[] CPU_STAT = {
        "/sys/fs/cgroup/cpu.stat",                       // cgroup v2
        "/sys/fs/cgroup/cpu,cpuacct/cpu.stat",           // cgroup v1
        "/sys/fs/cgroup/cpu/cpu.stat",
    };

    private record Gc(long atMs, long pauseMs, String name) {}
    private record Throttle(long atMs, long throttledMs, long throttledPeriods, long periods) {}

    private static final Deque<Gc> GCS = new ArrayDeque<>();
    private static final Deque<Throttle> THROTTLES = new ArrayDeque<>();
    private static volatile boolean running;
    private static volatile Path cpuStat;
    private static CgroupCpuStat lastStat;
    /** CPU time per thread id at the last 5-minute summary, to report the spend since then. */
    private static final java.util.Map<Long, Long> lastThreadCpu = new java.util.HashMap<>();
    private static long lastSummaryMs, lastWarnMs, lastAlertMs;
    private static volatile long startedMs;
    /** Boot itself throttles hard (class loading, datapacks); alerting on it would cry wolf every restart. */
    static final long ALERT_GRACE_MS = 5 * 60_000L;
    /** GameTest hook: alerts raised (counted even when no WatchdogManager is up). */
    static volatile int alertsRaised;

    private RuntimePressure() {}

    /** Once per JVM: the GC listeners and the sampler thread outlive a server restart inside the same process. */
    public static synchronized void start() {
        if (running) return;
        running = true;
        startedMs = System.currentTimeMillis();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter em) {
                NotificationListener l = (n, h) -> onGc(n.getUserData());
                em.addNotificationListener(l, n -> GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType()), null);
            }
        }
        for (String p : CPU_STAT) {
            Path path = Path.of(p);
            if (Files.isReadable(path)) { cpuStat = path; break; }
        }
        Thread t = new Thread(RuntimePressure::loop, "AeroRuntimePressure");
        t.setDaemon(true);
        t.start();
        CoffeesAeroAuth.LOGGER.info("[RuntimePressure] watching GC pauses ({}) and CPU throttling ({}).",
            ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList(),
            cpuStat == null ? "no cgroup cpu.stat here" : cpuStat);
    }

    private static void onGc(Object userData) {
        try {
            GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) userData);
            String name = info.getGcName();
            if (name.toLowerCase(Locale.ROOT).contains("concurrent")) return;   // runs alongside the game
            record(new Gc(System.currentTimeMillis(), info.getGcInfo().getDuration(), name));
        } catch (Throwable ignored) { }
    }

    private static synchronized void record(Gc g) {
        GCS.addLast(g);
        prune(System.currentTimeMillis());
    }

    private static synchronized void prune(long now) {
        while (!GCS.isEmpty() && now - GCS.peekFirst().atMs() > KEEP_MS) GCS.pollFirst();
        while (!THROTTLES.isEmpty() && now - THROTTLES.peekFirst().atMs() > KEEP_MS) THROTTLES.pollFirst();
    }

    private static void loop() {
        while (running) {
            try {
                Thread.sleep(SAMPLE_SECONDS * 1000L);
                sampleNow();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                // never die, never spin: the sleep above is unconditional
            }
        }
    }

    /** One cgroup sample + the logging decisions. Package-private for the GameTest. */
    static void sampleNow() {
        long now = System.currentTimeMillis();
        Path p = cpuStat;
        if (p != null) {
            try {
                CgroupCpuStat s = CgroupCpuStat.parse(Files.readString(p));
                if (s != null) {
                    CgroupCpuStat d = s.since(lastStat);
                    if (lastStat != null) {
                        synchronized (RuntimePressure.class) {
                            THROTTLES.addLast(new Throttle(now, d.throttledUsec() / 1000, d.nrThrottled(), d.nrPeriods()));
                        }
                    }
                    lastStat = s;
                }
            } catch (Throwable ignored) { }
        }
        prune(now);
        boolean on;
        try { on = AuthConfig.PRESSURE_LOG_ENABLED.get(); } catch (Throwable t) { on = true; }
        if (!on) return;
        long win = SAMPLE_SECONDS * 1000L;
        if ((maxGcPause(win) >= 1000 || throttledMs(win) >= 1000) && now - lastWarnMs >= 5 * 60_000L) {
            lastWarnMs = now;
            CoffeesAeroAuth.LOGGER.warn("[RuntimePressure] whole-server pause pressure in the last {}s — {}", SAMPLE_SECONDS, summary(win));
        }
        maybeAlert(now);
        if (now - lastSummaryMs >= 5 * 60_000L) {
            lastSummaryMs = now;
            CoffeesAeroAuth.LOGGER.info("[RuntimePressure] last 5 min — {} · CPU by thread: {}", summary(5 * 60_000L), threadCpuSinceLast());
        }
    }

    /**
     * Pure: should a throttle alert fire now? Throttled ms over the last minute against the threshold, outside the
     * boot grace period and the cooldown. {@code thresholdMs <= 0} disables it.
     */
    static boolean shouldAlert(long throttledLastMinuteMs, long thresholdMs, long uptimeMs, long sinceLastAlertMs,
                               long cooldownMs) {
        if (thresholdMs <= 0) return false;
        if (uptimeMs < ALERT_GRACE_MS) return false;
        if (sinceLastAlertMs < cooldownMs) return false;
        return throttledLastMinuteMs >= thresholdMs;
    }

    private static void maybeAlert(long now) {
        long threshold, cooldownMs;
        try {
            threshold = AuthConfig.THROTTLE_ALERT_MS.get();
            cooldownMs = AuthConfig.THROTTLE_ALERT_COOLDOWN_MINUTES.get() * 60_000L;
        } catch (Throwable t) { return; }    // config not loaded yet
        long minute = throttledMs(60_000L);
        if (!shouldAlert(minute, threshold, now - startedMs, now - lastAlertMs, cooldownMs)) return;
        lastAlertMs = now;
        raiseAlert(minute, summary(60_000L));
    }

    /** Package-private so the GameTest can fire one without waiting for a real throttle. */
    static void raiseAlert(long throttledMinuteMs, String detail) {
        alertsRaised++;
        double pct = throttledMinuteMs / 600.0;    // % of the minute the host had the server stopped
        CoffeesAeroAuth.LOGGER.warn("[RuntimePressure] HOST CPU THROTTLING — the server was frozen by the host for {} ms "
            + "of the last minute ({}%). {}", throttledMinuteMs, String.format(Locale.ROOT, "%.1f", pct), detail);
        WatchdogManager w = CoffeesAeroAuth.WATCHDOG;
        if (w == null) return;
        WatchdogEvent ev = WatchdogEvent.of(Severity.HIGH, "Host CPU throttling",
            "None automatic. Ask Lagless about the CPU quota, or cut server-side load (Distant Horizons, chunk gen).",
            "Frozen by host", String.format(Locale.ROOT, "%,d ms in the last minute (%.1f%%)", throttledMinuteMs, pct),
            "Detail", detail,
            "Meaning", "The host's CPU quota ran out, so every thread paused at once. Players feel this as lag and ping spikes; it is not our code.");
        try { w.alert(ev); }
        catch (Throwable t) { CoffeesAeroAuth.LOGGER.warn("[RuntimePressure] throttle alert failed: {}", t.toString()); }
    }

    /** CPU each thread group spent since the previous call (top 6). Cheap: one MXBean pass every 5 min. */
    static String threadCpuSinceLast() {
        try {
            java.lang.management.ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            if (!mx.isThreadCpuTimeSupported()) return "not supported on this JVM";
            if (!mx.isThreadCpuTimeEnabled()) mx.setThreadCpuTimeEnabled(true);
            java.util.Map<String, Long> spent = new java.util.HashMap<>();
            java.util.Map<Long, Long> now = new java.util.HashMap<>();
            for (java.lang.management.ThreadInfo ti : mx.getThreadInfo(mx.getAllThreadIds())) {
                if (ti == null) continue;
                long cpu = mx.getThreadCpuTime(ti.getThreadId());
                if (cpu < 0) continue;
                now.put(ti.getThreadId(), cpu);
                long before = lastThreadCpu.getOrDefault(ti.getThreadId(), 0L);
                spent.merge(ThreadGroups.group(ti.getThreadName()), Math.max(0, cpu - before), Long::sum);
            }
            boolean first = lastThreadCpu.isEmpty();
            lastThreadCpu.clear();
            lastThreadCpu.putAll(now);
            String top = ThreadGroups.top(spent, 6);
            return first ? top + " [since boot]" : top;
        } catch (Throwable t) {
            return "unavailable (" + t.getClass().getSimpleName() + ")";
        }
    }

    static synchronized long maxGcPause(long windowMs) {
        long cut = System.currentTimeMillis() - windowMs, m = 0;
        for (Gc g : GCS) if (g.atMs() >= cut) m = Math.max(m, g.pauseMs());
        return m;
    }

    static synchronized long throttledMs(long windowMs) {
        long cut = System.currentTimeMillis() - windowMs, sum = 0;
        for (Throttle t : THROTTLES) if (t.atMs() >= cut) sum += t.throttledMs();
        return sum;
    }

    /** "GC: 4 pauses, 1,210 ms total, longest 820 ms · CPU throttled 3,400 ms in 12/600 periods". */
    public static synchronized String summary(long windowMs) {
        long cut = System.currentTimeMillis() - windowMs;
        int n = 0;
        long total = 0, max = 0;
        for (Gc g : GCS) {
            if (g.atMs() < cut) continue;
            n++; total += g.pauseMs(); max = Math.max(max, g.pauseMs());
        }
        String gc = n == 0 ? "GC: no pauses" : String.format(Locale.ROOT, "GC: %d pause%s, %,d ms total, longest %,d ms", n, n == 1 ? "" : "s", total, max);
        if (cpuStat == null) return gc + " · CPU throttling: not measurable on this host";
        long tms = 0, tp = 0, per = 0;
        for (Throttle t : THROTTLES) {
            if (t.atMs() < cut) continue;
            tms += t.throttledMs(); tp += t.throttledPeriods(); per += t.periods();
        }
        if (per == 0) return gc + " · CPU throttling: no sample yet";
        return gc + String.format(Locale.ROOT, " · CPU throttled %,d ms in %d/%d periods", tms, tp, per);
    }
}
