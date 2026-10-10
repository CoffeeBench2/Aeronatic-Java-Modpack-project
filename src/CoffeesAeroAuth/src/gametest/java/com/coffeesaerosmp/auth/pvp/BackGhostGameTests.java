package com.coffeesaerosmp.auth.pvp;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.AuthManager;
import com.coffeesaerosmp.auth.auth.AuthState;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.world.InteractionHand;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * /back countdown + ghost, on a real server with a real (mock-connection) SURVIVAL ServerPlayer.
 * GameTestHelper's own mock player is hard-wired creative â€” creative players already take no damage
 * and are never targeted, which would make every assertion here pass for the wrong reason.
 * Every protection is checked against a control (the same action without the ghost).
 *
 * <p>The GameTest server builds its test area millions of blocks out, inside Sable's plot range, so
 * run-gametest's config turns plotGuardBlockTeleports OFF — otherwise PlotGuard (correctly) refuses
 * every teleport these tests make.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class BackGhostGameTests {

    /** ServerPlayer spawn protection is 60 ticks; damage before then proves nothing. */
    private static final int AFTER_SPAWN_PROTECTION = 80;

    // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    @SuppressWarnings("unchecked")
    private static ServerPlayer survivalPlayer(GameTestHelper h, BlockPos rel) {
        ServerLevel level = h.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), "ghosttest" + (int) (Math.random() * 1000)), false);
        ServerPlayer p = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection conn = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(conn);
        level.getServer().getPlayerList().placeNewPlayer(conn, p, cookie);
        // Auth freezes a fresh join until the gate cookie arrives (no gate here). Mark it logged in — by
        // reflection, in the test only, so no auth bypass exists in the shipped mod.
        try {
            AuthManager am = CoffeesAeroAuth.AUTH_MANAGER;
            Field states = AuthManager.class.getDeclaredField("authStates");
            states.setAccessible(true);
            ((Map<UUID, AuthState>) states.get(am)).put(p.getUUID(), AuthState.AUTHENTICATED);
            Field frozen = AuthManager.class.getDeclaredField("frozenPos");
            frozen.setAccessible(true);
            ((Map<UUID, double[]>) frozen.get(am)).remove(p.getUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AuthManager fields moved — update the test", e);
        }
        p.setGameMode(GameType.SURVIVAL);
        Vec3 abs = Vec3.atBottomCenterOf(h.absolutePos(rel));
        p.teleportTo(level, abs.x, abs.y, abs.z, 0f, 0f);
        return p;
    }

    private static void leave(GameTestHelper h, ServerPlayer p) {
        GhostState.end(p, null);
        h.getLevel().getServer().getPlayerList().remove(p);
    }

    private static CombatGuard.DeathPoint deathAt(GameTestHelper h, BlockPos rel) {
        Vec3 abs = Vec3.atBottomCenterOf(h.absolutePos(rel));
        return new CombatGuard.DeathPoint(h.getLevel().dimension(), abs.x, abs.y, abs.z, System.currentTimeMillis());
    }

    private static boolean near(ServerPlayer p, CombatGuard.DeathPoint d) {
        return p.position().distanceToSqr(d.x(), d.y(), d.z()) < 0.25;
    }

    // â”€â”€ tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void backWaitsForTheCountdownThenLandsAGhost(GameTestHelper h) {
        floor(h);
        ServerPlayer p = survivalPlayer(h, new BlockPos(1, 1, 1));
        CombatGuard.DeathPoint death = deathAt(h, new BlockPos(5, 1, 5));
        BackCountdown.start(p, death);

        h.runAfterDelay(80, () -> {                       // 4 s in: still waiting, still solid
            if (near(p, death)) h.fail("teleported before the 5 s countdown ended");
            if (GhostState.isGhost(p.getUUID())) h.fail("ghost before arrival");
            if (!BackCountdown.isRunning(p.getUUID())) h.fail("countdown stopped early");
        });
        h.runAfterDelay(130, () -> {                      // 6.5 s in: arrived as a ghost
            if (!near(p, death)) h.fail("not at the death spot after the countdown: " + p.position());
            if (!GhostState.isGhost(p.getUUID())) h.fail("arrived without the ghost");
            if (BackCountdown.isRunning(p.getUUID())) h.fail("countdown still running after arrival");
            leave(h, p);
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void movingCancelsTheCountdown(GameTestHelper h) {
        floor(h);
        ServerPlayer p = survivalPlayer(h, new BlockPos(1, 1, 1));
        CombatGuard.DeathPoint death = deathAt(h, new BlockPos(5, 1, 5));
        BackCountdown.start(p, death);

        h.runAfterDelay(20, () -> {
            Vec3 moved = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 4)));
            p.teleportTo(h.getLevel(), moved.x, moved.y, moved.z, 0f, 0f);
        });
        h.runAfterDelay(140, () -> {
            if (near(p, death)) h.fail("teleported even though the player moved");
            if (GhostState.isGhost(p.getUUID())) h.fail("became a ghost after a cancelled /back");
            if (BackCountdown.isRunning(p.getUUID())) h.fail("countdown not cancelled");
            leave(h, p);
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void ghostTakesNoDamageAndMobsCannotTargetIt(GameTestHelper h) {
        floor(h);
        ServerPlayer p = survivalPlayer(h, new BlockPos(1, 1, 1));
        Zombie zombie = h.spawn(EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        zombie.setNoAi(true);   // we drive setTarget ourselves; AI would re-target on its own

        h.runAfterDelay(AFTER_SPAWN_PROTECTION, () -> {
            GhostState.start(p, 60, p.position());
            float before = p.getHealth();
            p.hurt(p.damageSources().lava(), 4f);
            p.hurt(p.damageSources().playerAttack(p), 4f);
            if (p.getHealth() != before) h.fail("ghost took damage: " + before + " -> " + p.getHealth());
            zombie.setTarget(p);
            if (zombie.getTarget() == p) h.fail("zombie targeted a ghost");

            // Control: the same player, no longer a ghost.
            GhostState.end(p, null);
            p.hurt(p.damageSources().lava(), 4f);
            if (p.getHealth() >= before) h.fail("control: non-ghost took no lava damage (test is not measuring anything)");
            zombie.setTarget(p);
            if (zombie.getTarget() != p) h.fail("control: zombie could not target a non-ghost");
            leave(h, p);
            h.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void breakingABlockEndsTheGhost(GameTestHelper h) {
        floor(h);
        ServerPlayer p = survivalPlayer(h, new BlockPos(1, 1, 1));
        GhostState.start(p, 60, p.position());
        if (!GhostState.isGhost(p.getUUID())) h.fail("ghost did not start");
        BlockPos stone = h.absolutePos(new BlockPos(3, 0, 3));
        NeoForge.EVENT_BUS.post(new BlockEvent.BreakEvent(h.getLevel(), stone, h.getLevel().getBlockState(stone), p));
        if (GhostState.isGhost(p.getUUID())) h.fail("breaking a block did not end the ghost");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void usingAnEntityEndsTheGhost(GameTestHelper h) {
        floor(h);
        ServerPlayer p = survivalPlayer(h, new BlockPos(1, 1, 1));
        Pig pig = h.spawn(EntityType.PIG, new BlockPos(3, 1, 3));
        GhostState.start(p, 60, p.position());
        NeoForge.EVENT_BUS.post(new PlayerInteractEvent.EntityInteract(p, InteractionHand.MAIN_HAND, pig));
        if (GhostState.isGhost(p.getUUID())) h.fail("interacting with a pig did not end the ghost");
        leave(h, p);
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void ghostExpiresOnItsOwn(GameTestHelper h) {
        floor(h);
        ServerPlayer p = survivalPlayer(h, new BlockPos(1, 1, 1));
        GhostState.start(p, 2, p.position());
        h.runAfterDelay(60, () -> {
            if (GhostState.isGhost(p.getUUID())) h.fail("2 s ghost still active after 3 s");
            leave(h, p);
            h.succeed();
        });
    }
}
