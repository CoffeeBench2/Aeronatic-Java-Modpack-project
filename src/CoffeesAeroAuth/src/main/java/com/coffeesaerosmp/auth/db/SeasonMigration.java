package com.coffeesaerosmp.auth.db;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot per-season migration of the shared player database across a WORLD RESET.
 *
 * <p><b>Why this exists.</b> The MySQL {@code players} table survives a world reset — it is external
 * to the world folder. That is what lets Season 1 identity, passwords, approved names and playtime
 * carry over. But several columns describe the OLD WORLD and become actively harmful once it is
 * gone:
 *
 * <ul>
 *   <li>{@code return_dim / return_x/y/z} — where the player logged off. {@code AuthManager}'s
 *       {@code /spawn} teleports straight to them, so a stale value drops a returning player at
 *       Season 1 coordinates in brand-new terrain: inside rock, over ocean, or thousands of blocks
 *       from spawn. Cleared, they fall through to world spawn, which is correct.</li>
 *   <li>{@code room_slot / room_created_at} — a lobby room built in a world that no longer exists.
 *       {@code -1} makes the room manager assign a fresh one.</li>
 * </ul>
 *
 * <p><b>Why in the mod and not a .sql script.</b> {@link ProfileStore} is cache-first and loads every
 * profile once at boot; anything changed in MySQL while the server is up is overwritten by the next
 * bank. A hand-run script therefore has to be sequenced against a stopped server, and a re-run at the
 * wrong moment would snapshot Season 2 playtime as if it were Season 1. Running here — after the
 * schema exists, before profiles are loaded — removes both hazards.
 *
 * <p><b>Idempotency.</b> Every profile carries a {@code season} column. A row is migrated only when
 * its {@code season} is below {@link #CURRENT_SEASON}, and the same statement that migrates it also
 * stamps the new value. Re-running is a no-op, and a player who never logs into Season 2 is still
 * migrated because this works on the table, not on logins.
 *
 * <p><b>TWO PASSES, STAMPED SEPARATELY -- this matters.</b> The player database is SHARED between the
 * live server and any test/creative server. That makes the two halves of a season rollover carry very
 * different risk:
 *
 * <ul>
 *   <li><b>World-data pass</b> ({@code seasonClearWorldData}, stamped in {@code season}) clears old
 *       positions and lobby rooms. It grants nothing, so running it on a test server is harmless.</li>
 *   <li><b>Reward pass</b> ({@code seasonGrantRewards}, stamped in {@code season_rewarded}) re-arms
 *       the starter spurs and enables the veteran reward. If a test server ran this, the first
 *       veteran to join it would collect there and be marked paid -- and would receive NOTHING at the
 *       real launch. It therefore defaults to FALSE, and is turned on only on the live season world.</li>
 * </ul>
 *
 * <p>Two separate stamp columns are what make that work: running the world pass on a test server does
 * not consume the reward pass, so enabling rewards later on the live server still fires for everyone.
 */
public final class SeasonMigration {

    private SeasonMigration() {}

    /** Bump this for Season 3 and the same migration runs again, once. */
    public static final int CURRENT_SEASON = 2;

    /** Seconds of Season 1 playtime required to count as a veteran (1 hour). */
    private static final long VETERAN_SECONDS = 3600L;

    /**
     * Veterans who have not yet collected, loaded ONCE at boot: uuid -> Season 1 seconds.
     *
     * <p>Held in memory on purpose. The alternative — querying MySQL on the join path — is a 234 ms
     * cross-continent round trip on the main thread, which is precisely the blocking pattern that
     * cost ~67 s of a 104 s boot. This table is ~119 rows; loading it once costs one query and the
     * join path then costs nothing.
     */
    private static final Map<UUID, Long> UNCLAIMED = new ConcurrentHashMap<>();

    /** Season 1 seconds for a veteran who still owes a reward, or {@code null}. */
    public static Long pendingRewardFor(UUID uuid) {
        return uuid == null ? null : UNCLAIMED.get(uuid);
    }

    /** Marks collected: drops it from memory and persists the flag off-thread. */
    public static void markClaimed(UUID uuid, DatabaseManager db) {
        if (uuid == null || UNCLAIMED.remove(uuid) == null) return;
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            try (Connection c = db.getConnection();
                 java.sql.PreparedStatement ps = c.prepareStatement(
                     "UPDATE players SET season1_reward_claimed = TRUE WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                CoffeesAeroAuth.LOGGER.warn("[Season] could not persist reward claim for {}", uuid, e);
            }
        });
    }

    /**
     * Runs the migration if needed. Safe to call on every boot.
     *
     * <p>Called BEFORE {@code ProfileStore.initialize()} so the cache loads already-migrated rows —
     * otherwise the in-memory copies would still hold the old positions and write them straight back.
     */
    public static void run(DatabaseManager db) {
        if (db == null || !db.isAvailable()) {
            CoffeesAeroAuth.LOGGER.warn("[Season] DB is down - migration skipped this boot. "
                + "It will run on a later boot; nothing is lost.");
            return;
        }
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {

            // MySQL has no ADD COLUMN IF NOT EXISTS (see DatabaseManager.createSchema) - add and
            // swallow the duplicate-column error, exactly as the rest of the schema does.
            addColumn(s, "season                 INT     NOT NULL DEFAULT 1");
            addColumn(s, "season_rewarded        INT     NOT NULL DEFAULT 1");
            addColumn(s, "season1_playtime       BIGINT  NOT NULL DEFAULT 0");
            addColumn(s, "season1_veteran        BOOLEAN NOT NULL DEFAULT FALSE");
            addColumn(s, "season1_reward_claimed BOOLEAN NOT NULL DEFAULT FALSE");

            worldDataPass(s);
            rewardPass(s);

        } catch (SQLException e) {
            // Never fatal. A failed migration means stale return positions, which /authmod resetpos
            // can fix per player - far better than refusing to boot the server.
            CoffeesAeroAuth.LOGGER.error("[Season] Migration failed - continuing boot. "
                + "Returning players may land at Season 1 coordinates until this is re-run.", e);
        }
    }

    /**
     * Clears data that describes the OLD world, and freezes the season's playtime. Grants nothing,
     * so this is safe to leave on for a test server sharing the database.
     */
    private static void worldDataPass(Statement s) throws SQLException {
        if (!AuthConfig.SEASON_CLEAR_WORLD_DATA.get()) {
            CoffeesAeroAuth.LOGGER.info("[Season] World-data pass disabled by config - skipped.");
            return;
        }
        int pending = countWhere(s, "season < " + CURRENT_SEASON);
        if (pending == 0) {
            CoffeesAeroAuth.LOGGER.info("[Season] World data already on season {}.", CURRENT_SEASON);
            return;
        }
        CoffeesAeroAuth.LOGGER.info("[Season] Clearing old-world data on {} profile(s)...", pending);

        // ONE statement so a profile can never be half-migrated: the season stamp lands with the
        // clears, so a crash mid-way leaves untouched rows to be picked up on the next boot.
        int changed = s.executeUpdate(
            "UPDATE players SET " +
            //  Freeze the season's playtime ONCE. The guard matters: without it a re-run after
            //  Season 2 play would overwrite the snapshot with inflated totals.
            "  season1_playtime = CASE WHEN season1_playtime = 0 THEN total_playtime " +
            "                         ELSE season1_playtime END, " +
            "  season1_veteran  = CASE WHEN total_playtime >= " + VETERAN_SECONDS + " THEN TRUE " +
            "                         ELSE season1_veteran END, " +
            //  World-specific data - the whole point of this pass.
            "  return_dim = NULL, return_x = 0, return_y = 0, return_z = 0, " +
            "  room_slot = -1, room_created_at = 0, " +
            "  season = " + CURRENT_SEASON + " " +
            "WHERE season < " + CURRENT_SEASON);

        CoffeesAeroAuth.LOGGER.info(
            "[Season] Cleared old-world data on {} profile(s). Season 1 veterans (>= {}h): {}. "
                + "Return positions and lobby rooms cleared.",
            changed, VETERAN_SECONDS / 3600, countWhere(s, "season1_veteran = TRUE"));
    }

    /**
     * Re-arms the starter bonus and loads the pending veteran rewards.
     *
     * <p>OFF by default. See the class docs - running this on a server that shares the database with
     * the live one pays players out in the wrong world and marks them as already paid.
     */
    private static void rewardPass(Statement s) throws SQLException {
        if (!AuthConfig.SEASON_GRANT_REWARDS.get()) {
            CoffeesAeroAuth.LOGGER.info(
                "[Season] Reward pass DISABLED (seasonGrantRewards=false) - no starter bonus re-arm, "
                    + "no veteran rewards. This is the correct setting for a test/creative server. "
                    + "Set it true on the live season world.");
            UNCLAIMED.clear();
            return;
        }
        if (countWhere(s, "season_rewarded < " + CURRENT_SEASON) > 0) {
            // They lost everything in the reset, so the one-time starter kit fires again.
            int changed = s.executeUpdate(
                "UPDATE players SET startup_bonus_given = FALSE, season_rewarded = " + CURRENT_SEASON
              + " WHERE season_rewarded < " + CURRENT_SEASON);
            CoffeesAeroAuth.LOGGER.info("[Season] Starter kit re-armed for {} profile(s).", changed);
        }
        loadUnclaimed(s);
    }

    private static int countWhere(Statement s, String where) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM players WHERE " + where)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Loads veterans who have not collected yet. Called after migrating, and also on a boot where
     * nothing needed migrating — otherwise a restart mid-season would forget who is still owed.
     */
    private static void loadUnclaimed(Statement s) throws SQLException {
        UNCLAIMED.clear();
        try (ResultSet rs = s.executeQuery(
                "SELECT uuid, season1_playtime FROM players "
              + "WHERE season1_veteran = TRUE AND season1_reward_claimed = FALSE")) {
            while (rs.next()) {
                try { UNCLAIMED.put(UUID.fromString(rs.getString(1)), rs.getLong(2)); }
                catch (IllegalArgumentException badUuid) { /* skip malformed row */ }
            }
        }
        CoffeesAeroAuth.LOGGER.info("[Season] {} veteran reward(s) pending collection.", UNCLAIMED.size());
    }

    /** Adds a column, treating "already exists" as success. */
    private static void addColumn(Statement s, String definition) {
        try {
            s.executeUpdate("ALTER TABLE players ADD COLUMN " + definition);
        } catch (SQLException alreadyThere) {
            // Expected on every boot after the first.
        }
    }
}
