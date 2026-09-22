package com.coffeesaerosmp.auth.tracking;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * One row per session, written on logout.
 *
 * <p>Safe to write from {@code PlayerLoggedOutEvent}: that event fires before vanilla saves the
 * {@code .dat}, but this is our own table, not the player file.
 *
 * <h2>Lobby sessions ARE recorded — and that is not the same as counting lobby playtime</h2>
 * Playtime totals are SMP-only (owner decision 2026-09-22): the lobby is a waiting room, and
 * {@code SaveGuard} / {@code AuthManager} no longer bank it. This table is different — it is a
 * history of connections, and "spent 4 minutes stuck in the lobby" is exactly the kind of thing an
 * admin wants to see when someone reports trouble getting in. The {@code server_role} column keeps
 * the two readings apart, so nothing here can leak back into a playtime figure.
 */
public final class PlayerSessionLog {

    private PlayerSessionLog() {}

    /**
     * @param reason short disconnect cause — QUIT, KICK, TIMEOUT, TRANSFER, AFK
     */
    public static void recordLogout(ServerPlayer player, String reason) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        var store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return;
        var profile = store.get(uuid);
        // sessionStartEpoch is stamped at auth. Zero means they never authenticated — a refused
        // connection or a gate bounce — and there is no session to describe.
        if (profile == null || profile.sessionStartEpoch <= 0) return;

        long start = profile.sessionStartEpoch;
        long end   = System.currentTimeMillis();
        int  secs  = (int) Math.max(0, (end - start) / 1000L);
        String ip   = ipOf(player);
        String role = LobbyHandoff.isLobbyRole() ? "LOBBY" : "SMP";

        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO session_log (uuid, login_epoch, logout_epoch, duration_s, ip, " +
                     "server_role, reason) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, start);
                ps.setLong(3, end);
                ps.setInt(4, secs);
                ps.setString(5, ip);
                ps.setString(6, role);
                ps.setString(7, reason);
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Tracking] session write failed for {}: {}",
                    uuid, e.toString());
            }
        });
    }

    /** Bare IP, port stripped. Null rather than a guess if the connection is already gone. */
    private static String ipOf(ServerPlayer player) {
        try {
            String raw = player.connection.getRemoteAddress().toString();
            if (raw.startsWith("/")) raw = raw.substring(1);
            int colon = raw.lastIndexOf(':');
            return colon > 0 ? raw.substring(0, colon) : raw;
        } catch (Exception e) {
            return null;
        }
    }
}
