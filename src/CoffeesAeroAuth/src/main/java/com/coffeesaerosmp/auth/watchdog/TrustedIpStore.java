package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.DatabaseManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TrustedIpStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, List<String>>>(){}.getType();

    private final Path            fallbackFile;
    private final DatabaseManager db;
    private final Map<UUID, List<String>> store = new ConcurrentHashMap<>();

    public TrustedIpStore(Path dataDir, DatabaseManager db) {
        this.fallbackFile = dataDir.resolve("trusted_ips.json");
        this.db           = db;
    }

    public void initialize() {
        if (db.isAvailable()) {
            loadFromDatabase();
        } else {
            loadFromFile();
        }
    }

    public boolean isKnownIp(UUID uuid, String ip) {
        List<String> ips = store.get(uuid);
        return ips != null && ips.contains(ip);
    }

    public void addTrustedIp(UUID uuid, String ip) {
        store.compute(uuid, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            if (!list.contains(ip)) {
                list.add(ip);
                int max = AuthConfig.TRUSTED_IP_MAX_COUNT.get();
                while (list.size() > max) list.remove(0);
            }
            return list;
        });

        // In-memory map above is authoritative for reads; the DB round-trip goes off-thread
        // (1.6.12 — this ran on the server thread inside every offline login).
        long now = System.currentTimeMillis();
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            if (db.isAvailable()) {
                try (Connection c = db.getConnection()) {
                    upsertIp(c, uuid, ip, now);
                    pruneOldIps(c, uuid);
                    return;
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[TrustedIp] DB write failed, queuing", e);
                }
            }
            db.queueWrite(conn -> { upsertIp(conn, uuid, ip, now); pruneOldIps(conn, uuid); });
            saveToFile();
        });
    }

    public List<String> getTrustedIps(UUID uuid) {
        return Collections.unmodifiableList(store.getOrDefault(uuid, List.of()));
    }

    public void clearIps(UUID uuid) {
        store.remove(uuid);
        if (db.isAvailable()) {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM trusted_ips WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                CoffeesAeroAuth.LOGGER.warn("[TrustedIp] DB delete failed for {}", uuid, e);
            }
        } else {
            saveToFile();
        }
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private void loadFromDatabase() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, ip_address FROM trusted_ips ORDER BY added_at ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID   uuid = UUID.fromString(rs.getString("uuid"));
                    String ip   = rs.getString("ip_address");
                    store.computeIfAbsent(uuid, k -> new ArrayList<>()).add(ip);
                } catch (Exception ignored) {}
            }
            CoffeesAeroAuth.LOGGER.info("[TrustedIp] Loaded trusted IPs from MySQL.");
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.error("[TrustedIp] Failed to load from DB", e);
        }
    }

    private void upsertIp(Connection c, UUID uuid, String ip, long addedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT IGNORE INTO trusted_ips (uuid, ip_address, added_at) VALUES (?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, ip);
            ps.setLong(3, addedAt);
            ps.executeUpdate();
        }
    }

    private void pruneOldIps(Connection c, UUID uuid) throws SQLException {
        int max = AuthConfig.TRUSTED_IP_MAX_COUNT.get();
        try (PreparedStatement ps = c.prepareStatement(
            "DELETE FROM trusted_ips WHERE uuid=? AND ip_address NOT IN (" +
            "  SELECT ip_address FROM (" +
            "    SELECT ip_address FROM trusted_ips WHERE uuid=? ORDER BY added_at DESC LIMIT ?" +
            "  ) t" +
            ")")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            ps.setInt(3, max);
            ps.executeUpdate();
        }
    }

    // ── Flat-file fallback ────────────────────────────────────────────────────

    private void loadFromFile() {
        if (!Files.exists(fallbackFile)) return;
        try (Reader r = Files.newBufferedReader(fallbackFile)) {
            Map<String, List<String>> raw = GSON.fromJson(r, MAP_TYPE);
            if (raw != null) {
                raw.forEach((uuidStr, ips) -> {
                    try { store.put(UUID.fromString(uuidStr), new ArrayList<>(ips)); }
                    catch (IllegalArgumentException ignored) {}
                });
            }
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("[TrustedIp] Failed to load fallback file", e);
        }
    }

    private void saveToFile() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        store.forEach((uuid, ips) -> raw.put(uuid.toString(), ips));
        try (Writer w = Files.newBufferedWriter(fallbackFile)) {
            GSON.toJson(raw, w);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("[TrustedIp] Failed to save fallback file", e);
        }
    }
}
