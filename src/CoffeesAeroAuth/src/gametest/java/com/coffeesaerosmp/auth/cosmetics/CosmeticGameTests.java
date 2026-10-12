package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import com.coffeesaerosmp.auth.store.PreviewSelections;
import com.coffeesaerosmp.auth.store.StoreState;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cosmetics on a real server: trails emit only while moving, only for players allowed to wear them, never while
 * paused, and a viewer who hid others gets nothing. Unlinked mock players use the ops-preview session path.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class CosmeticGameTests {

    private static final Selection CLOUDWALK = new Selection(null, false, null, null, "trail:cloudwalk", null, null);

    private static ServerPlayer player(GameTestHelper h, boolean op) {
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(3, 1, 3), "cosm");
        if (op) CosmeticEngine.TEST_OPS.add(p.getUUID());     // GameTest ops get permission 0 — see TEST_OPS
        PreviewSelections.put(p.getUUID(), CLOUDWALK);
        CosmeticEngine.resetLoadForTest();
        CosmeticEngine.ignoreServerLoadForTest = true;    // other batches' deliberate stalls must not pause us
        CosmeticEngine.refresh(h.getLevel().getServer());
        return p;
    }

    private static void cleanup(GameTestHelper h, ServerPlayer p) {
        CosmeticEngine.TEST_OPS.remove(p.getUUID());
        CosmeticEngine.forget(p.getUUID());
        GameTestPlayers.remove(h, p);
    }

    /** Walk the player 0.6 blocks east every 2 ticks for {@code ticks}. Straight line, never back and forth. */
    private static void walk(GameTestHelper h, ServerPlayer p, int ticks, AtomicLong before) {
        before.set(CosmeticEngine.packetsSent);
        for (int t = 0; t < ticks; t += 2) {
            final int step = t;
            h.runAfterDelay(t, () -> {
                Vec3 at = p.position().add(0.6, 0, 0);   // straight line: alternating back and forth can land on the same spot every emit tick
                p.teleportTo(h.getLevel(), at.x, at.y, at.z, 0f, 0f);
            });
        }
    }

    @GameTest(template = "empty", batch = "cosmetics_trail", timeoutTicks = 300)
    public static void anOpTrailEmitsOnlyWhileMovingAndNeverWhilePaused(GameTestHelper h) {
        // One sequential scenario on purpose: the pause is global state, so a separate pause test running in an
        // overlapping batch made this test flaky.
        GameTestPlayers.floor(h);
        ServerPlayer p = player(h, true);
        if (!"trail:cloudwalk".equals(StoreState.selectionFor(p.getUUID()).trail()))
            h.fail("op preview did not keep the trail: " + StoreState.selectionFor(p.getUUID()));
        AtomicLong moving = new AtomicLong();
        walk(h, p, 40, moving);                                   // ticks 0-40: moving
        AtomicLong still = new AtomicLong();
        h.runAfterDelay(50, () -> {                               // settle: the last step (tick 38) may emit up to tick ~44
            if (CosmeticEngine.packetsSent - moving.get() <= 0)
                h.fail("a moving op with a trail sent no particles | " + CosmeticEngine.debugState() + " | me=" + p.getUUID()
                    + " at " + p.position() + " sel=" + StoreState.selectionFor(p.getUUID()).trail());
            still.set(CosmeticEngine.packetsSent);
        });
        AtomicLong paused = new AtomicLong();
        h.runAfterDelay(100, () -> {                              // ticks 41-100: standing still
            long sent = CosmeticEngine.packetsSent - still.get();
            if (sent > 0) h.fail("a player standing still sent " + sent + " trail packets");
            CosmeticEngine.forcePause(true);
            paused.set(CosmeticEngine.packetsSent);
        });
        for (int t = 102; t < 150; t += 2) {                      // ticks 102-150: moving while paused
            final int step = t;
            h.runAfterDelay(t, () -> {
                Vec3 at = p.position().add(0.6, 0, 0);   // straight line: alternating back and forth can land on the same spot every emit tick
                p.teleportTo(h.getLevel(), at.x, at.y, at.z, 0f, 0f);
            });
        }
        h.runAfterDelay(152, () -> {
            CosmeticEngine.forcePause(false);
            long sent = CosmeticEngine.packetsSent - paused.get();
            if (sent > 0) h.fail("particles sent while paused: " + sent);
            cleanup(h, p);
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "cosmetics_nonop", timeoutTicks = 120)
    public static void aPlayerWithoutTheTrailEmitsNothing(GameTestHelper h) {
        GameTestPlayers.floor(h);
        ServerPlayer p = player(h, false);          // not an op, no rank: the preview trail must be stripped
        if (StoreState.selectionFor(p.getUUID()).trail() != null) h.fail("a non-op kept a preview trail");
        AtomicLong before = new AtomicLong();
        walk(h, p, 40, before);
        h.runAfterDelay(60, () -> {
            if (CosmeticEngine.packetsSent != before.get()) h.fail("particles sent for a player who may not wear a trail");
            cleanup(h, p);
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "cosmetics_hide", timeoutTicks = 120)
    public static void aViewerWhoHidOthersReceivesNothing(GameTestHelper h) {
        GameTestPlayers.floor(h);
        ServerPlayer p = player(h, true);
        CosmeticEngine.toggleHideOthers(p.getUUID());   // the only viewer opts out
        AtomicLong before = new AtomicLong();
        walk(h, p, 40, before);
        h.runAfterDelay(60, () -> {
            if (CosmeticEngine.packetsSent != before.get()) h.fail("packets sent to a viewer who hid cosmetics");
            cleanup(h, p);
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "cosmetics_options", timeoutTicks = 20)
    public static void everyCatalogParticleResolves(GameTestHelper h) {
        for (var c : com.coffeesaerosmp.auth.store.CosmeticCatalog.all()) {
            if (CosmeticEngine.options(c) == null) h.fail(c.id() + " uses a particle this server cannot draw");
        }
        h.succeed();
    }
}
