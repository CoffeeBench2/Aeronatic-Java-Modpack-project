package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * Every write to the store tables. One class so there is a single place where money turns into state.
 *
 * <h2>🔴 Idempotency is not optional</h2>
 * Tebex retries delivery. A command that runs twice must not grant twice, and a grant that arrives for
 * a player we cannot resolve must be parked rather than dropped — see {@link MojangIds#queue}. Both
 * properties are enforced here rather than left to callers, because the caller is a command string
 * typed into a web dashboard by a human.
 *
 * <h2>Threading</h2>
 * Every method blocks on MySQL, so none may be called from the server thread. Commands here are
 * invoked by Tebex's own scheduler or from an admin command; both route through {@code AsyncIo}.
 */
public final class StoreGrants {

    private StoreGrants() {}

    private static DatabaseManager db() { return CoffeesAeroAuth.DB_MANAGER; }

    private static boolean down() {
        DatabaseManager d = db();
        return d == null || !d.isAvailable();
    }

    /** What happened, so a command can report it and a caller can tell "did nothing" from "failed". */
    public enum Outcome { APPLIED, QUEUED, FAILED }

    /**
     * Grant or extend a rank subscription.
     *
     * <p>Extending vs replacing is decided by whether the tier matches. Same tier → the period is
     * extended from whichever is later, the current expiry or now (so a renewal after a gap does not
     * silently credit the gap). Different tier → a plan change, which starts a fresh period, because
     * Tebex has already prorated on its side and double-counting here would hand out free time.
     */
    public static Outcome grantRank(UUID mojangUuid, Rank rank, int days, String txn) {
        if (mojangUuid == null || rank == null || rank == Rank.NONE || days <= 0) return Outcome.FAILED;
        if (down()) {
            MojangIds.queue(mojangUuid, MojangIds.Kind.RANK, rank.name() + ":" + days, 0L, txn);
            return Outcome.QUEUED;
        }
        // A rank is worth nothing until it can be rendered, and rendering needs the local uuid. If we
        // cannot resolve the player yet, park it — the column backfills on their next gate-verified
        // join. Dropping it here would consume a paid order silently.
        if (MojangIds.toLocal(mojangUuid).isEmpty()) {
            boolean q = MojangIds.queue(mojangUuid, MojangIds.Kind.RANK, rank.name() + ":" + days, 0L, txn);
            return q ? Outcome.QUEUED : Outcome.FAILED;
        }

        long now = System.currentTimeMillis();
        long addMs = days * 86_400_000L;
        try (Connection c = db().getConnection()) {
            Rank existing = Rank.NONE;
            long existingExpiry = 0L;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT rank_id, expires_at FROM subscriptions WHERE mojang_uuid = ?")) {
                ps.setString(1, mojangUuid.toString());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) { existing = Rank.parse(rs.getString(1)); existingExpiry = rs.getLong(2); }
                }
            }
            long base = (existing == rank && existingExpiry > now) ? existingExpiry : now;
            long expires = base + addMs;

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subscriptions " +
                    "(mojang_uuid, rank_id, started_at, expires_at, last_payment_at, tebex_package) " +
                    "VALUES (?,?,?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE rank_id = VALUES(rank_id), expires_at = VALUES(expires_at), " +
                    "last_payment_at = VALUES(last_payment_at), tebex_package = VALUES(tebex_package)")) {
                ps.setString(1, mojangUuid.toString());
                ps.setString(2, rank.name());
                ps.setLong(3, existing == Rank.NONE ? now : now);
                ps.setLong(4, expires);
                ps.setLong(5, now);
                ps.setString(6, txn);
                ps.executeUpdate();
            }
            CoffeesAeroAuth.LOGGER.info("[Store] {} -> {} for {} day(s), expires {} (txn {})",
                mojangUuid, rank, days, expires, txn);
            refresh(mojangUuid);
            return Outcome.APPLIED;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.error("[Store] grantRank failed for {}: {}", mojangUuid, e.getMessage());
            return Outcome.FAILED;
        }
    }

    /** End a subscription now — chargeback, refund or manual removal. Cosmetics owned are untouched. */
    public static Outcome expireRank(UUID mojangUuid) {
        if (mojangUuid == null || down()) return Outcome.FAILED;
        try (Connection c = db().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE subscriptions SET expires_at = ? WHERE mojang_uuid = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, mojangUuid.toString());
            int n = ps.executeUpdate();
            refresh(mojangUuid);
            return n > 0 ? Outcome.APPLIED : Outcome.FAILED;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.error("[Store] expireRank failed for {}: {}", mojangUuid, e.getMessage());
            return Outcome.FAILED;
        }
    }

    /**
     * Grant a permanent cosmetic (Coffee Beans purchase).
     *
     * <p>{@code ON DUPLICATE KEY} makes a retry a no-op AND clears {@code revoked_at}, so re-granting
     * after a reversal restores the entitlement without a second audit row.
     */
    public static Outcome grantCosmetic(UUID mojangUuid, String cosmeticId, String source, String txn) {
        if (mojangUuid == null || cosmeticId == null || cosmeticId.isBlank()) return Outcome.FAILED;

        // Refuse a staff colour at the door as well as at render time. Defence in depth: a bad Tebex
        // package should fail loudly here rather than sit in the table being silently ignored forever.
        if (cosmeticId.startsWith(Entitlements.COLOUR_PREFIX)
            && Palette.isStaffReserved(cosmeticId.substring(Entitlements.COLOUR_PREFIX.length()))) {
            CoffeesAeroAuth.LOGGER.error("[Store] REFUSED staff-reserved cosmetic '{}' for {}",
                cosmeticId, mojangUuid);
            return Outcome.FAILED;
        }
        if (down()) {
            MojangIds.queue(mojangUuid, MojangIds.Kind.COSMETIC, cosmeticId, 0L, txn);
            return Outcome.QUEUED;
        }
        if (MojangIds.toLocal(mojangUuid).isEmpty()) {
            boolean q = MojangIds.queue(mojangUuid, MojangIds.Kind.COSMETIC, cosmeticId, 0L, txn);
            return q ? Outcome.QUEUED : Outcome.FAILED;
        }
        try (Connection c = db().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO cosmetics_owned (mojang_uuid, cosmetic_id, granted_at, source, tebex_txn) " +
                 "VALUES (?,?,?,?,?) " +
                 "ON DUPLICATE KEY UPDATE revoked_at = NULL, tebex_txn = VALUES(tebex_txn)")) {
            ps.setString(1, mojangUuid.toString());
            ps.setString(2, cosmeticId);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, source == null ? "BEANS" : source);
            ps.setString(5, txn);
            ps.executeUpdate();
            CoffeesAeroAuth.LOGGER.info("[Store] granted '{}' to {} (txn {})", cosmeticId, mojangUuid, txn);
            refresh(mojangUuid);
            return Outcome.APPLIED;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.error("[Store] grantCosmetic failed for {}: {}", mojangUuid, e.getMessage());
            return Outcome.FAILED;
        }
    }

    /** Reverse a cosmetic (chargeback). The audit row survives; only the entitlement stops. */
    public static Outcome revokeCosmetic(UUID mojangUuid, String cosmeticId) {
        if (mojangUuid == null || cosmeticId == null || down()) return Outcome.FAILED;
        try (Connection c = db().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "UPDATE cosmetics_owned SET revoked_at = ? " +
                 "WHERE mojang_uuid = ? AND cosmetic_id = ? AND revoked_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, mojangUuid.toString());
            ps.setString(3, cosmeticId);
            int n = ps.executeUpdate();
            refresh(mojangUuid);
            return n > 0 ? Outcome.APPLIED : Outcome.FAILED;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.error("[Store] revokeCosmetic failed: {}", e.getMessage());
            return Outcome.FAILED;
        }
    }

    /** Store the player's chosen name style / bold flag. Validation is the caller's job. */
    public static boolean setNameSelection(UUID mojangUuid, String nameStyle, boolean bold) {
        if (mojangUuid == null || down()) return false;
        try (Connection c = db().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO cosmetic_selection (mojang_uuid, name_style, bold, updated_at) " +
                 "VALUES (?,?,?,?) " +
                 "ON DUPLICATE KEY UPDATE name_style = VALUES(name_style), bold = VALUES(bold), " +
                 "updated_at = VALUES(updated_at)")) {
            ps.setString(1, mojangUuid.toString());
            ps.setString(2, nameStyle);
            ps.setBoolean(3, bold);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            refresh(mojangUuid);
            return true;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] setNameSelection failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Submit free text for moderation. Writes ONLY the pending column, so nothing renders until
     * {@link #approveText} copies it across.
     *
     * @param which {@code "chat_title"} or {@code "join_message"}
     */
    public static boolean submitTextForApproval(UUID mojangUuid, String which, String value) {
        String col = switch (which) {
            case "chat_title"   -> "pending_chat_title";
            case "join_message" -> "pending_join_message";
            default -> null;
        };
        if (mojangUuid == null || col == null || down()) return false;
        // Column name is from a closed switch, never from user input — no interpolation of caller data.
        String sql = "INSERT INTO cosmetic_selection (mojang_uuid, " + col + ", updated_at) " +
                     "VALUES (?,?,?) ON DUPLICATE KEY UPDATE " + col + " = VALUES(" + col + "), " +
                     "updated_at = VALUES(updated_at)";
        try (Connection c = db().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mojangUuid.toString());
            ps.setString(2, value);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] submitTextForApproval failed: {}", e.getMessage());
            return false;
        }
    }

    /** Approve pending free text: copy pending → live, clear pending. */
    public static boolean approveText(UUID mojangUuid, String which) {
        String live = switch (which) {
            case "chat_title"   -> "chat_title";
            case "join_message" -> "join_message";
            default -> null;
        };
        if (mojangUuid == null || live == null || down()) return false;
        String pending = "pending_" + live;
        String sql = "UPDATE cosmetic_selection SET " + live + " = " + pending + ", " +
                     pending + " = NULL, updated_at = ? WHERE mojang_uuid = ?";
        try (Connection c = db().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, mojangUuid.toString());
            boolean ok = ps.executeUpdate() > 0;
            refresh(mojangUuid);
            return ok;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] approveText failed: {}", e.getMessage());
            return false;
        }
    }

    /** Reject pending free text: clear pending, leave whatever was already approved alone. */
    public static boolean rejectText(UUID mojangUuid, String which) {
        String col = switch (which) {
            case "chat_title"   -> "pending_chat_title";
            case "join_message" -> "pending_join_message";
            default -> null;
        };
        if (mojangUuid == null || col == null || down()) return false;
        String sql = "UPDATE cosmetic_selection SET " + col + " = NULL, updated_at = ? WHERE mojang_uuid = ?";
        try (Connection c = db().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, mojangUuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] rejectText failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Apply every parked grant for a player, then stamp each one.
     *
     * <p>Called after a join has backfilled {@code mojang_uuid}. Stamps only after the grant landed —
     * stamping first would turn a mid-list failure into a lost order.
     */
    public static int applyPending(UUID mojangUuid) {
        if (mojangUuid == null) return 0;
        int applied = 0;
        for (MojangIds.Pending p : MojangIds.pendingFor(mojangUuid)) {
            Outcome o = Outcome.FAILED;
            if (p.kind() == MojangIds.Kind.RANK) {
                String[] bits = p.payload().split(":", 2);
                Rank r = Rank.parse(bits[0]);
                int days = 30;
                if (bits.length > 1) { try { days = Integer.parseInt(bits[1]); } catch (NumberFormatException ignored) {} }
                o = grantRank(mojangUuid, r, days, p.tebexTxn());
            } else {
                o = grantCosmetic(mojangUuid, p.payload(), "BEANS", p.tebexTxn());
            }
            if (o == Outcome.APPLIED) { MojangIds.markApplied(p.id()); applied++; }
        }
        if (applied > 0) {
            CoffeesAeroAuth.LOGGER.info("[Store] applied {} parked grant(s) for {}", applied, mojangUuid);
        }
        return applied;
    }

    /** Drop the render caches for whoever this Mojang uuid maps to, so the change shows immediately. */
    private static void refresh(UUID mojangUuid) {
        Optional<UUID> local = MojangIds.toLocal(mojangUuid);
        local.ifPresent(StoreState::invalidate);
    }
}
