package com.coffeesaerosmp.auth.auth;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.DatabaseManager;

import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session tokens for offline players — survive server restarts when MySQL is available.
 * After a successful /login, a token is bound to the player's UUID + IP.
 * On reconnect from the same IP, the token is accepted and login is skipped.
 * On reconnect from a different IP, the token is invalidated and login is required.
 */
public class SessionTokenManager {

    public enum TokenStatus {
        VALID,       // token exists and IP matches — skip login
        IP_CHANGED,  // token exists but IP differs — invalidate and force login
        NONE         // no token — normal login required
    }

    private record SessionToken(String id, String ip) {}

    private final Map<UUID, SessionToken> tokens = new ConcurrentHashMap<>();
    private static final SecureRandom RNG = new SecureRandom();

    private static final long SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000; // 30 days

    private DatabaseManager db; // nullable — set after construction

    public void init(DatabaseManager db) {
        this.db = db;
        loadFromDatabase();
    }

    /** Called after every successful /login or /register flow. */
    public void createToken(UUID uuid, String ip) {
        byte[] bytes = new byte[12];
        RNG.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(uuid, new SessionToken(id, ip));
        persistSession(uuid, id, ip);
    }

    /** Check whether a reconnecting player can skip login. */
    public TokenStatus check(UUID uuid, String ip) {
        SessionToken t = tokens.get(uuid);
        if (t != null) {
            return t.ip().equals(ip) ? TokenStatus.VALID : TokenStatus.IP_CHANGED;
        }
        // Not in memory — check DB in case server restarted
        return checkDatabase(uuid, ip);
    }

    /** Removes the token — forces re-login on next join. */
    public void invalidate(UUID uuid) {
        tokens.remove(uuid);
        deleteFromDatabase(uuid);
    }

    public int activeCount() { return tokens.size(); }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private void loadFromDatabase() {
        if (db == null || !db.isAvailable()) return;
        long now = System.currentTimeMillis();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, session_id, ip_address FROM sessions WHERE expires_at > ?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        tokens.put(uuid, new SessionToken(
                            rs.getString("session_id"),
                            rs.getString("ip_address")));
                        count++;
                    } catch (Exception ignored) {}
                }
                if (count > 0) CoffeesAeroAuth.LOGGER.info("[Sessions] Loaded {} active sessions from DB.", count);
            }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Sessions] Could not load sessions from DB: {}", e.getMessage());
        }
    }

    private TokenStatus checkDatabase(UUID uuid, String ip) {
        if (db == null || !db.isAvailable()) return TokenStatus.NONE;
        long now = System.currentTimeMillis();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT session_id, ip_address FROM sessions WHERE uuid=? AND expires_at>?")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return TokenStatus.NONE;
                String storedIp = rs.getString("ip_address");
                String sessionId = rs.getString("session_id");
                TokenStatus status = storedIp.equals(ip) ? TokenStatus.VALID : TokenStatus.IP_CHANGED;
                if (status == TokenStatus.VALID) {
                    tokens.put(uuid, new SessionToken(sessionId, ip));
                }
                return status;
            }
        } catch (SQLException e) {
            return TokenStatus.NONE;
        }
    }

    private void persistSession(UUID uuid, String id, String ip) {
        if (db == null || !db.isAvailable()) return;
        long now    = System.currentTimeMillis();
        long expiry = now + SESSION_TTL_MS;
        try (Connection c = db.getConnection()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM sessions WHERE uuid=?")) {
                del.setString(1, uuid.toString());
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO sessions (session_id, uuid, ip_address, created_at, expires_at) VALUES (?,?,?,?,?)")) {
                ins.setString(1, id);
                ins.setString(2, uuid.toString());
                ins.setString(3, ip);
                ins.setLong(4, now);
                ins.setLong(5, expiry);
                ins.executeUpdate();
            }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Sessions] Could not persist session for {}: {}", uuid, e.getMessage());
        }
    }

    private void deleteFromDatabase(UUID uuid) {
        if (db == null || !db.isAvailable()) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Sessions] Could not delete session for {}: {}", uuid, e.getMessage());
        }
    }
}
