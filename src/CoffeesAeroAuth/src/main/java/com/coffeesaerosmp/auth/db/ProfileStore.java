package com.coffeesaerosmp.auth.db;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProfileStore implements CredentialStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path               dataDir;
    private final Path               profilesDir;
    private final Path               nameIndexFile;
    private final DatabaseManager    db;

    private final Map<UUID, PlayerProfile> cache     = new ConcurrentHashMap<>();
    /** lower-cased display name → owner UUID */
    private final Map<String, UUID>        nameIndex = new ConcurrentHashMap<>();

    public ProfileStore(Path dataDir, DatabaseManager db) {
        this.dataDir       = dataDir;
        this.profilesDir   = dataDir.resolve("profiles");
        this.nameIndexFile = dataDir.resolve("displaynames.json");
        this.db            = db;
    }

    public void initialize() {
        try {
            Files.createDirectories(profilesDir);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("ProfileStore: could not create data directory", e);
        }

        if (db.isAvailable()) {
            loadAllFromDatabase();
            CoffeesAeroAuth.LOGGER.info("[ProfileStore] Loaded {} profiles from MySQL.", cache.size());
        } else {
            loadNameIndexFromFile();
            CoffeesAeroAuth.LOGGER.warn("[ProfileStore] MySQL unavailable — using flat-file fallback.");
        }
    }

    public void shutdown() {
        if (db.isAvailable()) {
            int written = 0;
            for (PlayerProfile p : cache.values()) {
                try (Connection c = db.getConnection()) {
                    upsertPlayer(c, p);
                    written++;
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("Shutdown flush failed for {}, writing flat file", p.getUUID(), e);
                    writeProfileFallback(p);
                }
            }
            CoffeesAeroAuth.LOGGER.info("[ProfileStore] Flushed {} profiles to MySQL on shutdown.", written);
        } else {
            cache.values().forEach(this::writeProfileFallback);
            writeNameIndexToFile();
            CoffeesAeroAuth.LOGGER.info("[ProfileStore] Flushed {} profiles to flat files on shutdown.", cache.size());
        }
    }

    // ── Profile CRUD ──────────────────────────────────────────────────────────

    public GetOrCreateResult getOrCreate(UUID uuid, String username, PlayerProfile.AccountType type) {
        PlayerProfile existing = get(uuid);
        if (existing != null) {
            existing.username = username;
            return new GetOrCreateResult(existing, false);
        }
        PlayerProfile fresh = new PlayerProfile(uuid, username, type);
        cache.put(uuid, fresh);
        return new GetOrCreateResult(fresh, true);
    }

    public PlayerProfile get(UUID uuid) {
        PlayerProfile cached = cache.get(uuid);
        if (cached != null) return cached;

        if (db.isAvailable()) {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT * FROM players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        PlayerProfile p = fromResultSet(rs);
                        cache.put(uuid, p);
                        return p;
                    }
                }
            } catch (SQLException e) {
                CoffeesAeroAuth.LOGGER.warn("[ProfileStore] DB read failed for {}, trying flat file", uuid, e);
            }
        }

        return loadProfileFromFile(uuid);
    }

    public void save(PlayerProfile profile) {
        cache.put(profile.getUUID(), profile);   // reads are cache-first, so callers see this instantly

        // The MySQL round-trip runs OFF the server thread (1.6.12) — a remote-DB latency spike used
        // to stall the tick on every login/leave/name-change. Single writer thread keeps ordering.
        final PlayerProfile snapshot = profile;
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            if (db.isAvailable()) {
                try (Connection c = db.getConnection()) {
                    upsertPlayer(c, snapshot);
                    return;
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("[ProfileStore] DB write failed, queuing for retry", e);
                }
            }
            db.queueWrite(conn -> upsertPlayer(conn, snapshot));
            writeProfileFallback(snapshot);
        });
    }

    /** Returns all profiles. When DB is up: from cache (fully loaded on startup).
     *  When fallback: scans flat files to populate cache. */
    public Collection<PlayerProfile> getAll() {
        if (!db.isAvailable()) {
            try {
                Files.list(profilesDir).forEach(f -> {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".json")) return;
                    try { get(UUID.fromString(name.replace(".json", ""))); }
                    catch (Exception ignored) {}
                });
            } catch (IOException ignored) {}
        }
        return new ArrayList<>(cache.values());
    }

    /** Exposed for WatchdogManager fallback scan. */
    public Path profilesDir() { return profilesDir; }

    // ── Display name index ────────────────────────────────────────────────────

    public boolean isDisplayNameTaken(String name) {
        return nameIndex.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public UUID getDisplayNameOwner(String name) {
        return nameIndex.get(name.toLowerCase(Locale.ROOT));
    }

    public void registerDisplayName(String name, UUID uuid) {
        nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        if (!db.isAvailable()) writeNameIndexToFile();
    }

    public void releaseDisplayName(String name) {
        if (name == null) return;
        nameIndex.remove(name.toLowerCase(Locale.ROOT));
        if (!db.isAvailable()) writeNameIndexToFile();
    }

    /** Admin lookup by display name OR Minecraft username, case-insensitive — works for OFFLINE
     *  players (all profiles are cached at boot). Display-name index first (O(1)), then a username
     *  scan. Used by /authmod resetpassword + /authmod player. */
    public PlayerProfile findByAnyName(String name) {
        if (name == null || name.isBlank()) return null;
        UUID byDisplay = nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (byDisplay != null) {
            PlayerProfile p = get(byDisplay);
            if (p != null) return p;
        }
        for (PlayerProfile p : cache.values()) {
            if (name.equalsIgnoreCase(p.username) || name.equalsIgnoreCase(p.displayName)) return p;
        }
        return null;
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    private void loadAllFromDatabase() {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM players");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                PlayerProfile p = fromResultSet(rs);
                cache.put(p.getUUID(), p);
                if (p.displayName != null) {
                    nameIndex.put(p.displayName.toLowerCase(Locale.ROOT), p.getUUID());
                }
            }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.error("[ProfileStore] Failed to load profiles from DB", e);
        }
    }

    private static final String UPSERT_SQL =
        "INSERT INTO players " +
        "(uuid,username,display_name,account_type,password_hash,password_salt," +
        " name_approved,first_join,last_seen,total_playtime,bio,skin_url," +
        " name_approval_pending,pending_display_name,name_rejection_count," +
        " name_changes_used,room_slot,room_created_at,first_join_complete,startup_bonus_given," +
        " first_ip,cape_enabled,return_dim,return_x,return_y,return_z,skin_changes_used,discord_id)" +
        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" +
        " ON DUPLICATE KEY UPDATE" +
        "  username=VALUES(username),display_name=VALUES(display_name)," +
        "  account_type=VALUES(account_type),password_hash=VALUES(password_hash)," +
        "  password_salt=VALUES(password_salt),name_approved=VALUES(name_approved)," +
        "  last_seen=VALUES(last_seen),total_playtime=VALUES(total_playtime)," +
        "  bio=VALUES(bio),skin_url=VALUES(skin_url)," +
        "  name_approval_pending=VALUES(name_approval_pending)," +
        "  pending_display_name=VALUES(pending_display_name)," +
        "  name_rejection_count=VALUES(name_rejection_count)," +
        "  name_changes_used=VALUES(name_changes_used)," +
        "  room_slot=VALUES(room_slot),room_created_at=VALUES(room_created_at)," +
        "  first_join_complete=VALUES(first_join_complete)," +
        "  startup_bonus_given=VALUES(startup_bonus_given)," +
        "  cape_enabled=VALUES(cape_enabled)," +
        "  return_dim=VALUES(return_dim),return_x=VALUES(return_x)," +
        "  return_y=VALUES(return_y),return_z=VALUES(return_z)," +
        "  skin_changes_used=VALUES(skin_changes_used)," +
        "  discord_id=VALUES(discord_id)";

    private void upsertPlayer(Connection c, PlayerProfile p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPSERT_SQL)) {
            ps.setString(1,  p.getUUID().toString());
            ps.setString(2,  p.username);
            ps.setString(3,  p.displayName != null ? p.displayName : p.username);
            ps.setString(4,  p.accountType);
            ps.setString(5,  p.passwordHash);
            ps.setString(6,  p.passwordSalt);
            ps.setBoolean(7, p.nameApproved);
            ps.setLong(8,    p.joinDate);
            // last_seen: write the FIELD, not "now". Every save used to stamp currentTimeMillis(),
            // which was harmless while nothing read the column — but lastSeen is now the clock for
            // the lobby-bypass rule, and a save that happens while the player is OFFLINE (an admin
            // approving a name, a Discord link, a watchdog write) would have reset their "last seen"
            // to now and let them skip the lobby on their next join. AuthManager stamps the field on
            // logout; 0 only for a profile that has never logged out, where "now" is correct.
            ps.setLong(9,    p.lastSeen > 0 ? p.lastSeen : System.currentTimeMillis());
            ps.setLong(10,   p.totalPlaytimeSeconds);
            ps.setString(11, p.bio != null ? p.bio : "");
            ps.setString(12, p.skinUrl);
            ps.setBoolean(13, p.nameApprovalPending);
            ps.setString(14, p.pendingDisplayName);
            ps.setInt(15,    p.nameRejectionCount);
            ps.setInt(16,    p.nameChangesUsed);
            ps.setInt(17,    p.roomSlot);
            ps.setLong(18,   p.roomCreatedAt);
            ps.setBoolean(19, p.firstJoinComplete);
            ps.setBoolean(20, p.startupBonusGiven);
            ps.setString(21, p.firstIp);   // only persisted on initial INSERT (not in ON DUPLICATE UPDATE)
            ps.setBoolean(22, p.capeEnabled);
            ps.setString(23, p.returnDim);
            ps.setDouble(24, p.returnX);
            ps.setDouble(25, p.returnY);
            ps.setDouble(26, p.returnZ);
            ps.setInt(27, p.skinChangesUsed);
            ps.setString(28, p.discordId);
            ps.executeUpdate();
        }
    }

    private PlayerProfile fromResultSet(ResultSet rs) throws SQLException {
        PlayerProfile p = new PlayerProfile();
        p.uuidStr              = rs.getString("uuid");
        p.uuid                 = UUID.fromString(p.uuidStr);
        p.username             = rs.getString("username");
        p.displayName          = rs.getString("display_name");
        p.accountType          = rs.getString("account_type");
        p.passwordHash         = rs.getString("password_hash");
        p.passwordSalt         = rs.getString("password_salt");
        p.nameApproved         = rs.getBoolean("name_approved");
        p.joinDate             = rs.getLong("first_join");
        p.firstIp              = rs.getString("first_ip");
        p.lastSeen             = rs.getLong("last_seen");
        p.totalPlaytimeSeconds = rs.getLong("total_playtime");
        p.bio                  = rs.getString("bio");
        p.skinUrl              = rs.getString("skin_url");
        p.capeEnabled          = rs.getBoolean("cape_enabled");
        p.nameApprovalPending  = rs.getBoolean("name_approval_pending");
        p.pendingDisplayName   = rs.getString("pending_display_name");
        p.nameRejectionCount   = rs.getInt("name_rejection_count");
        p.nameChangesUsed      = rs.getInt("name_changes_used");
        p.roomSlot             = rs.getInt("room_slot");
        p.roomCreatedAt        = rs.getLong("room_created_at");
        p.firstJoinComplete    = rs.getBoolean("first_join_complete");
        p.startupBonusGiven    = rs.getBoolean("startup_bonus_given");
        p.returnDim            = rs.getString("return_dim");
        p.returnX              = rs.getDouble("return_x");
        p.returnY              = rs.getDouble("return_y");
        p.returnZ              = rs.getDouble("return_z");
        p.skinChangesUsed      = rs.getInt("skin_changes_used");
        p.discordId            = rs.getString("discord_id");
        return p;
    }

    // ── Flat-file fallback ────────────────────────────────────────────────────

    private void writeProfileFallback(PlayerProfile p) {
        try (Writer w = Files.newBufferedWriter(profilesDir.resolve(p.getUUID() + ".json"))) {
            GSON.toJson(p, w);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to write fallback profile {}", p.getUUID(), e);
        }
    }

    private PlayerProfile loadProfileFromFile(UUID uuid) {
        Path f = profilesDir.resolve(uuid + ".json");
        if (!Files.exists(f)) return null;
        try (Reader r = Files.newBufferedReader(f)) {
            PlayerProfile p = GSON.fromJson(r, PlayerProfile.class);
            if (p != null) {
                p.uuid = uuid;
                cache.put(uuid, p);
            }
            return p;
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to read fallback profile {}", uuid, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void loadNameIndexFromFile() {
        if (!Files.exists(nameIndexFile)) return;
        try (Reader r = Files.newBufferedReader(nameIndexFile)) {
            Map<String, String> raw = GSON.fromJson(r, Map.class);
            if (raw != null) raw.forEach((k, v) -> nameIndex.put(k, UUID.fromString(v)));
            CoffeesAeroAuth.LOGGER.info("[ProfileStore] Loaded {} display names from flat file.", nameIndex.size());
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to load display name index", e);
        }
    }

    private void writeNameIndexToFile() {
        Map<String, String> raw = new LinkedHashMap<>();
        nameIndex.forEach((k, v) -> raw.put(k, v.toString()));
        try (Writer w = Files.newBufferedWriter(nameIndexFile)) {
            GSON.toJson(raw, w);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to save display name index", e);
        }
    }
}
