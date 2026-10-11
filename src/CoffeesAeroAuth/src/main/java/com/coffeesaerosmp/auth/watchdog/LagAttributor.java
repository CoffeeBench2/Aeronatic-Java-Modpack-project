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
 *
 * <h2>1.13.29 — per-episode, both sides of the tick (2026-10-11 audit)</h2>
 * <ul>
 *   <li>The old report's mod/frame breakdown was EVERY slow tick since the last report (up to 10 min), not the
 *       spike it named; 45% of reports had no real backlog at all. Samples now belong to ONE stall episode and
 *       are reset at each boundary, and only a stall of {@code stallReportMs} (default 2 s) is reported.</li>
 *   <li>80% of lost time was BETWEEN ticks (packets, chunk-load tasks, mod jobs queued on the main thread),
 *       where nothing sampled. The gap from tick Post to the next Pre is now an episode of its own.</li>
 *   <li>If the sampler itself froze (far fewer samples than the stall allows) the report says WHOLE-SERVER PAUSE
 *       and carries {@link RuntimePressure}'s GC / CPU-throttling figures.</li>
 *   <li>Cooldown is per kind, so an in-tick report never hides a between-ticks one.</li>
 * </ul>
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
    /** Samples where the server thread was merely WAITING for the next tick (not lag). */
    private static int    idleSamples;
    private static double worstTickMs;
    /** nanoTime the last tick ENDED; 0 = no tick yet. The gap until the next Pre is the between-ticks episode. */
    private static volatile long lastTickEndNanos = 0L;
    private static final long[] lastReportMs = new long[StallMath.Kind.values().length];
    /** The last report text, for the GameTests. */
    static volatile String lastReport;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public static void start(MinecraftServer server) {
        if (running) return;
        running = true;
        resetCounters();
        sampler = new Thread(LagAttributor::sampleLoop, "AeroLagSampler");
        sampler.setDaemon(true);
        sampler.start();
        CoffeesAeroAuth.LOGGER.info("[LagAttributor] Sampling armed — samples ticks over {}ms and between-tick gaps over "
            + "{}ms, reports stalls over {}ms (cooldown {}min per kind).",
            AuthConfig.LAG_SAMPLE_AFTER_MS.get(), AuthConfig.STALL_BETWEEN_SAMPLE_AFTER_MS.get(),
            AuthConfig.STALL_REPORT_MS.get(), AuthConfig.LAG_REPORT_COOLDOWN_MINUTES.get());
    }

    public static void stop() {
        running = false;
        if (sampler != null) sampler.interrupt();
    }

    // ── Tick hooks (server thread) ────────────────────────────────────────────

    /**
     * Stamp the start of the tick — and close the between-ticks episode that just ended. Its samples were taken
     * while the server thread was busy outside a tick; if the gap was long enough it is reported here, on the
     * server thread, with those samples only.
     */
    public static void onTickPre(MinecraftServer server) {
        long now = System.nanoTime();
        long lastEnd = lastTickEndNanos;
        serverThread   = Thread.currentThread();
        tickStartNanos = now;
        if (lastEnd == 0L || server == null) { resetEpisode(); return; }
        maybeReport(server, StallMath.Kind.BETWEEN_TICKS, (now - lastEnd) / 1_000_000.0);
    }

    /** Close the tick out and, if it was a stall, report it with ITS samples only. */
    public static void onTickPost(MinecraftServer server) {
        long start = tickStartNanos;
        tickStartNanos = 0L;                       // out of tick: sampler switches to between-ticks mode
        lastTickEndNanos = System.nanoTime();
        if (start == 0L || server == null) { resetEpisode(); return; }
        maybeReport(server, StallMath.Kind.IN_TICK, (lastTickEndNanos - start) / 1_000_000.0);
    }

    private static void maybeReport(MinecraftServer server, StallMath.Kind kind, double ms) {
        try {
            synchronized (LOCK) {
                if (ms > worstTickMs) worstTickMs = ms;
            }
            if (!AuthConfig.LAG_ATTRIBUTION_ENABLED.get() || ms < AuthConfig.STALL_REPORT_MS.get()) {
                resetEpisode();
                return;
            }
            synchronized (LOCK) {
                // Mostly waiting, not working: the thread was idle (e.g. nothing scheduled) — not lag.
                if (idleSamples > 0 && idleSamples * 4 >= (idleSamples + totalSamples) * 3) { resetCounters(); return; }
            }
            long now = System.currentTimeMillis();
            long cooldown = AuthConfig.LAG_REPORT_COOLDOWN_MINUTES.get() * 60_000L;
            synchronized (LOCK) {
                if (now - lastReportMs[kind.ordinal()] < cooldown) { resetCounters(); return; }
                lastReportMs[kind.ordinal()] = now;
            }
            // Census runs HERE, on the server thread, inline — see the class note. The cooldown makes it affordable.
            String census = censusNow(server);
            Report r = snapshotAndReset(ms);
            String pressure = RuntimePressure.summary((long) ms + 30_000L);
            String cause = StallMath.cause(kind, (long) ms, r.samples(),
                cfg(AuthConfig.LAG_SAMPLE_INTERVAL_MS, 50), kind == StallMath.Kind.IN_TICK
                    ? cfg(AuthConfig.LAG_SAMPLE_AFTER_MS, 100) : cfg(AuthConfig.STALL_BETWEEN_SAMPLE_AFTER_MS, 150));
            final int players = server.getPlayerList().getPlayerCount();
            // Posting touches the network; hand it off so it can never extend this tick.
            Thread t = new Thread(() -> post(kind, r, cause, pressure, census, players), "AeroLagReport");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            CoffeesAeroAuth.LOGGER.debug("[LagAttributor] skipped: {}", e.toString());
            resetEpisode();
        }
    }

    /** GameTest hook: clear the last report and every cooldown, so a test sees its own stall. */
    static void resetForTest() {
        lastReport = null;
        synchronized (LOCK) { java.util.Arrays.fill(lastReportMs, 0L); resetCounters(); }
    }

    private static void resetEpisode() {
        synchronized (LOCK) { resetCounters(); }
    }

    // ── Sampler thread ────────────────────────────────────────────────────────

    /** Floor between samples. A constant, so the loop can never spin even if config is unreadable. */
    private static final long SAMPLE_FLOOR_MS = 50L;

    /** At most one failure line per minute, however many failures there are. */
    private static final long ERROR_LOG_INTERVAL_MS = 60_000L;

    private static long lastErrorLogMs;
    private static long suppressedErrors;

    /**
     * Reads a config int, falling back to {@code def} if the spec is not loaded.
     *
     * <p>🔴 NeoForge throws {@code IllegalStateException: Cannot get config value before config is
     * loaded} whenever the SERVER spec is not currently bound — which includes the window around
     * shutdown, while this daemon thread is still alive. Every read in this loop has to survive that.
     */
    private static int cfg(net.neoforged.neoforge.common.ModConfigSpec.IntValue value, int def) {
        try { return value.get(); } catch (Throwable t) { return def; }
    }

    private static boolean cfg(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue value, boolean def) {
        try { return value.get(); } catch (Throwable t) { return def; }
    }

    /**
     * Samples the server thread while a tick is overrunning.
     *
     * <h2>🔴 2026-09-01 — this loop wrote 3,869,731 log lines in TWELVE SECONDS</h2>
     *
     * The sleep used to be {@code Thread.sleep(Math.max(10, CONFIG.get()))} — the config read was
     * <b>inside the sleep argument</b>. When that read threw (see {@link #cfg}), the sleep never
     * happened, so the catch logged and the loop went straight round again. An unthrottled spin,
     * rate-limited only by how fast the disk could accept log lines: measured at <b>~380,000 lines
     * per second</b>, rotating four log files in twelve seconds and saturating disk I/O on a server
     * whose tick loop was already short of headroom.
     *
     * <p>Three independent guards now, because any one of them alone would have prevented it:
     * <ol>
     *   <li>the sleep is a <b>constant</b> and happens first, so a config failure cannot skip it;</li>
     *   <li>every config read goes through {@link #cfg} and falls back to a default;</li>
     *   <li>the failure log is rate-limited to once a minute and reports how many it swallowed.</li>
     * </ol>
     *
     * <p>The lesson worth keeping: <b>never put a call that can throw inside the argument of the
     * only thing that throttles a loop.</b>
     */
    private static void sampleLoop() {
        while (running) {
            try {
                // (1) Constant sleep, first, unconditionally.
                Thread.sleep(SAMPLE_FLOOR_MS);

                // (2) Every read below tolerates an unloaded config.
                int interval = cfg(AuthConfig.LAG_SAMPLE_INTERVAL_MS, 50);
                if (interval > SAMPLE_FLOOR_MS) {
                    Thread.sleep(interval - SAMPLE_FLOOR_MS);
                }
                if (!cfg(AuthConfig.LAG_ATTRIBUTION_ENABLED, true)) continue;

                Thread st = serverThread;
                if (st == null) continue;
                long start = tickStartNanos;
                long nowNs = System.nanoTime();
                if (start != 0L) {                               // inside a tick
                    if ((nowNs - start) / 1_000_000L < cfg(AuthConfig.LAG_SAMPLE_AFTER_MS, 100)) continue;
                } else {                                         // between ticks: normally a <50 ms sleep
                    long end = lastTickEndNanos;
                    if (end == 0L || (nowNs - end) / 1_000_000L < cfg(AuthConfig.STALL_BETWEEN_SAMPLE_AFTER_MS, 150)) continue;
                }

                StackTraceElement[] stack = st.getStackTrace();
                if (stack.length == 0) continue;
                record(stack);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // (3) A sampler that dies stops guarding silently — but one that logs every failure
                // is worse than useless, as 2026-09-01 proved. Report at most once a minute.
                noteFailure(t);
            }
        }
    }

    private static void noteFailure(Throwable t) {
        try {
            long now = System.currentTimeMillis();
            suppressedErrors++;
            if (now - lastErrorLogMs < ERROR_LOG_INTERVAL_MS) return;
            lastErrorLogMs = now;
            CoffeesAeroAuth.LOGGER.warn(
                "[LagAttributor] sampler failing — {} failure(s) in the last minute. Latest: {}",
                suppressedErrors, t.toString());
            suppressedErrors = 0;
        } catch (Throwable ignored) { }
    }

    /**
     * True when the server thread is just waiting for the next tick: the first non-JDK frame is the tick
     * scheduler's wait ({@code waitUntilNextTick} / {@code managedBlock} / {@code waitForTasks}) — no queued task
     * runs on top of it. A real job queued between ticks always has its own (game or mod) frame above that wait.
     * Found by the GameTest: an idle test server produced a "BETWEEN TICKS" report of 100% {@code Net.poll}.
     */
    static boolean idle(StackTraceElement[] stack) {
        for (StackTraceElement el : stack) {
            String c = el.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("sun.")) continue;
            String m = el.getMethodName();
            return m.equals("waitUntilNextTick") || m.equals("managedBlock") || m.equals("waitForTasks")
                || m.equals("waitForRuns") || m.equals("parkUntil");
        }
        return false;
    }

    private static void record(StackTraceElement[] stack) {
        if (idle(stack)) {
            synchronized (LOCK) { idleSamples++; }
            return;
        }
        // Walk from the TOP (deepest call) down, SKIPPING the JDK: a blocking socket read is "sun.nio.ch.Net.poll"
        // at the top, which names nothing — the first game/mod frame under it is the code that made the call.
        // (1.13.29: a GameTest stall reported only "sun.nio.ch 100% / Net.poll", which was useless.)
        // Reading bottom-up would attribute everything to "minecraft" forever: the tick loop is vanilla at the root.
        String mod = null;
        String frame = null;
        String top = shortFrame(stack[0]);
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (isJdk(cls)) continue;
            String hit = classify(cls);
            if (hit != null) {
                mod = hit;
                String own = shortFrame(el);
                frame = own.equals(top) ? own : own + " > " + top;
                break;
            }
        }
        if (mod == null) {
            mod = "unattributed";
            frame = top;
        }
        synchronized (LOCK) {
            modSamples.merge(mod, 1, Integer::sum);
            frameSamples.merge(frame, 1, Integer::sum);
            totalSamples++;
        }
    }

    /** JDK and shared libraries: never the OWNER of a stall — keep walking to the code that called them. */
    private static final String[] NOT_AN_OWNER = {
        "java.", "jdk.", "sun.", "com.sun.", "com.mysql.", "com.zaxxer.", "io.netty.", "it.unimi.", "com.google.",
        "org.apache.", "org.slf4j.", "com.mojang.serialization.", "com.mojang.datafixers.", "org.objectweb.asm.",
    };

    static boolean isJdk(String cls) {
        for (String p : NOT_AN_OWNER) if (cls.startsWith(p)) return true;
        return false;
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
    static String censusNow(MinecraftServer server) {
        try {
            Map<String, Integer> byType = new HashMap<>();
            Map<ChunkKey, Integer> byChunk = new HashMap<>();
            Map<ChunkKey, Map<String, Integer>> chunkTypes = new HashMap<>();
            int total = 0;
            int cap = AuthConfig.LAG_CENSUS_MAX_ENTITIES.get();

            outer:
            for (ServerLevel level : server.getAllLevels()) {
                String dim = shortDim(level);
                for (Entity e : level.getAllEntities()) {
                    String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
                    byType.merge(type, 1, Integer::sum);
                    ChunkKey k = new ChunkKey(dim, e.chunkPosition().x, e.chunkPosition().z);
                    byChunk.merge(k, 1, Integer::sum);
                    chunkTypes.computeIfAbsent(k, x -> new HashMap<>()).merge(type, 1, Integer::sum);
                    if (++total >= cap) break outer;
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append(total).append(total >= cap ? "+ (capped)" : "").append(" entities. Top: ");
            sb.append(topN(byType, 4));

            // The area briefing. A bare chunk coordinate is not actionable — it has to be multiplied
            // by 16 by hand before anyone can fly to it. Report block coords you can paste into /tp,
            // what is actually sitting in the worst chunk, and who is standing near it.
            List<Map.Entry<ChunkKey, Integer>> hot = new ArrayList<>(byChunk.entrySet());
            hot.sort(Comparator.<Map.Entry<ChunkKey, Integer>>comparingInt(Map.Entry::getValue).reversed());

            if (!hot.isEmpty()) {
                Map.Entry<ChunkKey, Integer> worst = hot.get(0);
                ChunkKey k = worst.getKey();
                sb.append("\n• Hotspot: ").append(k.dim())
                  .append(" /tp ").append(k.blockX()).append(" ~ ").append(k.blockZ())
                  .append("  (chunk ").append(k.cx()).append(',').append(k.cz()).append(')')
                  .append(" — ").append(worst.getValue()).append(" entities");

                Map<String, Integer> inChunk = chunkTypes.get(k);
                if (inChunk != null && !inChunk.isEmpty()) {
                    sb.append("\n• Mostly: ").append(topN(inChunk, 3));
                }

                String near = nearestPlayer(server, k);
                if (near != null) sb.append("\n• Nearest player: ").append(near);
                String owner = ClaimOwner.of(server, k.dim(), k.cx(), k.cz());
                if (owner != null) sb.append("\n• Claimed by: ").append(owner);

                if (hot.size() > 1) {
                    sb.append("\n• Also hot: ");
                    for (int i = 1; i < Math.min(4, hot.size()); i++) {
                        ChunkKey o = hot.get(i).getKey();
                        if (i > 1) sb.append("; ");
                        sb.append(o.dim()).append(' ').append(o.blockX()).append(',').append(o.blockZ())
                          .append(" (").append(hot.get(i).getValue());
                        Map<String, Integer> types = chunkTypes.get(o);
                        if (types != null && !types.isEmpty()) sb.append(", mostly ").append(topN(types, 1));
                        String ow = ClaimOwner.of(server, o.dim(), o.cx(), o.cz());
                        if (ow != null) sb.append(", claim ").append(ow);
                        sb.append(')');
                    }
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "census failed: " + t;
        }
    }

    /** A chunk, kept as a value so the hotspot can be rendered as block coords rather than re-parsed. */
    private record ChunkKey(String dim, int cx, int cz) {
        int blockX() { return cx * 16 + 8; }
        int blockZ() { return cz * 16 + 8; }
    }

    /** {@code minecraft:the_nether} reads as {@code the_nether} — the namespace is never in doubt here. */
    private static String shortDim(ServerLevel level) {
        String s = level.dimension().location().toString();
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    /**
     * Nearest player to the hotspot, with distance. Usually the whole answer: a farm only lags while
     * somebody is loading it, so the player standing 30 blocks away is the one to talk to.
     */
    private static String nearestPlayer(MinecraftServer server, ChunkKey k) {
        try {
            net.minecraft.server.level.ServerPlayer best = null;
            double bestSq = Double.MAX_VALUE;
            for (ServerLevel level : server.getAllLevels()) {
                if (!shortDim(level).equals(k.dim())) continue;
                for (net.minecraft.server.level.ServerPlayer p : level.players()) {
                    double dx = p.getX() - k.blockX();
                    double dz = p.getZ() - k.blockZ();
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestSq) { bestSq = d2; best = p; }
                }
            }
            if (best == null) return null;
            return best.getGameProfile().getName() + " (" + Math.round(Math.sqrt(bestSq)) + "m away)";
        } catch (Throwable t) {
            return null;
        }
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    private record Report(double stallMs, int samples, String mods, String frames) {}

    private static Report snapshotAndReset(double stallMs) {
        synchronized (LOCK) {
            Report r = new Report(stallMs, totalSamples, topN(modSamples, 5), topN(frameSamples, 5));
            resetCounters();
            return r;
        }
    }

    private static void resetCounters() {
        modSamples.clear();
        frameSamples.clear();
        totalSamples = 0;
        idleSamples  = 0;
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

    private static void post(StallMath.Kind kind, Report r, String cause, String pressure, String census, int players) {
        try {
            String text = String.format(java.util.Locale.ROOT,
                "[LagAttributor] Stall %.0fms %s | cause: %s | mods: %s | frames: %s | %s | %s",
                r.stallMs(), kind.label.toUpperCase(java.util.Locale.ROOT), cause,
                r.mods().isEmpty() ? "(no samples)" : r.mods(), r.frames().isEmpty() ? "(no samples)" : r.frames(),
                pressure, census);
            lastReport = text;
            CoffeesAeroAuth.LOGGER.warn(text);
            if (CoffeesAeroAuth.WATCHDOG == null) return;
            CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(Severity.MEDIUM,
                "Server stall — " + kind.label,
                "Diagnostic only; no action taken",
                "Stall",       String.format(java.util.Locale.ROOT, "%.1f s", r.stallMs() / 1000.0),
                "Likely cause", cause,
                "Samples",     String.valueOf(r.samples()),
                "By mod",      r.mods().isEmpty() ? "(none)" : r.mods(),
                "Hot methods", r.frames().isEmpty() ? "(none)" : r.frames(),
                "GC / CPU",    pressure,
                "Entities",    census,
                "Players",     String.valueOf(players)));
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[LagAttributor] report failed", t);
        }
    }
}
