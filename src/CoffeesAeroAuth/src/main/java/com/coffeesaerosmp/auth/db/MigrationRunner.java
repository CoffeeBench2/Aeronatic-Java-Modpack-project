package com.coffeesaerosmp.auth.db;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** One-time migration: reads existing flat-file profiles and loads them into MySQL. */
public class MigrationRunner {

    private static final Gson GSON = new Gson();
    private static final Type TRUSTED_IP_MAP =
        new TypeToken<Map<String, List<String>>>(){}.getType();

    public static void runIfNeeded(DatabaseManager db, Path dataDir) {
        if (!db.isAvailable()) return;
        Path profilesDir = dataDir.resolve("profiles");
        if (!Files.exists(profilesDir)) return;

        boolean hasProfiles = false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(profilesDir, "*.json")) {
            hasProfiles = ds.iterator().hasNext();
        } catch (IOException ignored) {}
        if (!hasProfiles) return;

        CoffeesAeroAuth.LOGGER.info("[DB] Flat-file profiles detected — migrating to MySQL...");
        int migrated = 0, skipped = 0;

        try (Connection conn = db.getConnection()) {
            conn.setAutoCommit(false);

            // Migrate profiles
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(profilesDir, "*.json")) {
                for (Path f : ds) {
                    String name = f.getFileName().toString();
                    String uuidStr = name.replace(".json", "");
                    try {
                        UUID.fromString(uuidStr);
                        try (Reader r = Files.newBufferedReader(f)) {
                            JsonObject obj = GSON.fromJson(r, JsonObject.class);
                            if (obj != null) {
                                upsertFromJson(conn, uuidStr, obj);
                                migrated++;
                            } else {
                                skipped++;
                            }
                        }
                        Files.move(f, f.resolveSibling(uuidStr + ".json.migrated"),
                            StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception e) {
                        CoffeesAeroAuth.LOGGER.warn("[DB] Migration skipped {}: {}", name, e.getMessage());
                        skipped++;
                    }
                }
            }

            // Migrate trusted IPs
            Path trustedIpsFile = dataDir.resolve("trusted_ips.json");
            if (Files.exists(trustedIpsFile)) {
                try (Reader r = Files.newBufferedReader(trustedIpsFile)) {
                    Map<String, List<String>> raw = GSON.fromJson(r, TRUSTED_IP_MAP);
                    if (raw != null) {
                        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                            for (String ip : e.getValue()) {
                                try (PreparedStatement ps = conn.prepareStatement(
                                    "INSERT IGNORE INTO trusted_ips (uuid, ip_address, added_at) VALUES (?,?,?)")) {
                                    ps.setString(1, e.getKey());
                                    ps.setString(2, ip);
                                    ps.setLong(3, System.currentTimeMillis());
                                    ps.executeUpdate();
                                }
                            }
                        }
                    }
                }
                Files.move(trustedIpsFile,
                    trustedIpsFile.resolveSibling("trusted_ips.json.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            }

            // Rename name index
            Path nameIndex = dataDir.resolve("displaynames.json");
            if (Files.exists(nameIndex)) {
                Files.move(nameIndex,
                    nameIndex.resolveSibling("displaynames.json.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
            }

            conn.commit();
            CoffeesAeroAuth.LOGGER.info("[DB] Migration complete — migrated {}, skipped {}.", migrated, skipped);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[DB] Migration failed", e);
        }
    }

    private static void upsertFromJson(Connection conn, String uuid, JsonObject o) throws SQLException {
        String sql =
            "INSERT INTO players " +
            "(uuid,username,display_name,account_type,password_hash,password_salt," +
            " name_approved,first_join,last_seen,total_playtime,bio,skin_url," +
            " name_approval_pending,pending_display_name,name_rejection_count," +
            " name_changes_used,room_slot,room_created_at,first_join_complete)" +
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" +
            " ON DUPLICATE KEY UPDATE" +
            "  username=VALUES(username),display_name=VALUES(display_name)," +
            "  account_type=VALUES(account_type),password_hash=VALUES(password_hash)," +
            "  password_salt=VALUES(password_salt),name_approved=VALUES(name_approved)," +
            "  total_playtime=VALUES(total_playtime),bio=VALUES(bio),skin_url=VALUES(skin_url)," +
            "  name_approval_pending=VALUES(name_approval_pending)," +
            "  pending_display_name=VALUES(pending_display_name)," +
            "  name_rejection_count=VALUES(name_rejection_count)," +
            "  name_changes_used=VALUES(name_changes_used)," +
            "  room_slot=VALUES(room_slot),room_created_at=VALUES(room_created_at)," +
            "  first_join_complete=VALUES(first_join_complete)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            String username = str(o, "username", "unknown");
            ps.setString(1, uuid);
            ps.setString(2, username);
            ps.setString(3, str(o, "displayName", username));
            ps.setString(4, str(o, "accountType", "OFFLINE"));
            ps.setString(5, str(o, "passwordHash", null));
            ps.setString(6, str(o, "passwordSalt", null));
            ps.setBoolean(7, bool(o, "nameApproved", false));
            ps.setLong(8,    lng(o, "joinDate", System.currentTimeMillis()));
            ps.setLong(9,    System.currentTimeMillis());
            ps.setLong(10,   lng(o, "totalPlaytimeSeconds", 0));
            ps.setString(11, str(o, "bio", ""));
            ps.setString(12, str(o, "skinUrl", null));
            ps.setBoolean(13, bool(o, "nameApprovalPending", false));
            ps.setString(14, str(o, "pendingDisplayName", null));
            ps.setInt(15,    num(o, "nameRejectionCount", 0));
            ps.setInt(16,    num(o, "nameChangesUsed", 0));
            ps.setInt(17,    num(o, "roomSlot", -1));
            ps.setLong(18,   lng(o, "roomCreatedAt", 0));
            ps.setBoolean(19, bool(o, "firstJoinComplete", false));
            ps.executeUpdate();
        }
    }

    private static String  str(JsonObject o, String k, String def)  { JsonElement e = o.get(k); return (e != null && !e.isJsonNull()) ? e.getAsString()  : def; }
    private static boolean bool(JsonObject o, String k, boolean def) { JsonElement e = o.get(k); return (e != null && !e.isJsonNull()) ? e.getAsBoolean() : def; }
    private static long    lng(JsonObject o, String k, long def)     { JsonElement e = o.get(k); return (e != null && !e.isJsonNull()) ? e.getAsLong()    : def; }
    private static int     num(JsonObject o, String k, int def)      { JsonElement e = o.get(k); return (e != null && !e.isJsonNull()) ? e.getAsInt()     : def; }
}
