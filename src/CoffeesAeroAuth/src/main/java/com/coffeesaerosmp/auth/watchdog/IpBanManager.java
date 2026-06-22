package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.DatabaseManager;
import com.coffeesaerosmp.auth.util.NetUtil;

import java.sql.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class IpBanManager {

    private final Map<String, Long> bans = new ConcurrentHashMap<>(); // key → expiry epoch ms
    private final DatabaseManager   db;

    public IpBanManager(DatabaseManager db) {
        this.db = db;
    }

    /** Load active bans from DB on server startup. */
    public void initialize() {
        if (!db.isAvailable()) return;
        long now = System.currentTimeMillis();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT ip_address, expires_at FROM ip_bans WHERE expires_at > ?")) {
            ps.setLong(1, now);
            try (ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    bans.put(rs.getString("ip_address"), rs.getLong("expires_at"));
                    count++;
                }
                if (count > 0) CoffeesAeroAuth.LOGGER.info("[IpBanManager] Loaded {} active bans from DB.", count);
            }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[IpBanManager] Could not load bans from DB: {}", e.getMessage());
        }
    }

    // ── Watchdog bans ─────────────────────────────────────────────────────────

    public void ban(String ip, long durationMs) {
        long expiry = System.currentTimeMillis() + durationMs;
        bans.put(ip, expiry);
        persistBan(ip, "watchdog", expiry);
    }

    public void banSubnet(String subnetPrefix, long durationMs) {
        String key = "subnet:" + subnetPrefix;
        long expiry = System.currentTimeMillis() + durationMs;
        bans.put(key, expiry);
        persistBan(key, "watchdog-subnet", expiry);
    }

    public boolean isBanned(String ip) {
        long now = System.currentTimeMillis();
        Long expiry = bans.get(ip);
        if (expiry != null) {
            if (expiry > now) return true;
            bans.remove(ip);
        }
        Long subnetExpiry = bans.get("subnet:" + NetUtil.subnetOf(ip));
        if (subnetExpiry != null) {
            if (subnetExpiry > now) return true;
            bans.remove("subnet:" + NetUtil.subnetOf(ip));
        }
        return false;
    }

    public void clearBan(String ip) {
        bans.remove(ip);
        bans.remove("subnet:" + NetUtil.subnetOf(ip));
        deleteBanFromDb(ip);
        deleteBanFromDb("subnet:" + NetUtil.subnetOf(ip));
    }

    /** Remove a ban by its exact stored key (e.g. {@code "subnet:1.2.3"} or a plain IP). */
    public void clearByKey(String key) {
        bans.remove(key);
        deleteBanFromDb(key);
    }

    public String getBanExpiry(String ip) {
        Long expiry = bans.get(ip);
        if (expiry == null) expiry = bans.get("subnet:" + NetUtil.subnetOf(ip));
        if (expiry == null || expiry <= System.currentTimeMillis()) return null;
        long remaining = (expiry - System.currentTimeMillis()) / 1000;
        return remaining + "s remaining";
    }

    // ── Auth bans (failed login attempts) — in-memory only, short-lived ───────

    public void banAuth(String ip, long durationMs) {
        bans.put("auth_ban:" + ip, System.currentTimeMillis() + durationMs);
    }

    public boolean isAuthBanned(String ip) {
        long now = System.currentTimeMillis();
        Long expiry = bans.get("auth_ban:" + ip);
        if (expiry == null) return false;
        if (expiry > now) return true;
        bans.remove("auth_ban:" + ip);
        return false;
    }

    public long getAuthRemainingSeconds(String ip) {
        Long expiry = bans.get("auth_ban:" + ip);
        if (expiry == null) return 0;
        return Math.max(0, (expiry - System.currentTimeMillis()) / 1000);
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private void persistBan(String ip, String reason, long expiry) {
        if (!db.isAvailable()) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ip_bans (ip_address, reason, banned_at, expires_at) VALUES (?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE reason=VALUES(reason), banned_at=VALUES(banned_at), expires_at=VALUES(expires_at)")) {
            ps.setString(1, ip);
            ps.setString(2, reason);
            ps.setLong(3, System.currentTimeMillis());
            ps.setLong(4, expiry);
            ps.executeUpdate();
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[IpBanManager] Could not persist ban for {}: {}", ip, e.getMessage());
        }
    }

    private void deleteBanFromDb(String ip) {
        if (!db.isAvailable()) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ip_bans WHERE ip_address=?")) {
            ps.setString(1, ip);
            ps.executeUpdate();
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[IpBanManager] Could not delete ban for {}: {}", ip, e.getMessage());
        }
    }
}
