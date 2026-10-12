package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Coffee Beans: a balance row plus an append-only ledger, keyed on the Mojang uuid like every store table.
 *
 * <h2>🔴 Why a ledger and not just a balance</h2>
 * The approved spec requires a purchase history (Mojang's rule) and the ability to reverse Beans on a chargeback.
 * Every change is a {@code beans_ledger} row carrying the balance after it, so the history can be read back and
 * audited against the balance.
 *
 * <h2>Concurrency</h2>
 * Every change runs in one transaction that first locks the player's balance row ({@code SELECT … FOR UPDATE}),
 * so two clicks on "buy", or a purchase racing a Tebex credit, are serialised by MySQL — the second sees the
 * first's result. The ownership check for a purchase happens inside that lock, so a double click cannot charge
 * twice for one item.
 *
 * <h2>Idempotency</h2>
 * A credit with a {@code ref} (a Tebex transaction id) is applied at most once per player: the
 * {@code UNIQUE (mojang_uuid, ref)} key makes a retried delivery a no-op that reports {@link Result#DUPLICATE}.
 *
 * <p>Every method blocks on MySQL: never call from the server thread — route through {@code AsyncIo}.
 */
public final class BeanLedger {

    public enum Result { APPLIED, DUPLICATE, ALREADY_OWNED, NOT_FOR_SALE, INSUFFICIENT, FAILED }

    /** {@code balance} is the balance after the call, or -1 when it is unknown (failure). */
    public record Outcome(Result result, int balance, int price) {
        static Outcome of(Result r) { return new Outcome(r, -1, 0); }
    }

    public record Entry(long atMs, int delta, int balanceAfter, String kind, String note) {}

    private BeanLedger() {}

    private static DatabaseManager db() { return CoffeesAeroAuth.DB_MANAGER; }

    public static boolean available() {
        DatabaseManager d = db();
        return d != null && d.isAvailable();
    }

    /** Current balance; 0 for a player who has never had Beans, -1 when the database is down. */
    public static int balance(UUID mojang) {
        if (mojang == null || !available()) return -1;
        try (Connection c = db().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM beans_balance WHERE mojang_uuid = ?")) {
            ps.setString(1, mojang.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Beans] balance read failed for {}: {}", mojang, e.getMessage());
            return -1;
        }
    }

    /**
     * Add (or with a negative amount, remove) Beans. {@code kind}: PURCHASE (bought with money), GRANT (staff /
     * event), REFUND, REVERSAL (chargeback). A removal never takes the balance below zero — it reports
     * {@link Result#INSUFFICIENT} and changes nothing.
     *
     * @param ref idempotency key (Tebex transaction id), or null for a one-off staff grant
     */
    public static Outcome credit(UUID mojang, int amount, String kind, String ref, String note) {
        if (mojang == null || amount == 0 || !available()) return Outcome.of(Result.FAILED);
        return retrying(() -> creditOnce(mojang, amount, kind, ref, note), "credit", mojang);
    }

    private static Outcome creditOnce(UUID mojang, int amount, String kind, String ref, String note) throws SQLException {
        try (Connection c = db().getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int bal = lockBalance(c, mojang);
                if (ref != null) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT 1 FROM beans_ledger WHERE mojang_uuid = ? AND ref = ?")) {
                        ps.setString(1, mojang.toString());
                        ps.setString(2, ref);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) { c.rollback(); return new Outcome(Result.DUPLICATE, bal, 0); }
                        }
                    }
                }
                long after = (long) bal + amount;
                if (after < 0) { c.rollback(); return new Outcome(Result.INSUFFICIENT, bal, 0); }
                if (after > Integer.MAX_VALUE) { c.rollback(); return new Outcome(Result.FAILED, bal, 0); }
                write(c, mojang, amount, (int) after, kind, ref, note);
                c.commit();
                CoffeesAeroAuth.LOGGER.info("[Beans] {} {} for {} ({}) -> balance {}", kind, amount, mojang, note, after);
                return new Outcome(Result.APPLIED, (int) after, 0);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    /** Spend Beans on a cosmetic: debit, ledger row and ownership grant in ONE transaction. */
    public static Outcome buy(UUID mojang, String cosmeticId) {
        if (mojang == null || cosmeticId == null) return Outcome.of(Result.FAILED);
        String id = cosmeticId.trim().toLowerCase(java.util.Locale.ROOT);
        OptionalInt price = BeanPrices.price(id);
        if (price.isEmpty()) return Outcome.of(Result.NOT_FOR_SALE);
        if (!available()) return Outcome.of(Result.FAILED);
        int cost = price.getAsInt();
        Outcome o = retrying(() -> buyOnce(mojang, id, cost), "buy " + id, mojang);
        if (o.result() != Result.APPLIED) return o;
        MojangIds.toLocal(mojang).ifPresent(StoreState::invalidate);
        return new Outcome(Result.APPLIED, balance(mojang), cost);
    }

    private static Outcome buyOnce(UUID mojang, String id, int cost) throws SQLException {
        try (Connection c = db().getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int bal = lockBalance(c, mojang);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM cosmetics_owned WHERE mojang_uuid = ? AND cosmetic_id = ? AND revoked_at IS NULL")) {
                    ps.setString(1, mojang.toString());
                    ps.setString(2, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { c.rollback(); return new Outcome(Result.ALREADY_OWNED, bal, cost); }
                    }
                }
                if (bal < cost) { c.rollback(); return new Outcome(Result.INSUFFICIENT, bal, cost); }
                write(c, mojang, -cost, bal - cost, "SPEND", null, id);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO cosmetics_owned (mojang_uuid, cosmetic_id, granted_at, source) VALUES (?,?,?,'BEANS') " +
                        "ON DUPLICATE KEY UPDATE revoked_at = NULL, source = 'BEANS', granted_at = VALUES(granted_at)")) {
                    ps.setString(1, mojang.toString());
                    ps.setString(2, id);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                c.commit();
                CoffeesAeroAuth.LOGGER.info("[Beans] {} bought {} for {} -> balance {}", mojang, id, cost, bal - cost);
                return new Outcome(Result.APPLIED, bal - cost, cost);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    private interface Tx { Outcome run() throws SQLException; }

    /**
     * MySQL resolves a lock conflict by killing one transaction (errno 1213 deadlock, 1205 lock wait timeout); the
     * victim changed nothing, so retrying it is safe and is what MySQL tells the client to do. Caught by the
     * concurrent-buy GameTest: two clicks on a player's first purchase raced the creation of the balance row.
     */
    private static Outcome retrying(Tx tx, String what, UUID mojang) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.run();
            } catch (SQLException e) {
                boolean lockConflict = e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
                if (lockConflict && attempt < 4) {
                    try { Thread.sleep(20L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    continue;
                }
                CoffeesAeroAuth.LOGGER.error("[Beans] {} failed for {}: {}", what, mojang, e.getMessage());
                return Outcome.of(Result.FAILED);
            }
        }
    }

    /** Newest first. */
    public static List<Entry> history(UUID mojang, int limit) {
        List<Entry> out = new ArrayList<>();
        if (mojang == null || !available()) return out;
        try (Connection c = db().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT created_at, delta, balance_after, kind, note FROM beans_ledger " +
                 "WHERE mojang_uuid = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, mojang.toString());
            ps.setInt(2, Math.max(1, Math.min(50, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Entry(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Beans] history read failed for {}: {}", mojang, e.getMessage());
        }
        return out;
    }

    /**
     * Lock the player's balance row for this transaction, creating it first only if it is missing. Locking FIRST
     * matters: an unconditional INSERT IGNORE takes a shared lock on an existing row, and two transactions both
     * upgrading shared to exclusive is a textbook deadlock (seen in the GameTest).
     */
    private static int lockBalance(Connection c, UUID mojang) throws SQLException {
        Integer bal = selectForUpdate(c, mojang);
        if (bal != null) return bal;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO beans_balance (mojang_uuid, balance, updated_at) VALUES (?, 0, ?)")) {
            ps.setString(1, mojang.toString());
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
        bal = selectForUpdate(c, mojang);
        if (bal == null) throw new SQLException("balance row vanished");
        return bal;
    }

    private static Integer selectForUpdate(Connection c, UUID mojang) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM beans_balance WHERE mojang_uuid = ? FOR UPDATE")) {
            ps.setString(1, mojang.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : null; }
        }
    }

    private static void write(Connection c, UUID mojang, int delta, int after, String kind, String ref, String note)
            throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE beans_balance SET balance = ?, updated_at = ? WHERE mojang_uuid = ?")) {
            ps.setInt(1, after);
            ps.setLong(2, now);
            ps.setString(3, mojang.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO beans_ledger (mojang_uuid, delta, balance_after, kind, ref, note, created_at) " +
                "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, mojang.toString());
            ps.setInt(2, delta);
            ps.setInt(3, after);
            ps.setString(4, kind);
            ps.setString(5, ref);
            ps.setString(6, note == null ? null : (note.length() > 128 ? note.substring(0, 128) : note));
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }
}
