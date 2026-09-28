package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Translates between the two identities this server has for one person.
 *
 * <pre>
 *   Mojang uuid (v4)  ── what Tebex delivers to, stable across renames ── players.mojang_uuid
 *   local uuid  (v3)  ── md5("OfflinePlayer:"+name), what the WORLD keys on ── players.uuid
 * </pre>
 *
 * <p>Store tables key on the Mojang uuid; everything in the world keys on the local one. This class is
 * the only place that crosses between them, so there is exactly one thing to audit.
 *
 * <h2>🔴 Never call these from the server thread</h2>
 * Every method here hits MySQL. The no-DB-on-the-join-path rule exists because
 * {@code PlayerAuthEvents.onPlayerJoin} used to spend up to a second of main-thread time on profile
 * round trips. Call from {@code AsyncIo}, cache the answer in memory, and render from the cache. The
 * low DB latency since the database moved onto the game host is headroom, not licence.
 */
public final class MojangIds {

    private MojangIds() {}

    private static DatabaseManager db() { return CoffeesAeroAuth.DB_MANAGER; }

    private static boolean unavailable() {
        DatabaseManager d = db();
        return d == null || !d.isAvailable();
    }

    /**
     * Mojang uuid → the local (world) uuid, if we have ever seen that player as premium.
     *
     * <p>Empty means "we cannot place this purchase yet" — not "no such player". The column backfills
     * on each gate-verified join, so the same lookup can start succeeding later. That is precisely why
     * a miss must {@link #queue} rather than discard.
     */
    public static Optional<UUID> toLocal(UUID mojangUuid) {
        if (mojangUuid == null || unavailable()) return Optional.empty();
        String sql = "SELECT uuid FROM players WHERE mojang_uuid = ? LIMIT 1";
        try (Connection c = db().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mojangUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(UUID.fromString(rs.getString(1)));
            }
        } catch (SQLException | IllegalArgumentException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] mojang→local lookup failed for {}: {}",
                mojangUuid, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Local (world) uuid → the Mojang uuid, which is the key every store table uses.
     *
     * <p>Empty means this player has no store identity yet, so they can own nothing — render them as
     * unranked rather than treating it as an error.
     */
    public static Optional<UUID> toMojang(UUID localUuid) {
        if (localUuid == null || unavailable()) return Optional.empty();
        String sql = "SELECT mojang_uuid FROM players WHERE uuid = ? LIMIT 1";
        try (Connection c = db().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, localUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    if (v != null && !v.isBlank()) return Optional.of(UUID.fromString(v));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] local→mojang lookup failed for {}: {}",
                localUuid, e.getMessage());
        }
        return Optional.empty();
    }

    /** How many premium rows still have no {@code mojang_uuid}. The store-launch readiness number. */
    public static int unresolvedPremiumCount() {
        if (unavailable()) return -1;
        String sql = "SELECT COUNT(*) FROM players WHERE account_type = 'PREMIUM' " +
                     "AND (mojang_uuid IS NULL OR mojang_uuid = '')";
        try (Connection c = db().getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] readiness count failed: {}", e.getMessage());
            return -1;
        }
    }

    /** A grant that arrived before we could place it. */
    public record Pending(long id, Kind kind, String payload, long expiresAt, String tebexTxn) {}

    public enum Kind { RANK, COSMETIC }

    /**
     * Park a grant we cannot deliver yet.
     *
     * <p>🔴 This must be called whenever resolution fails, and it must not be conditional on anything
     * else succeeding. Tebex marks an order delivered once its command has run; a command that
     * resolves nothing and returns quietly has <b>consumed a paid order and left no record</b>. This
     * table is that record.
     */
    public static boolean queue(UUID mojangUuid, Kind kind, String payload, long expiresAt, String txn) {
        if (mojangUuid == null || kind == null || payload == null) return false;
        if (unavailable()) {
            // Last resort: the operation replays when the pool reconnects. Losing the row entirely
            // would lose the order, so this is logged loudly rather than swallowed.
            CoffeesAeroAuth.LOGGER.error(
                "[Store] DB DOWN while queueing a {} grant for {} ({}) — queued for replay",
                kind, mojangUuid, payload);
            DatabaseManager d = db();
            if (d == null) return false;
            d.queueWrite(conn -> insertPending(conn, mojangUuid, kind, payload, expiresAt, txn));
            return true;
        }
        try (Connection c = db().getConnection()) {
            insertPending(c, mojangUuid, kind, payload, expiresAt, txn);
            CoffeesAeroAuth.LOGGER.info("[Store] queued {} grant '{}' for unresolved player {}",
                kind, payload, mojangUuid);
            return true;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.error("[Store] FAILED to queue a {} grant for {} ({}): {}",
                kind, mojangUuid, payload, e.getMessage());
            return false;
        }
    }

    private static void insertPending(Connection c, UUID mojangUuid, Kind kind, String payload,
                                      long expiresAt, String txn) throws SQLException {
        String sql = "INSERT INTO store_pending_grants " +
                     "(mojang_uuid, kind, payload, expires_at, created_at, tebex_txn) " +
                     "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mojangUuid.toString());
            ps.setString(2, kind.name());
            ps.setString(3, payload);
            ps.setLong(4, expiresAt);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, txn);
            ps.executeUpdate();
        }
    }

    /** Unapplied grants for a player, oldest first. Read on join, off-thread. */
    public static List<Pending> pendingFor(UUID mojangUuid) {
        List<Pending> out = new ArrayList<>();
        if (mojangUuid == null || unavailable()) return out;
        String sql = "SELECT id, kind, payload, expires_at, tebex_txn FROM store_pending_grants " +
                     "WHERE mojang_uuid = ? AND applied_at IS NULL ORDER BY created_at ASC";
        try (Connection c = db().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mojangUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Pending(rs.getLong(1), Kind.valueOf(rs.getString(2)),
                                        rs.getString(3), rs.getLong(4), rs.getString(5)));
                }
            }
        } catch (SQLException | IllegalArgumentException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] pending lookup failed for {}: {}",
                mojangUuid, e.getMessage());
        }
        return out;
    }

    /**
     * Mark a pending grant applied.
     *
     * <p>Stamped only AFTER the grant has actually been written. Stamping first would turn any failure
     * in between into a silently lost order — the same shape as the delivery bug this table exists to
     * prevent.
     */
    public static void markApplied(long id) {
        if (unavailable()) return;
        try (Connection c = db().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE store_pending_grants SET applied_at = ? WHERE id = ? AND applied_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setLong(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] could not stamp pending grant {}: {}", id, e.getMessage());
        }
    }
}
