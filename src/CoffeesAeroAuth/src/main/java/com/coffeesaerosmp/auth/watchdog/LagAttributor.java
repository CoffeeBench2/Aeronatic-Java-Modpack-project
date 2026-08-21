package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers "WHERE is the lag coming from?" without anyone having to be awake.
 *
 * <h2>Why this exists alongside spark</h2>
 *
 * spark is installed and is a far better profiler than anything here. What spark cannot do is
 * <b>notice a spike at 02:30 and tell you about it</b> — it needs somebody to run
 * {@code /spark profiler}, wait, and read a web report. Every lag event on this server so far
 * happened while the owner was asleep, and by morning the evidence was gone. This is the unattended
 * half: detect, attribute, push to the watchdog channel, keep the receipts.
 *
 * <h2>How the attribution works</h2>
 *
 * A background thread samples the SERVER THREAD's stack, but only while a tick is actually running
 * long ({@code lagSampleAfterMs}). {@link #onTickPre} stamps the tick's start time; the sampler
 * compares that stamp against wall clock, so it knows a tick is overrunning <i>while it is still
 * overrunning</i> — which is the only moment the stack is worth looking at. Each sample is
 * attributed to a mod by walking down from the top frame to the first package we recognise.
 *
 * <p>Sampling only during slow ticks matters for cost. {@code Thread.getStackTrace()} on another
 * thread needs a safepoint and is not free; paying that 20×/second unconditionally would itself be a
 * lag source. Paid only while already over budget, the overhead is bounded and buys the one thing
 * that is otherwise unobtainable — an attributed picture of a spike nobody witnessed.
 *
 * <h2>The entity census is usually the actionable half</h2>
 *
 * On a modded server the stack frequently just says "create" or "minecraft" — true but useless.
 * What identifies the culprit is <i>which chunk holds 4,000 items</i>. The census runs inline on the
 * server thread, and ONLY once per report cooldown (default 10 min), because iterating every entity
 * in every dimension is exactly the kind of work that must not be done casually on a server whose
 * tick loop is already the bottleneck.
 *
 * <p>⚠ Reports go out at {@link Severity#MEDIUM} on purpose. HIGH and above also message every op
 * in-game via {@code server.execute}, and a lag report is diagnostics for the watchdog channel — not
 * something to interrupt play with. {@link LagMonitor} already handles telling players.
 */
public final class LagAttributor {

    private LagAttributor() {}

    // ── Package → mod attribution ─────────────────────────────────────────────
    //
    // Hand-maintained, because NeoForge offers no cheap class→mod lookup at runtime and scanning
    // jars to build one would cost more than it is worth. Anything unmatched falls back to its
    // first three package segments, which is still enough to name the culprit — an unrecognised
    // "org.embeddedt.modernfix" reads perfectly well in a report.
    private static final Map<String, String> MOD_PREFIXES = new LinkedHashMap<>();
    static {
        MOD_PREFIXES.put("com.coffeesaerosmp.",            "OUR MOD");
        MOD_PREFIXES.put("dev.ryanhcode.sable.",           "sable (physics)");
        MOD_PREFIXES.put("com.simibubi.create.",           "create");
        MOD_PREFIXES.put("com.railwayteam.railways.",      "create: railways");
        MOD_PREFIXES.put("rbasamoyai.createbigcannons.",   "create: big cannons");
        MOD_PREFIXES.put("com.seibel.distanthorizons.",    "distant horizons");
        MOD_PREFIXES.put("com.ishland.c2me.",              "c2me (chunks)");
        MOD_PREFIXES.put("net.caffeinemc.",                "lithium/sodium");
        MOD_PREFIXES.put("com.github.alexthe666.alexsmobs.", "alexsmobs");
        MOD_PREFIXES.put("com.github.alexthe666.citadel.", "citadel");
        MOD_PREFIXES.put("dev.ftb.mods.",                  "ftb");
        MOD_PREFIXES.put("de.maxhenkel.voicechat.",        "voicechat");
        MOD_PREFIXES.put("dev.latvian.mods.kubejs.",       "kubejs");
        MOD_PREFIXES.put("dev.latvian.mods.rhino.",        "rhino (kubejs)");
        MOD_PREFIXES.put("com.palm1.analogaudio.",         "analog audio");
        MOD_PREFIXES.put("net.blay09.mods.",               "balm/waystones");
        MOD_PREFIXES.put("vazkii.",                        "vazkii (patchouli/…)");
        MOD_PREFIXES.put("org.embeddedt.modernfix.",       "modernfix");
        MOD_PREFIXES.put("com.mojang.",                    "mojang (datafixer/brigadier)");
        MOD_PREFIXES.put("net.neoforged.",                 "neoforge");
        MOD_PREFIXES.put("net.minecraft.",                 "minecraft (vanilla)");
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** nanoTime the current tick began; 0 means "not inside a tick". Written by the server thread. */
    private static volatile long    tickStartNanos = 0L;
    private static volatile Thread  serverThread;
    private static volatile boolean running;
    private static Thread sampler;

    /** Guards every counter below — touched by the sampler thread and the server thread. */
    private static final Object LOCK = new Object();
    private static final Map<String, Integer> modSamples   = new HashMap<>();
    private static final Map<String, Integer> frameSamples = new HashMap<>();
    private static int    totalSamples;
    private static double worstTickMs;
    private static long   lastReportMs;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public static void start(MinecraftServer server) {
        if (running) return;
        running = true;
        resetCounters();
        sampler = new Thread(LagAttributor::sampleLoop, "AeroLagSampler");
        sampler.setDaemon(true);
        sampler.start();
        CoffeesAeroAuth.LOGGER.info("[LagAttributor] Sampling armed — attributes ticks over {}ms, "
            + "reports spikes over {}ms (cooldown {}min).",
            AuthConfig.LAG_SAMPLE_AFTER_MS.get(), AuthConfig.LAG_SPIKE_REPORT_MS.get(),
            AuthConfig.LAG_REPORT_COOLDOWN_MINUTES.get());
    }

    public static void stop() {
        running = false;
        if (sampler != null) sampler.interrupt();
    }

    // ── Tick hooks (server thread) ────────────────────────────────────────────

    /** Stamp the start of the tick. Two writes — this must never be why a tick is slow. */
    public static void onTickPre(MinecraftServer server) {
        serverThread   = Thread.currentThread();
        tickStartNanos = System.nanoTime();
    }

    /** Close the tick out and, if it was a spike, produce a report. */
    public static void onTickPost(MinecraftServer server) {
        long start = tickStartNanos;
        tickStartNanos = 0L;                       // out of tick: sampler stops sampling
        if (start == 0L || server == null) return;
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        synchronized (LOCK) {
            if (ms > worstTickMs) worstTickMs = ms;
        }

        try {
            if (!AuthConfig.LAG_ATTRIBUTION_ENABLED.get()) return;
            if (ms < AuthConfig.LAG_SPIKE_REPORT_MS.get()) return;

            long now = System.currentTimeMillis();
            long cooldown = AuthConfig.LAG_REPORT_COOLDOWN_MINUTES.get() * 60_000L;
            synchronized (LOCK) {
                if (now - lastReportMs < cooldown) return;
                if (totalSamples < 5) return;      // too little evidence to name anything
                lastReportMs = now;
            }

            // Census runs HERE, on the server thread, inline — see the class note. It is the only
            // way to read entities safely, and the cooldown is what makes it affordable.
            String census = censusNow(server);
            Report r = snapshotAndReset();
            // Posting touches the network; hand it off so it can never extend this tick.
            final int players = server.getPlayerList().getPlayerCount();
            Thread t = new Thread(() -> post(r, census, players), "AeroLagReport");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            CoffeesAeroAuth.LOGGER.debug("[LagAttributor] skipped: {}", e.toString());
        }
    }

    // ── Sampler thread ────────────────────────────────────────────────────────

    private static void sampleLoop() {
        while (running) {
            try {
                Thread.sleep(Math.max(10, AuthConfig.LAG_SAMPLE_INTERVAL_MS.get()));
                if (!AuthConfig.LAG_ATTRIBUTION_ENABLED.get()) continue;

                long start = tickStartNanos;
                if (start == 0L) continue;                       // not inside a tick
                Thread st = serverThread;
                if (st == null) continue;

                long runningMs = (System.nanoTime() - start) / 1_000_000L;
                if (runningMs < AuthConfig.LAG_SAMPLE_AFTER_MS.get()) continue;  // tick is fine

                StackTraceElement[] stack = st.getStackTrace();
                if (stack.length == 0) continue;
                record(stack);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // A sampler that dies stops guarding silently. Never let it.
                try { CoffeesAeroAuth.LOGGER.debug("[LagAttributor] sample failed: {}", t.toString()); }
                catch (Throwable ignored) { }
            }
        }
    }

    private static void record(StackTraceElement[] stack) {
        // Walk from the TOP (deepest call) down: the first recognised package is the most specific
        // answer available. Reading bottom-up would attribute everything to "minecraft" forever,
        // because the tick loop is always vanilla at the root.
        String mod = null;
        String frame = null;
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            String hit = classify(cls);
            if (hit != null) {
                mod = hit;
                frame = shortFrame(el);
                break;
            }
        }
        if (mod == null) {
            mod = "unattributed";
            frame = shortFrame(stack[0]);
        }
        synchronized (LOCK) {
            modSamples.merge(mod, 1, Integer::sum);
            frameSamples.merge(frame, 1, Integer::sum);
            totalSamples++;
        }
    }

    /** null = keep walking. Vanilla matches LAST so a mod frame above it always wins. */
    private static String classify(String cls) {
        for (Map.Entry<String, String> e : MOD_PREFIXES.entrySet()) {
            if (cls.startsWith(e.getKey())) return e.getValue();
        }
        // Unknown mod: first three package segments still identify it usefully.
        int a = cls.indexOf('.');
        if (a < 0) return null;
        int b = cls.indexOf('.', a + 1);
        if (b < 0) return cls.substring(0, a);
        int c = cls.indexOf('.', b + 1);
        return cls.substring(0, c < 0 ? b : c);
    }

    private static String shortFrame(StackTraceElement el) {
        String cls = el.getClassName();
        int i = cls.lastIndexOf('.');
        return (i < 0 ? cls : cls.substring(i + 1)) + "." + el.getMethodName();
    }

    // ── Entity census (server thread only) ────────────────────────────────────

    /**
     * Counts entities per type and finds the single busiest chunk. Bounded by
     * {@code lagCensusMaxEntities} so a runaway world can never turn diagnostics into an outage.
     */
    private static String censusNow(MinecraftServer server) {
        try {
            Map<String, Integer> byType  = new HashMap<>();
            Map<String, Integer> byChunk = new HashMap<>();
            int total = 0;
            int cap = AuthConfig.LAG_CENSUS_MAX_ENTITIES.get();

            outer:
            for (ServerLevel level : server.getAllLevels()) {
                String dim = level.dimension().location().toString();
                for (Entity e : level.getAllEntities()) {
                    String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                    byType.merge(type, 1, Integer::sum);
                    byChunk.merge(dim + " " + e.chunkPosition().x + "," + e.chunkPosition().z,
                                  1, Integer::sum);
                    if (++total >= cap) break outer;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(total).append(total >= cap ? "+ (capped)" : "").append(" entities. Top: ");
            sb.append(topN(byType, 4));
            String hottest = topN(byChunk, 1);
            if (!hottest.isEmpty()) sb.append(" | busiest chunk: ").append(hottest);
            return sb.toString();
        } catch (Throwable t) {
            return "census failed: " + t;
        }
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    private record Report(double worstMs, int samples, String mods, String frames) {}

    private static Report snapshotAndReset() {
        synchronized (LOCK) {
            Report r = new Report(worstTickMs, totalSamples, topN(modSamples, 5), topN(frameSamples, 5));
            resetCounters();
            return r;
        }
    }

    private static void resetCounters() {
        modSamples.clear();
        frameSamples.clear();
        totalSamples = 0;
        worstTickMs  = 0;
    }

    private static String topN(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed());
        StringBuilder sb = new StringBuilder();
        int total = m.values().stream().mapToInt(Integer::intValue).sum();
        for (int i = 0; i < Math.min(n, l.size()); i++) {
            if (i > 0) sb.append(", ");
            var e = l.get(i);
            sb.append(e.getKey()).append(' ');
            if (total > 0) sb.append(Math.round(100.0 * e.getValue() / total)).append('%');
            else sb.append('x').append(e.getValue());
        }
        return sb.toString();
    }

    private static void post(Report r, String census, int players) {
        try {
            CoffeesAeroAuth.LOGGER.warn("[LagAttributor] Spike {}ms | mods: {} | frames: {} | {}",
                String.format("%.0f", r.worstMs()), r.mods(), r.frames(), census);
            if (CoffeesAeroAuth.WATCHDOG == null) return;
            CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(Severity.MEDIUM,
                "Lag Spike — attributed",
                "Diagnostic only; no action taken",
                "Worst tick",  String.format("%.0f ms", r.worstMs()),
                "Samples",     String.valueOf(r.samples()),
                "By mod",      r.mods().isEmpty() ? "(none)" : r.mods(),
                "Hot methods", r.frames().isEmpty() ? "(none)" : r.frames(),
                "Entities",    census,
                "Players",     String.valueOf(players)));
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[LagAttributor] report failed", t);
        }
    }
}
