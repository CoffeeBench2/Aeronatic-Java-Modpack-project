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

    /**
     * 🔴 Flush ONLY profiles whose own save() has not reached MySQL yet — never the whole cache.
     *
     * <p>Until 1.13.15 this upserted EVERY cached profile. The lobby and the SMP each cache all 429
     * rows at boot and share one database, so whichever process stopped last wrote its BOOT-TIME
     * snapshot over everything the other had changed since. Proven 2026-10-07: the lobby booted at
     * 19:43, the S3 reward pass re-armed 256 starter kits at 19:56, and the lobby's 03:46 shutdown
     * "Flushed 429 profiles" put all 256 back to given. The same path silently rolls back playtime,
     * flags and names. Every save() already writes its own row (failures go to the DB retry queue +
     * flat file), and AsyncIo is drained before this runs, so the only rows owed here are saves still
     * in flight — tracked by {@link #pendingWrites}.
     */
    public void shutdown() {
        if (db.isAvailable()) {
            int written = 0;
            for (PlayerProfile p : cache.values()) {
                if (!pendingWrites.containsKey(p.getUUID())) continue;   // already written by its save()
                try (Connection c = db.getConnection()) {
                    upsertPlayer(c, p);
                    written++;
                } catch (SQLException e) {
                    CoffeesAeroAuth.LOGGER.warn("Shutdown flush failed for {}, writing flat file", p.getUUID(), e);
                    writeProfileFallback(p);
                }
            }
            CoffeesAeroAuth.LOGGER.info("[ProfileStore] Flushed {} unsaved profile(s) to MySQL on shutdown "
                + "(of {} cached; the rest were already written by their own save).", written, cache.size());
        } else {
            cache.values().forEach(this::writeProfileFallback);
            writeNameIndexToFile();
            CoffeesAeroAuth.LOGGER.info("[ProfileStore] Flushed {} profiles to flat files on shutdown.", cache.size());
        }
    }

    // ── Profile CRUD ──────────────────────────────────────────────────────────

    public GetOrCreateResult getOrCreate(UUID uuid, String username, PlayerProfile.AccountType type) {
        // 🔴 Re-read this ONE row from MySQL before deciding anything about the joining player.
        //
        // The lobby and the SMP are separate processes sharing one database, and each caches EVERY
        // profile at boot (initialize -> loadAllFromDatabase) with no cross-process invalidation
        // anywhere. save() then writes all 26 columns from its in-memory copy. So a process that
        // booted before a change happened elsewhere will happily clobber the newer row with its own
        // stale snapshot — last writer wins, whole row.
        //
        // That is not theoretical. It is why new players were welcomed "for the first time" over and
        // over: the lobby set first_join_complete=true, the SMP's stale copy wrote false back, and
        // the next join looked like a first join again. startup_bonus_given rides in the same row,
        // so the same revert made the SMP pay the starter bonus a second time — real currency, and
        // every log line about it looks legitimate. total_playtime and last_seen can go backwards
        // the same way.
        //
        // One indexed primary-key SELECT per join, against a database that now lives on the game
        // host. This is the join path and the no-blocking-DB rule applies to it — but get() already
        // hits MySQL on any cache miss, so this adds a read that was always possible, and the
        // alternative is paying players twice.
        refreshFromDatabase(uuid);
        PlayerProfile existing = get(uuid);
        if (existing != null) {
            existing.username = username;
            return new GetOrCreateResult(existing, false);
        }
        PlayerProfile fresh = new PlayerProfile(uuid, username, type);
        cache.put(uuid, fresh);
        return new GetOrCreateResult(fresh, true);
    }

    /**
     * True when the profile table itself is reachable, so a null from {@link #get} really does mean
     * "no such player" rather than "could not look".
     *
     * <p>Callers that make a decision out of a player's ABSENCE need this. {@link #get} falls back to
     * the flat file when the DB is down, which covers most returning players but not one who has no
     * local file — they come back null and look brand new. Anything that would refuse or reset a
     * player on that basis must check here first and stand down while the DB is unavailable.
     */
    public boolean isBacked() {
        return db.isAvailable();
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
        final UUID id = profile.getUUID();
        pendingWrites.merge(id, 1, Integer::sum);  // owed until its async write below has run

        // The MySQL round-trip runs OFF the server thread (1.6.12) — a remote-DB latency spike used
        // to stall the tick on every login/leave/name-change. Single writer thread keeps ordering.
        final PlayerProfile snapshot = profile;
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            try {
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
            } finally {
                // Handled either way (written, or in the DB retry queue + flat file): no longer owed.
                pendingWrites.computeIfPresent(id, (k, n) -> n <= 1 ? null : n - 1);
            }
        });
    }

    /** uuid -> saves submitted but not yet run. Shutdown flushes only these (see {@link #shutdown}). */
    private final Map<UUID, Integer> pendingWrites = new ConcurrentHashMap<>();

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

    /**
     * Drop a uuid from the in-memory cache so the next {@link #get} re-reads it.
     *
     * <p>Exists for {@code AccountTransfer}, which re-keys a row in MySQL behind the store's back.
     * Reads are cache-first, so without this the old uuid would keep answering from memory and the
     * new one would look like it does not exist — for the rest of the server's uptime.
     */
    /**
     * Replaces one cached profile with the current database row, if and only if that row can be
     * read right now.
     *
     * <h3>🔴 Why this never evicts on failure</h3>
     * The obvious implementation — {@code evict(uuid)} and let {@link #get} re-read — is dangerous.
     * If MySQL is momentarily unavailable, the evict succeeds, {@code get} finds nothing, and
     * {@code getOrCreate} mints a BRAND-NEW empty profile over a real player's account: no password,
     * no display name, {@code startup_bonus_given=false}. The next save would persist that blank.
     *
     * <p>So the order is inverted: read first, and only replace the cache entry once a real row is
     * in hand. Every failure path — database down, read throws, row genuinely absent — leaves the
     * existing cached copy exactly as it was. A stale profile is a bug; a destroyed profile is not
     * recoverable.
     *
     * <p>A missing row is NOT treated as a failure to report, because it is the normal case for a
     * player who has never been saved yet.
     */
    public void refreshFromDatabase(UUID uuid) {
        if (uuid == null || !db.isAvailable()) return;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM players WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    cache.put(uuid, fromResultSet(rs));   // only now is it safe to displace the cache
                }
            }
        } catch (SQLException e) {
            // Keep whatever is cached. See the javadoc: losing it is worse than it being stale.
            CoffeesAeroAuth.LOGGER.warn("[ProfileStore] join refresh failed for {} — keeping the "
                + "cached copy: {}", uuid, e.getMessage());
        }
    }

    public void evict(UUID uuid) {
        if (uuid != null) cache.remove(uuid);
    }

    /** Flat-file location for a profile. The fallback store is {@code profiles/<uuid>.json}. */
    public Path profileFile(UUID uuid) {
        return profilesDir.resolve(uuid + ".json");
    }

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
    /** Reverse of the /link flow: Discord snowflake → profile. All profiles are cached at boot, so
     *  this resolves OFFLINE players too — which is the point, since someone typing in Discord is
     *  usually not in-game. Linear over the cache (75 profiles); called once per bridged message. */
    public PlayerProfile findByDiscordId(String discordId) {
        if (discordId == null || discordId.isBlank()) return null;
        for (PlayerProfile p : cache.values()) {
            if (discordId.equals(p.discordId)) return p;
        }
        return null;
    }

    /**
     * Resolve a profile by display name or account name.
     *
     * <p>DETERMINISTIC SINCE 2026-08-08. The old loop returned the FIRST match from a
     * {@link ConcurrentHashMap} iteration. When one player owns two profiles — the real Mojang
     * (v4) UUID and the {@code md5("OfflinePlayer:"+name)} (v3) alias minted whenever their gate
     * cookie fails — the winner was whatever order the map happened to iterate, and it could change
     * between restarts. That is why SideBlackStar's Discord card read "0h 34m" while all their
     * hours sat on the other record.
     *
     * <p>Order now: exact display-name index → PREMIUM/v4 profiles → highest playtime → most
     * recently seen. Playtime breaks the tie rather than account type alone because a player can
     * legitimately own two premium-flagged rows once the alias has been logged into.
     */
    public PlayerProfile findByAnyName(String name) {
        if (name == null || name.isBlank()) return null;
        UUID byDisplay = nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (byDisplay != null) {
            PlayerProfile p = get(byDisplay);
            if (p != null) return p;
        }
        return matchesByName(name).stream()
            .max(Comparator
                .comparing(PlayerProfile::isPremium)
                .thenComparingLong(p -> p.totalPlaytimeSeconds)
                .thenComparingLong(p -> p.lastSeen))
            .orElse(null);
    }

    /** Every cached profile whose account name or display name matches, case-insensitively. */
    public List<PlayerProfile> matchesByName(String name) {
        List<PlayerProfile> out = new ArrayList<>();
        if (name == null || name.isBlank()) return out;
        for (PlayerProfile p : cache.values()) {
            if (name.equalsIgnoreCase(p.username) || name.equalsIgnoreCase(p.displayName)) out.add(p);
        }
        return out;
    }

    /**
     * Account names owned by more than one profile — i.e. a player split across their real Mojang
     * UUID and an offline alias. Each list is ordered by {@link #findByAnyName}'s preference, so
     * element 0 is the record that should be treated as canonical.
     */
    public Map<String, List<PlayerProfile>> findDuplicateAccounts() {
        Map<String, List<PlayerProfile>> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (PlayerProfile p : cache.values()) {
            if (p.username == null || p.username.isBlank()) continue;
            byName.computeIfAbsent(p.username, k -> new ArrayList<>()).add(p);
        }
        byName.values().removeIf(l -> l.size() < 2);
        Comparator<PlayerProfile> best = Comparator
            .comparing(PlayerProfile::isPremium)
            .thenComparingLong(p -> p.totalPlaytimeSeconds)
            .thenComparingLong(p -> p.lastSeen);
        byName.values().forEach(l -> l.sort(best.reversed()));
        return byName;
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
        " name_changes_used,first_join_complete,startup_bonus_given," +
        " first_ip,cape_enabled,return_dim,return_x,return_y,return_z,skin_changes_used,discord_id)" +
        " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)" +
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
            ps.setBoolean(17, p.firstJoinComplete);
            ps.setBoolean(18, p.startupBonusGiven);
            ps.setString(19, p.firstIp);   // only persisted on initial INSERT (not in ON DUPLICATE UPDATE)
            ps.setBoolean(20, p.capeEnabled);
            ps.setString(21, p.returnDim);
            ps.setDouble(22, p.returnX);
            ps.setDouble(23, p.returnY);
            ps.setDouble(24, p.returnZ);
            ps.setInt(25, p.skinChangesUsed);
            ps.setString(26, p.discordId);
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
        // Defensive: SeasonMigration adds this column before ProfileStore.initialize(), but a DB
        // where the ALTER failed must NOT throw here — fromResultSet throwing takes down the whole
        // bulk load and produces "Loaded 0 profiles" (the 1.7.19 socketTimeout failure mode).
        p.season1PlaytimeSeconds = optLong(rs, "season1_playtime");
        p.bio                  = rs.getString("bio");
        p.skinUrl              = rs.getString("skin_url");
        p.capeEnabled          = rs.getBoolean("cape_enabled");
        p.nameApprovalPending  = rs.getBoolean("name_approval_pending");
        p.pendingDisplayName   = rs.getString("pending_display_name");
        p.nameRejectionCount   = rs.getInt("name_rejection_count");
        p.nameChangesUsed      = rs.getInt("name_changes_used");
        p.firstJoinComplete    = rs.getBoolean("first_join_complete");
        p.startupBonusGiven    = rs.getBoolean("startup_bonus_given");
        p.returnDim            = rs.getString("return_dim");
        p.returnX              = rs.getDouble("return_x");
        p.returnY              = rs.getDouble("return_y");
        p.returnZ              = rs.getDouble("return_z");
        p.skinChangesUsed      = rs.getInt("skin_changes_used");
        p.discordId            = rs.getString("discord_id");
        p.mojangLink           = optString(rs, "mojang_uuid");
        p.linkSource           = optString(rs, "link_source");
        p.identityHold         = optString(rs, "identity_hold");
        p.seasonStartPlaytime  = optLong(rs, "season_start_playtime");
        return p;
    }

    /** Reads a string column, returning null if the column is absent rather than failing the whole row. */
    private static String optString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException missingColumn) {
            return null;
        }
    }

    /** Reads a long column, returning 0 if the column is absent rather than failing the whole row. */
    private static long optLong(ResultSet rs, String column) {
        try {
            return rs.getLong(column);
        } catch (SQLException missingColumn) {
            return 0L;
        }
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
