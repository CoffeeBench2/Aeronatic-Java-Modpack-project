package com.coffeesaerosmp.auth.mail;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * MySQL side of /mail. Every call runs on {@link AsyncIo} (the single DB writer thread — ordering is
 * guaranteed) and hands its result back on the server thread, so nothing here ever blocks a tick.
 *
 * <h2>The two rules that keep mail from duplicating or losing items</h2>
 * <ol>
 *   <li><b>Sends are idempotent by {@code dedupe_key}</b> (UNIQUE). A level-up reward, a welcome mail,
 *       a broadcast — anything system-generated carries a key like {@code lvl:s3:<uuid>:12}, and
 *       {@code INSERT IGNORE} makes a second send a no-op. That holds across restarts AND across the
 *       lobby/SMP pair sharing this database, which is exactly where the starter bonus was once paid
 *       twice (2026-09-22).</li>
 *   <li><b>A claim is a compare-and-set</b>: {@code UPDATE … SET claimed_at=? WHERE id=? AND claimed_at
 *       IS NULL}. Only the caller that changed exactly one row hands out the items. A double click, two
 *       open GUIs, or a crash between "update" and "give" can at worst lose one delivery (logged with the
 *       mail id so staff can resend) — it can never pay twice.</li>
 * </ol>
 *
 * <p>Keyed on {@code players.uuid} (the uuid the player actually plays under). AccountTransfer moves
 * these rows on a rename/re-key — see its HISTORY_TABLES / PER_PLAYER_TABLES.
 */
public final class MailStore {

    /** Inbox page size × pages the GUI can show. Older mail beyond this is still claimable later. */
    public static final int INBOX_LIMIT = 45 * 6;

    private MailStore() {}

    public static void createSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS mail (" +
                "  id          BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  uuid        CHAR(36)      NOT NULL," +
                "  sender      VARCHAR(32)   NOT NULL," +
                "  subject     VARCHAR(64)   NOT NULL," +
                "  body        VARCHAR(1024) NOT NULL DEFAULT ''," +
                // SNBT of {items:[…]} written by MailItems — survives item components, enchantments, names.
                "  items       MEDIUMTEXT    NULL," +
                "  spurs       INT           NOT NULL DEFAULT 0," +
                "  created_at  BIGINT        NOT NULL," +
                "  expires_at  BIGINT        NOT NULL DEFAULT 0," +
                "  read_at     BIGINT        NULL," +
                "  claimed_at  BIGINT        NULL," +
                "  dedupe_key  VARCHAR(128)  NULL," +
                "  UNIQUE KEY uniq_mail_dedupe (dedupe_key)," +
                "  INDEX idx_mail_inbox (uuid, claimed_at, created_at)" +
                ")");
            // Highest level a player has already been rewarded for, per season. Its own table rather than
            // a players column on purpose: save() rewrites the whole players row from a cached copy, and
            // a stale copy from the other process would roll this back and re-pay every level.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS level_progress (" +
                "  uuid           CHAR(36) NOT NULL," +
                "  season         INT      NOT NULL," +
                "  level_rewarded INT      NOT NULL DEFAULT 1," +
                "  PRIMARY KEY (uuid, season)" +
                ")");
        }
        // 1.13.9 (mail remake): who SENT a mail, for the daily limit and returning expired parcels.
        // MySQL has no ADD COLUMN IF NOT EXISTS (that is MariaDB), so add and swallow "already there".
        alterIgnoring(c, "ALTER TABLE mail ADD COLUMN sender_uuid CHAR(36) NULL", 1060);
        alterIgnoring(c, "ALTER TABLE mail ADD INDEX idx_mail_sender (sender_uuid, created_at)", 1061);
    }

    private static void alterIgnoring(Connection c, String sql, int duplicateErrno) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            if (e.getErrorCode() != duplicateErrno) throw e;
        }
    }

    // ── model ────────────────────────────────────────────────────────────────

    public record Mail(long id, String sender, String subject, String body, String itemsSnbt, int spurs,
                       long createdAt, long expiresAt, boolean read, boolean claimed) {
        public boolean hasAttachments() {
            return spurs > 0 || (itemsSnbt != null && !itemsSnbt.isBlank());
        }
    }

    /**
     * A mail about to be sent. {@code dedupeKey} null = always deliver (manual mail). {@code senderUuid}
     * is set for mail a PLAYER or staff member sent: it is what the daily limit counts and where an
     * expired parcel goes back to. Null for system mail.
     */
    public record Outgoing(String sender, String subject, String body, String itemsSnbt, int spurs,
                           long expiresAt, String dedupeKey, UUID senderUuid) {
        public Outgoing(String sender, String subject, String body, String itemsSnbt, int spurs,
                        long expiresAt, String dedupeKey) {
            this(sender, subject, body, itemsSnbt, spurs, expiresAt, dedupeKey, null);
        }
    }

    // ── writes ───────────────────────────────────────────────────────────────

    /**
     * Delivers {@code mail} to every uuid in {@code to}. {@code done} receives how many were actually
     * inserted (a dedupe hit counts 0), on the server thread.
     */
    public static void send(MinecraftServer server, Collection<UUID> to, Outgoing mail, Consumer<Integer> done) {
        List<UUID> targets = new ArrayList<>(to);
        AsyncIo.submit(() -> {
            int n = 0;
            if (available()) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "INSERT IGNORE INTO mail (uuid, sender, subject, body, items, spurs, created_at, expires_at, dedupe_key, sender_uuid) "
                       + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    long now = System.currentTimeMillis();
                    for (UUID u : targets) {
                        ps.setString(1, u.toString());
                        ps.setString(2, trim(mail.sender(), 32));
                        ps.setString(3, trim(mail.subject(), 64));
                        ps.setString(4, trim(mail.body() == null ? "" : mail.body(), 1024));
                        if (mail.itemsSnbt() == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, mail.itemsSnbt());
                        ps.setInt(6, Math.max(0, mail.spurs()));
                        ps.setLong(7, now);
                        ps.setLong(8, mail.expiresAt());
                        // A per-recipient key: the same broadcast must still reach everyone exactly once.
                        if (mail.dedupeKey() == null) ps.setNull(9, Types.VARCHAR);
                        else ps.setString(9, trim(mail.dedupeKey() + (targets.size() > 1 ? ":" + u : ""), 128));
                        if (mail.senderUuid() == null) ps.setNull(10, Types.CHAR); else ps.setString(10, mail.senderUuid().toString());
                        n += ps.executeUpdate();
                    }
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[Mail] send failed ({} recipients): {}", targets.size(), e.getMessage());
                }
            }
            int sent = n;
            if (done != null && server != null) server.execute(() -> done.accept(sent));
        });
    }

    /**
     * Compare-and-set claim. {@code done} gets the mail (with its attachments) if THIS call claimed it, or
     * null if it was already claimed, is not this player's, or the DB is down.
     */
    public static void claim(MinecraftServer server, UUID owner, long id, Consumer<Mail> done) {
        AsyncIo.submit(() -> {
            Mail won = null;
            if (available()) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
                    long now = System.currentTimeMillis();
                    int changed;
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE mail SET claimed_at=?, read_at=COALESCE(read_at, ?) "
                          + "WHERE id=? AND uuid=? AND claimed_at IS NULL")) {
                        ps.setLong(1, now);
                        ps.setLong(2, now);
                        ps.setLong(3, id);
                        ps.setString(4, owner.toString());
                        changed = ps.executeUpdate();
                    }
                    if (changed == 1) won = loadOne(c, owner, id);
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[Mail] claim {} failed: {}", id, e.getMessage());
                }
            }
            Mail result = won;
            server.execute(() -> done.accept(result));
        });
    }

    public static void markRead(UUID owner, long id) {
        AsyncIo.submit(() -> update("UPDATE mail SET read_at=? WHERE id=? AND uuid=? AND read_at IS NULL",
            System.currentTimeMillis(), id, owner.toString()));
    }

    /** Deletes only mail with nothing left to take — a player cannot throw away an unclaimed parcel. */
    public static void delete(MinecraftServer server, UUID owner, long id, Runnable done) {
        AsyncIo.submit(() -> {
            update("DELETE FROM mail WHERE id=? AND uuid=? AND (claimed_at IS NOT NULL "
                 + "OR ((items IS NULL OR items='') AND spurs=0))", id, owner.toString());
            server.execute(done);
        });
    }

    public static void deleteAllClaimed(MinecraftServer server, UUID owner, Runnable done) {
        AsyncIo.submit(() -> {
            update("DELETE FROM mail WHERE uuid=? AND (claimed_at IS NOT NULL "
                 + "OR (read_at IS NOT NULL AND (items IS NULL OR items='') AND spurs=0))", owner.toString());
            server.execute(done);
        });
    }

    /**
     * Boot housekeeping. An expired, unclaimed parcel a PLAYER sent goes back to them first (dedupe key
     * {@code return:<id>}, so a crash between the two steps can never return it twice); then expired
     * mail is deleted, claimed or not. Returned parcels never expire: nobody's items are lost to a timer.
     */
    public static void returnExpired() {
        AsyncIo.submit(() -> {
            if (!available()) return;
            long now = System.currentTimeMillis();
            int returned = 0;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
                List<Object[]> back = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, sender, sender_uuid, items FROM mail WHERE expires_at > 0 AND expires_at < ? "
                      + "AND claimed_at IS NULL AND sender_uuid IS NOT NULL AND items IS NOT NULL AND items <> ''")) {
                    ps.setLong(1, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) back.add(new Object[]{rs.getLong(1), rs.getString(3), rs.getString(4)});
                    }
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT IGNORE INTO mail (uuid, sender, subject, body, items, spurs, created_at, expires_at, dedupe_key) "
                      + "VALUES (?,?,?,?,?,0,?,0,?)")) {
                    for (Object[] r : back) {
                        ins.setString(1, (String) r[1]);
                        ins.setString(2, "Mail Office");
                        ins.setString(3, "Your parcel came back");
                        ins.setString(4, "Nobody collected your parcel in time, so the Mail Office brought it "
                            + "back to you. Everything you packed is attached.");
                        ins.setString(5, (String) r[2]);
                        ins.setLong(6, now);
                        ins.setString(7, "return:" + r[0]);
                        returned += ins.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                // Do NOT delete anything if the returns could not be written.
                CoffeesAeroAuth.LOGGER.warn("[Mail] returning expired parcels failed, nothing purged: {}", e.getMessage());
                return;
            }
            int n = update("DELETE FROM mail WHERE expires_at > 0 AND expires_at < ?", now);
            if (n > 0 || returned > 0) {
                CoffeesAeroAuth.LOGGER.info("[Mail] {} expired mail purged, {} parcel(s) returned to sender.", n, returned);
            }
        });
    }

    /** Mail {@code sender} has sent since {@code sinceMs} — the daily limit. -1 if the DB is down. */
    public static void countSentSince(MinecraftServer server, UUID sender, long sinceMs, Consumer<Integer> done) {
        AsyncIo.submit(() -> {
            int n = -1;
            if (available()) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT COUNT(*) FROM mail WHERE sender_uuid=? AND created_at > ?")) {
                    ps.setString(1, sender.toString());
                    ps.setLong(2, sinceMs);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) n = rs.getInt(1); }
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[Mail] send count failed: {}", e.getMessage());
                }
            }
            int result = n;
            server.execute(() -> done.accept(result));
        });
    }

    // ── reads ────────────────────────────────────────────────────────────────

    /** Unclaimed parcels first, then newest. Returns an empty list (not null) if the DB is down. */
    public static void inbox(MinecraftServer server, UUID owner, Consumer<List<Mail>> done) {
        AsyncIo.submit(() -> {
            List<Mail> out = new ArrayList<>();
            if (available()) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT * FROM mail WHERE uuid=? ORDER BY (claimed_at IS NULL) DESC, created_at DESC LIMIT "
                       + INBOX_LIMIT)) {
                    ps.setString(1, owner.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(read(rs));
                    }
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[Mail] inbox read failed: {}", e.getMessage());
                }
            }
            server.execute(() -> done.accept(out));
        });
    }

    /** [unread, unclaimed-with-attachments] for the join notice. */
    public static void counts(MinecraftServer server, UUID owner, Consumer<int[]> done) {
        AsyncIo.submit(() -> {
            int[] r = {0, 0};
            if (available()) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT SUM(read_at IS NULL), SUM(claimed_at IS NULL AND (spurs > 0 OR (items IS NOT NULL AND items<>''))) "
                       + "FROM mail WHERE uuid=?")) {
                    ps.setString(1, owner.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { r[0] = rs.getInt(1); r[1] = rs.getInt(2); }
                    }
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[Mail] count failed: {}", e.getMessage());
                }
            }
            server.execute(() -> done.accept(r));
        });
    }

    // ── level bookkeeping ────────────────────────────────────────────────────

    /** The highest level already rewarded this season, or -1 if no row yet (first time on this system). */
    public static void levelRewarded(MinecraftServer server, UUID owner, int season, Consumer<Integer> done) {
        AsyncIo.submit(() -> {
            int lv = -1;
            if (available()) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT level_rewarded FROM level_progress WHERE uuid=? AND season=?")) {
                    ps.setString(1, owner.toString());
                    ps.setInt(2, season);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) lv = rs.getInt(1);
                    }
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[Level] progress read failed: {}", e.getMessage());
                    lv = Integer.MIN_VALUE;   // unknown: caller must not reward
                }
            } else {
                lv = Integer.MIN_VALUE;
            }
            int result = lv;
            server.execute(() -> done.accept(result));
        });
    }

    /** Monotonic: GREATEST, so a stale writer can never move it backwards. */
    public static void setLevelRewarded(UUID owner, int season, int level) {
        AsyncIo.submit(() -> update(
            "INSERT INTO level_progress (uuid, season, level_rewarded) VALUES (?,?,?) "
          + "ON DUPLICATE KEY UPDATE level_rewarded = GREATEST(level_rewarded, VALUES(level_rewarded))",
            owner.toString(), season, level));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static boolean available() {
        return CoffeesAeroAuth.DB_MANAGER != null && CoffeesAeroAuth.DB_MANAGER.isAvailable();
    }

    private static Mail loadOne(Connection c, UUID owner, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM mail WHERE id=? AND uuid=?")) {
            ps.setLong(1, id);
            ps.setString(2, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private static Mail read(ResultSet rs) throws SQLException {
        rs.getLong("read_at");
        boolean read = !rs.wasNull();
        rs.getLong("claimed_at");
        boolean claimed = !rs.wasNull();
        return new Mail(rs.getLong("id"), rs.getString("sender"), rs.getString("subject"), rs.getString("body"),
            rs.getString("items"), rs.getInt("spurs"), rs.getLong("created_at"), rs.getLong("expires_at"),
            read, claimed);
    }

    private static int update(String sql, Object... args) {
        if (!available()) return 0;
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Mail] write failed: {}", e.getMessage());
            return 0;
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
