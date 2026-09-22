package com.coffeesaerosmp.auth.tracking;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Append-only per-player infraction history.
 *
 * <p>Written from call sites that ALREADY EXIST — chat-filter hits, watchdog events, confiscations,
 * warns, bans, name rejections. Deliberately no new event subscriptions: a per-event listener is how
 * this server lost 7.89% of the server thread to recipe-advancement scans.
 */
public final class InfractionLog {

    /** Longest `detail` the column accepts; longer text is truncated rather than lost. */
    private static final int MAX_DETAIL = 512;

    private InfractionLog() {}

    /**
     * @param type  one of CHAT_FILTER, WATCHDOG, CONFISCATE, RELEASE, WARN, BAN, NAME_REJECT
     * @param actor admin name, or null for "system"
     */
    public static void record(UUID uuid, String type, String detail, String actor) {
        if (uuid == null || type == null) return;
        long now = System.currentTimeMillis();
        String trimmed = detail == null ? null
            : detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO infractions (uuid, type, detail, actor, epoch) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, type);
                ps.setString(3, trimmed);
                ps.setString(4, actor == null ? "system" : actor);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Infraction] write failed: {}", e.toString());
            }
        });
    }

    /**
     * How many infractions this player has.
     *
     * <p>🔴 BLOCKING. Never call from the server thread — wrap it in {@code AsyncIo.submit} and post
     * the result back with {@code server.execute}.
     */
    public static int count(UUID uuid) {
        if (uuid == null) return 0;
        if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return 0;
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM infractions WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Infraction] count failed: {}", e.toString());
            return 0;
        }
    }
}
