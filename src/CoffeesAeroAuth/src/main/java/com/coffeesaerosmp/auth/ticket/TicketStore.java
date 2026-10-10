package com.coffeesaerosmp.auth.ticket;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.coffeesaerosmp.auth.ticket.Ticket.Status;

/**
 * MySQL side of /ticket. Writes and player-facing reads go through {@link AsyncIo} and call back on the
 * server thread. {@link #getNow} is the one synchronous read: Discord button handlers run on the gateway
 * thread and must answer within 3 s, so they read the row by primary key directly instead of queueing
 * behind the single DB writer.
 */
public final class TicketStore {

    private TicketStore() {}

    public static void createSchema(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            // A new table, so utf8mb4 explicitly: players write emoji and non-Latin text.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS tickets (" +
                "  id          BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  uuid        CHAR(36)      NOT NULL," +
                "  name        VARCHAR(32)   NOT NULL," +
                "  message     VARCHAR(600)  NOT NULL," +
                "  location    VARCHAR(96)   NOT NULL DEFAULT ''," +
                "  status      VARCHAR(12)   NOT NULL DEFAULT 'OPEN'," +
                "  created_at  BIGINT        NOT NULL," +
                "  updated_at  BIGINT        NOT NULL," +
                "  closed_by   VARCHAR(64)   NULL," +
                "  last_reply  VARCHAR(1024) NULL," +
                "  INDEX idx_tickets_uuid_created (uuid, created_at)" +
                ") DEFAULT CHARSET=utf8mb4");
        }
    }

    static boolean available() {
        return CoffeesAeroAuth.DB_MANAGER != null && CoffeesAeroAuth.DB_MANAGER.isAvailable();
    }

    /** Tickets {@code uuid} opened at or after {@code sinceMs}; -1 if the DB is unavailable. */
    static void countSince(MinecraftServer server, UUID uuid, long sinceMs, Consumer<Integer> done) {
        AsyncIo.submit(() -> {
            int n = -1;
            if (available()) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT COUNT(*) FROM tickets WHERE uuid=? AND created_at>=?")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, sinceMs);
                    try (ResultSet rs = ps.executeQuery()) { n = rs.next() ? rs.getInt(1) : 0; }
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[Ticket] count failed: {}", e.getMessage());
                }
            }
            int result = n;
            server.execute(() -> done.accept(result));
        });
    }

    /** Inserts a ticket; {@code done} gets the new id, or -1 on failure. */
    static void create(MinecraftServer server, UUID uuid, String name, String message, String location,
                       Consumer<Long> done) {
        AsyncIo.submit(() -> {
            long id = -1;
            if (available()) {
                long now = System.currentTimeMillis();
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO tickets (uuid, name, message, location, status, created_at, updated_at) "
                       + "VALUES (?,?,?,?, 'OPEN', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, name);
                    ps.setString(3, message);
                    ps.setString(4, location);
                    ps.setLong(5, now);
                    ps.setLong(6, now);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) id = rs.getLong(1); }
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[Ticket] create failed: {}", e.getMessage());
                }
            }
            long result = id;
            server.execute(() -> done.accept(result));
        });
    }

    /** Synchronous read by id — for the Discord gateway thread only (never the server thread). */
    static Ticket getNow(long id) {
        if (!available()) return null;
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM tickets WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? read(rs) : null; }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Ticket] read #{} failed: {}", id, e.getMessage());
            return null;
        }
    }

    /** Fire-and-forget status / reply update. {@code closedBy} and {@code lastReply} null = unchanged. */
    static void update(long id, Status status, String closedBy, String lastReply) {
        AsyncIo.submit(() -> {
            if (!available()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "UPDATE tickets SET status=?, updated_at=?, closed_by=COALESCE(?, closed_by), "
                   + "last_reply=COALESCE(?, last_reply) WHERE id=?")) {
                ps.setString(1, status.name());
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, closedBy);
                ps.setString(4, lastReply);
                ps.setLong(5, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                CoffeesAeroAuth.LOGGER.warn("[Ticket] update #{} failed: {}", id, e.getMessage());
            }
        });
    }

    /** The player's 10 newest tickets; null if the DB is unavailable. */
    static void listFor(MinecraftServer server, UUID uuid, Consumer<List<Ticket>> done) {
        AsyncIo.submit(() -> {
            List<Ticket> out = null;
            if (available()) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "SELECT * FROM tickets WHERE uuid=? ORDER BY created_at DESC LIMIT 10")) {
                    ps.setString(1, uuid.toString());
                    out = new ArrayList<>();
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(read(rs)); }
                } catch (SQLException e) {
                    out = null;
                    CoffeesAeroAuth.LOGGER.warn("[Ticket] list failed: {}", e.getMessage());
                }
            }
            List<Ticket> result = out;
            server.execute(() -> done.accept(result));
        });
    }

    private static Ticket read(ResultSet rs) throws SQLException {
        Status st;
        try { st = Status.valueOf(rs.getString("status")); } catch (Exception e) { st = Status.OPEN; }
        return new Ticket(rs.getLong("id"), UUID.fromString(rs.getString("uuid")), rs.getString("name"),
            rs.getString("message"), rs.getString("location"), st, rs.getLong("created_at"),
            rs.getString("closed_by"), rs.getString("last_reply"));
    }
}
