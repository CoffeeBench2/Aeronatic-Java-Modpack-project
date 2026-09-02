package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.server.MinecraftServer;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects a HUNG server thread and, if it does not recover, kills the JVM so the panel restarts it.
 *
 * <h2>Why this exists — a stall is not a crash</h2>
 *
 * Pterodactyl/Cybrancee already restarts the server automatically, but it only reacts to the
 * process <b>exiting</b>. On 2026-08-20 18:12 the tick loop stopped and never resumed: spark kept
 * logging "Timed out waiting for world statistics" once a minute for four and a half minutes while
 * the JVM sat there perfectly alive. The panel saw a healthy server. Nobody could play. Nothing
 * restarted. Then at 2026-08-21 00:00 the same thing happened during SHUTDOWN — the log is nothing
 * but {@code RejectedExecutionException: Server already shutting down} repeating every 60s, because
 * a shutdown had begun and then wedged.
 *
 * <p>Those two failures are what this class is for. It watches from OUTSIDE the tick loop, so a
 * hung tick loop cannot stop it from noticing.
 *
 * <h2>Why {@code halt()} and not {@code System.exit()}</h2>
 *
 * 🔴 {@link Runtime#exit} runs shutdown hooks, and <b>a hung shutdown is one of the two failure
 * modes being caught here</b> — calling exit() on a server that is already stuck saving would just
 * wedge in a different place and still never restart. {@link Runtime#halt} terminates immediately
 * without hooks. That is deliberately violent, and it is the correct trade: {@code SaveGuard} banks
 * player data every 60s and the world every 120s, so a halt costs at most ~1–2 minutes, whereas a
 * hang costs the entire session AND leaves the server down until somebody notices by hand.
 *
 * <h2>Shutdown is watched separately</h2>
 *
 * During shutdown ticks stop entirely and legitimately, so the tick-stall rule cannot apply — it
 * would fire on every clean stop. Once {@link #onServerStopping()} is called the watchdog switches
 * to a separate, longer deadline measured from the start of shutdown. That is what would have
 * caught the midnight hang.
 *
 * <h2>Alerting is best-effort; the dump is not</h2>
 *
 * ⚠ {@link WatchdogManager#alert} internally calls {@code server.execute(...)} to message ops
 * in-game — the exact call that throws {@code RejectedExecutionException} on a stopping server, and
 * one that silently queues forever on a hung one. So the thread dump is written to DISK FIRST and
 * unconditionally; the Discord/ops alert is attempted afterwards inside its own try/catch. If
 * alerting fails, the evidence still exists on disk.
 */
public final class StallWatchdog {

    private StallWatchdog() {}

    /** Exit code used when this watchdog halts the JVM. Distinctive, so the panel log identifies it. */
    public static final int HALT_EXIT_CODE = 70;

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private static volatile long    lastTickMs   = 0L;
    /** Only true once the server has ticked at least once — never judge a server that never started. */
    private static volatile boolean armed        = false;
    private static volatile boolean shuttingDown = false;
    private static volatile long    shutdownAtMs = 0L;
    private static volatile boolean running      = false;
    private static volatile MinecraftServer server;

    private static Thread   thread;
    private static Path     dumpDir;
    /** True once the current stall has been reported, so one stall produces one alert, not sixty. */
    private static boolean  reported = false;

    // ── Tick heartbeat ────────────────────────────────────────────────────────

    /**
     * Heartbeat from the server thread. Deliberately the cheapest possible body — a single volatile
     * write. This runs every tick and must never be the reason a tick is slow.
     */
    public static void onServerTick(MinecraftServer s) {
        lastTickMs = System.currentTimeMillis();
        if (!armed) {
            server = s;
            armed  = true;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Start the watcher. Called once from {@code onServerStarting}. */
    public static void start(MinecraftServer s, Path dataDir) {
        if (running) return;
        server  = s;
        dumpDir = dataDir == null ? null : dataDir.resolve("stalls");
        lastTickMs   = System.currentTimeMillis();
        armed        = false;
        shuttingDown = false;
        reported     = false;
        running      = true;

        thread = new Thread(StallWatchdog::loop, "AeroStallWatchdog");
        // Daemon: this thread must never be the reason the JVM stays alive.
        thread.setDaemon(true);
        // Above normal, so a saturated box still schedules the one thread whose job is to notice.
        thread.setPriority(Thread.NORM_PRIORITY + 2);
        thread.start();
        CoffeesAeroAuth.LOGGER.info("[StallWatchdog] Watching. warn={}s kill={}s shutdownKill={}s",
            AuthConfig.STALL_WARN_SECONDS.get(), AuthConfig.STALL_KILL_SECONDS.get(),
            AuthConfig.STALL_SHUTDOWN_KILL_SECONDS.get());
    }

    /** Switch to shutdown supervision. Called from {@code onServerStopping}. */
    public static void onServerStopping() {
        shuttingDown = true;
        shutdownAtMs = System.currentTimeMillis();
        reported     = false;
        CoffeesAeroAuth.LOGGER.info("[StallWatchdog] Shutdown started — supervising for {}s.",
            AuthConfig.STALL_SHUTDOWN_KILL_SECONDS.get());
    }

    /** Stop watching entirely (clean shutdown completed). */
    public static void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    // ── The watcher ───────────────────────────────────────────────────────────

    private static void loop() {
        while (running) {
            try {
                Thread.sleep(1000L);
                // 🔴 Tolerate an unloaded config. NeoForge throws "Cannot get config value before
                // config is loaded" whenever the SERVER spec is unbound — including the window around
                // shutdown, while this daemon thread is still running. That threw 4,206 times in the
                // 2026-09-01 logs, and every one of those seconds the watchdog was NOT guarding.
                // The 1s sleep above means this never span, unlike LagAttributor — but a watchdog
                // that is silently blind is still a broken watchdog.
                boolean enabled;
                try { enabled = AuthConfig.STALL_WATCHDOG_ENABLED.get(); }
                catch (Throwable t) { enabled = true; }          // fail SAFE: keep guarding
                if (!enabled) { reported = false; continue; }
                long now = System.currentTimeMillis();

                if (shuttingDown) {
                    checkShutdown(now);
                } else if (armed) {
                    checkTickStall(now);
                }
            } catch (InterruptedException ie) {
                return;                                  // stop() was called
            } catch (Throwable t) {
                // The watchdog must never die of its own accident, or it silently stops guarding.
                try {
                    CoffeesAeroAuth.LOGGER.warn("[StallWatchdog] check failed", t);
                } catch (Throwable ignored) { /* logging itself may be broken */ }
            }
        }
    }

    private static void checkTickStall(long now) {
        long stalledMs = now - lastTickMs;
        long warnMs    = AuthConfig.STALL_WARN_SECONDS.get() * 1000L;
        long killMs    = AuthConfig.STALL_KILL_SECONDS.get() * 1000L;

        if (stalledMs < warnMs) {
            if (reported) {
                // It came back on its own. Say so — a stall that self-recovers is the single most
                // useful thing to know about, because it names a slow operation without downtime.
                CoffeesAeroAuth.LOGGER.warn("[StallWatchdog] Server thread RECOVERED after {}s.",
                    stalledMs / 1000L);
                reported = false;
            }
            return;
        }

        if (!reported) {
            reported = true;
            Path written = writeDump("tick-stall", stalledMs);
            CoffeesAeroAuth.LOGGER.error(
                "[StallWatchdog] SERVER THREAD STALLED for {}s — dump: {}", stalledMs / 1000L,
                written == null ? "(write failed)" : written.getFileName());
            alert(Severity.CRITICAL, "Server Thread Stalled",
                killMs > 0 ? "Killing the JVM at " + (killMs / 1000L) + "s so the panel restarts it"
                           : "Alert only (stallKillSeconds = 0)",
                stalledMs, written);
        }

        if (killMs > 0 && stalledMs >= killMs) {
            writeDump("tick-stall-FINAL", stalledMs);
            halt("server thread stalled " + (stalledMs / 1000L) + "s");
        }
    }

    private static void checkShutdown(long now) {
        long killMs = AuthConfig.STALL_SHUTDOWN_KILL_SECONDS.get() * 1000L;
        if (killMs <= 0) return;
        long elapsed = now - shutdownAtMs;
        if (elapsed < killMs) return;

        writeDump("shutdown-hang", elapsed);
        halt("shutdown hung for " + (elapsed / 1000L) + "s");
    }

    // ── Halt ──────────────────────────────────────────────────────────────────

    private static void halt(String why) {
        try {
            CoffeesAeroAuth.LOGGER.error(
                "[StallWatchdog] HALTING JVM (exit {}): {}. Shutdown hooks are SKIPPED on purpose — "
                + "a hung shutdown is one of the failures this exists to break out of. SaveGuard's "
                + "last periodic bank is the recovery point.", HALT_EXIT_CODE, why);
        } catch (Throwable ignored) { /* keep going: halting matters more than logging it */ }
        // halt(), never exit(): exit() runs shutdown hooks and would wedge all over again.
        Runtime.getRuntime().halt(HALT_EXIT_CODE);
    }

    // ── Evidence ──────────────────────────────────────────────────────────────

    /**
     * Writes a full thread dump. Returns the path, or null if it could not be written.
     *
     * <p>The server thread's stack is printed FIRST and in full — it is the one that names the
     * blocking call, and it is the reason this file exists. Deadlock detection runs too, since a
     * mutual lock between the tick loop and an async worker is a prime suspect for a total freeze.
     */
    private static Path writeDump(String reason, long stalledMs) {
        try {
            ThreadMXBean mx = ManagementFactory.getThreadMXBean();
            StringBuilder sb = new StringBuilder(64 * 1024);
            sb.append("Coffees Aero SMP — stall dump\n")
              .append("reason      : ").append(reason).append('\n')
              .append("stalled for : ").append(stalledMs / 1000L).append("s\n")
              .append("utc         : ").append(Instant.now()).append('\n')
              .append("players     : ").append(playerCount()).append('\n')
              .append("heap used   : ").append(usedHeapMb()).append(" MB (of -Xmx)\n")
              .append("process RSS : ").append(rssText()).append('\n')
              .append("              ^ compare against the CONTAINER limit, not -Xmx. If this is\n")
              .append("                near the limit, the kernel OOM-killer is the likely cause of\n")
              .append("                any silent death — it leaves no OutOfMemoryError behind.\n\n");

            long[] deadlocked = null;
            try {
                deadlocked = mx.findDeadlockedThreads();
            } catch (Throwable ignored) { /* not supported everywhere */ }
            if (deadlocked != null && deadlocked.length > 0) {
                sb.append("!!! DEADLOCK DETECTED — ").append(deadlocked.length)
                  .append(" thread(s) !!!\n\n");
            }

            ThreadInfo[] all = mx.dumpAllThreads(true, true);

            sb.append("========== SERVER THREAD (the one that matters) ==========\n");
            boolean foundServer = false;
            for (ThreadInfo ti : all) {
                if (ti != null && "Server thread".equals(ti.getThreadName())) {
                    sb.append(ti);
                    foundServer = true;
                }
            }
            if (!foundServer) sb.append("(no thread named 'Server thread' — it may already be gone)\n");

            sb.append("\n========== ALL THREADS ==========\n");
            for (ThreadInfo ti : all) {
                if (ti != null) sb.append(ti).append('\n');
            }

            if (dumpDir == null) {
                CoffeesAeroAuth.LOGGER.error("[StallWatchdog] no dump dir; dump follows:\n{}", sb);
                return null;
            }
            Files.createDirectories(dumpDir);
            Path out = dumpDir.resolve("stall-" + STAMP.format(Instant.now()) + "-" + reason + ".txt");
            Files.writeString(out, sb.toString());
            return out;
        } catch (Throwable t) {
            try {
                CoffeesAeroAuth.LOGGER.error("[StallWatchdog] could not write dump", t);
            } catch (Throwable ignored) { }
            return null;
        }
    }

    /**
     * Best-effort staff alert. Never allowed to throw.
     *
     * <p>⚠ {@code WatchdogManager.alert} messages ops via {@code server.execute(...)}. On a stopping
     * server that throws {@code RejectedExecutionException}; on a hung one the task simply queues
     * and is never run. Either way the Discord webhook still goes out, and the dump on disk is
     * already safe by the time this is called.
     */
    private static void alert(Severity sev, String title, String action, long stalledMs, Path dump) {
        try {
            if (CoffeesAeroAuth.WATCHDOG == null) return;
            CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(sev, title, action,
                "Stalled for", (stalledMs / 1000L) + "s",
                "Players online", String.valueOf(playerCount()),
                "Heap used", usedHeapMb() + " MB",
                "Process RSS", rssText(),
                "Thread dump", dump == null ? "write failed" : dump.getFileName().toString(),
                "Top frame", topServerFrame()));
        } catch (Throwable t) {
            try {
                CoffeesAeroAuth.LOGGER.warn("[StallWatchdog] alert failed (dump is still on disk)", t);
            } catch (Throwable ignored) { }
        }
    }

    /** The single most useful line for a Discord alert: what the tick loop is actually sitting in. */
    private static String topServerFrame() {
        try {
            for (ThreadInfo ti : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
                if (ti != null && "Server thread".equals(ti.getThreadName())) {
                    StackTraceElement[] st = ti.getStackTrace();
                    if (st.length == 0) return "(empty stack)";
                    List<String> frames = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, st.length); i++) frames.add(st[i].toString());
                    return ti.getThreadState() + " @ " + String.join(" <- ", frames);
                }
            }
        } catch (Throwable ignored) { }
        return "unknown";
    }

    private static int playerCount() {
        try {
            MinecraftServer s = server;
            // getPlayerCount() reads a cached int, so this does not need the server thread.
            return s == null ? -1 : s.getPlayerList().getPlayerCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long usedHeapMb() {
        Runtime r = Runtime.getRuntime();
        return (r.totalMemory() - r.freeMemory()) / 1048576L;
    }

    /**
     * Total resident memory of the whole PROCESS, in MB, or -1 if unavailable.
     *
     * <p>🔑 This is the number that decides whether the container OOM-killer fires, and it is NOT
     * the heap. {@code Runtime} only ever reports heap; the process also carries metaspace for ~250
     * mods, code cache, thread stacks, Netty direct buffers, and — the big ones here — <b>Rapier's
     * native physics allocations and Distant Horizons' SQLite/LOD caches</b>. A JVM pinned at
     * {@code -Xmx12G} with {@code AlwaysPreTouch} on a 24 GB container starts around 12 GB resident
     * and has roughly 12 GB of headroom for all of that.
     *
     * <p>When the kernel kills for exceeding the container limit it sends SIGKILL: no
     * {@code OutOfMemoryError}, no crash report, no log line — the log simply stops. That is
     * indistinguishable in the log from every other silent death, which is exactly why this is
     * recorded on every dump. If VmRSS is close to the container limit here, memory is the answer.
     */
    private static long rssMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]) / 1024L;      // reported in kB
                }
            }
        } catch (Throwable ignored) {
            // Not Linux, or /proc unavailable. Fine — heap figures still get reported.
        }
        return -1L;
    }

    private static String rssText() {
        long rss = rssMb();
        return rss < 0 ? "unavailable" : rss + " MB resident (whole process, not just heap)";
    }
}
