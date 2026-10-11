package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The lag tooling against REAL stalls on a real server. Each kind runs in its own batch so the stalls never
 * overlap. The stall is a Thread.sleep, so the report must name it — that proves the samples belong to THIS
 * stall and not to whatever else happened in the last ten minutes (the 2026-10-11 false-alarm bug).
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class StallToolingGameTests {

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    @GameTest(template = "empty", batch = "stall_intick", timeoutTicks = 400)
    public static void anInTickStallIsReportedWithItsOwnSamples(GameTestHelper h) {
        LagAttributor.resetForTest();
        sleep(2300);                                   // this test body runs inside the server tick
        h.succeedWhen(() -> {
            String r = LagAttributor.lastReport;
            if (r == null) throw new GameTestAssertException("waiting for the report");
            if (!r.contains("INSIDE A TICK")) h.fail("wrong kind: " + r);
            if (!r.contains("busy inside")) h.fail("a fully sampled stall was not called work: " + r);
            if (!r.contains("Thread.sleep0") || !r.contains("StallToolingGameTests")) h.fail("the report does not name the stall's own frames: " + r);
        });
    }

    @GameTest(template = "empty", batch = "stall_between", timeoutTicks = 400)
    public static void aBetweenTicksStallIsSeenAndReported(GameTestHelper h) {
        LagAttributor.resetForTest();
        // A job queued on the main thread runs while it waits for the next tick — the old blind spot. Submitted
        // from ANOTHER thread: execute() called on the server thread runs the job immediately, inside the tick.
        var server = h.getLevel().getServer();
        new Thread(() -> server.execute(() -> sleep(2300)), "stall-test-submitter").start();
        h.succeedWhen(() -> {
            String r = LagAttributor.lastReport;
            if (r == null) throw new GameTestAssertException("waiting for the report");
            if (!r.contains("BETWEEN TICKS")) h.fail("a between-ticks stall was reported as: " + r);
            if (!r.contains("Thread.sleep0") || !r.contains("StallToolingGameTests")) h.fail("between-ticks samples missing: " + r);
        });
    }

    @GameTest(template = "empty", batch = "stall_monitor", timeoutTicks = 600)
    public static void aFreezeWarnsPlayersWhereTheOldRuleNeverDid(GameTestHelper h) {
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "lagtest");
        int before = LagMonitor.warnings;
        // The GameTest server ticks as fast as it can (hundreds of TPS, not 20), so a 10 s window of fast ticks
        // would out-vote a 6 s freeze. A 3 s window is fully covered by the freeze — what live TPS 20 gives too.
        var window = com.coffeesaerosmp.auth.config.AuthConfig.LAG_WARN_SUSTAIN_SECONDS;
        int saved = window.get();
        window.set(3);
        h.runAfterDelay(120, () -> {                   // let LagMonitor collect >5 s of history first
            LagMonitor.resetCooldownForTest();         // an earlier batch's slow moment may have used it up
            sleep(6000);
        });
        h.succeedWhen(() -> {
            if (LagMonitor.warnings == before) throw new GameTestAssertException("waiting for the freeze warning");
            window.set(saved);
            GameTestPlayers.remove(h, p);
        });
    }

    @GameTest(template = "empty", batch = "stall_pressure", timeoutTicks = 200)
    public static void garbageCollectionPausesAreRecorded(GameTestHelper h) {
        System.gc();
        h.succeedWhen(() -> {
            String s = RuntimePressure.summary(120_000);
            if (s.startsWith("GC: no pauses")) throw new GameTestAssertException("waiting for the GC notification: " + s);
        });
    }

    @GameTest(template = "empty", batch = "stall_census", timeoutTicks = 100)
    public static void theCensusNamesTheHotChunkWithCoordinates(GameTestHelper h) {
        BlockPos at = new BlockPos(3, 1, 3);
        for (int i = 0; i < 25; i++) {
            Pig pig = h.spawn(EntityType.PIG, at);
            pig.setNoAi(true);
        }
        BlockPos abs = h.absolutePos(at);
        int bx = (abs.getX() >> 4) * 16 + 8, bz = (abs.getZ() >> 4) * 16 + 8;
        String census = LagAttributor.censusNow(h.getLevel().getServer());
        if (!census.contains("/tp " + bx + " ~ " + bz)) h.fail("hot chunk not named with /tp coords " + bx + "," + bz + ": " + census);
        if (!census.contains("minecraft:pig")) h.fail("hot chunk contents missing: " + census);
        h.succeed();
    }

    private static StackTraceElement f(String cls, String m) {
        return new StackTraceElement(cls, m, null, -1);
    }

    @GameTest(template = "empty", batch = "stall_idle", timeoutTicks = 20)
    public static void anIdleWaitIsNotLagButAQueuedJobIs(GameTestHelper h) {
        StackTraceElement[] idlePoll = {           // the exact false alarm: waiting on the network selector
            f("sun.nio.ch.Net", "poll"), f("java.util.concurrent.locks.LockSupport", "parkNanos"),
            f("net.minecraft.server.MinecraftServer", "waitUntilNextTick"), f("net.minecraft.server.MinecraftServer", "runServer")};
        StackTraceElement[] idlePark = {
            f("jdk.internal.misc.Unsafe", "park"), f("net.minecraft.util.thread.BlockableEventLoop", "managedBlock"),
            f("net.minecraft.server.MinecraftServer", "waitUntilNextTick")};
        StackTraceElement[] queuedJob = {          // a real job running between ticks
            f("java.lang.Thread", "sleep0"), f("com.example.SomeMod", "heavyJob"),
            f("net.minecraft.server.MinecraftServer", "pollTaskInternal"), f("net.minecraft.server.MinecraftServer", "waitUntilNextTick")};
        StackTraceElement[] packet = {
            f("net.minecraft.server.level.ChunkMap$TrackedEntity", "updatePlayer"),
            f("net.minecraft.network.protocol.PacketUtils", "lambda$ensureRunningOnSameThread$0"),
            f("net.minecraft.server.MinecraftServer", "waitUntilNextTick")};
        if (!LagAttributor.idle(idlePoll)) h.fail("an idle network poll was counted as lag");
        if (!LagAttributor.idle(idlePark)) h.fail("an idle park was counted as lag");
        if (LagAttributor.idle(queuedJob)) h.fail("a queued mod job was dismissed as idle");
        if (LagAttributor.idle(packet)) h.fail("packet handling was dismissed as idle");
        h.succeed();
    }
}
