package com.coffeesaerosmp.auth;

import com.coffeesaerosmp.auth.auth.AuthManager;
import com.coffeesaerosmp.auth.auth.AuthState;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

/**
 * Real (mock-connection) SURVIVAL players for GameTests. GameTestHelper's own mock player is hard-wired
 * creative — creative players take no damage and are never targeted, which would make protection tests
 * pass for the wrong reason.
 */
public final class GameTestPlayers {

    private GameTestPlayers() {}

    /** Stone floor under the 7×7 "empty" template, so a player standing on it does not fall. */
    public static void floor(GameTestHelper h) {
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    @SuppressWarnings("unchecked")
    public static ServerPlayer survival(GameTestHelper h, BlockPos rel, String namePrefix) {
        ServerLevel level = h.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
            new GameProfile(UUID.randomUUID(), namePrefix + (int) (Math.random() * 1000)), false);
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

    public static void remove(GameTestHelper h, ServerPlayer p) {
        h.getLevel().getServer().getPlayerList().remove(p);
    }
}
