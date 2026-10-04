package com.coffeesaerosmp.auth.admin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.UUIDUtil;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Moves one account from the uuid of an old Minecraft name to the uuid of a new one.
 *
 * <h3>Why this is necessary at all</h3>
 * The backend is {@code online-mode=false}, and since the transfer gate the client connects DIRECTLY
 * to it — no proxy forwards an identity. So the server mints every uuid itself as
 * {@code md5("OfflinePlayer:" + username)}: <b>the uuid is derived from the name.</b>
 * {@code players.uuid} is the primary key, and vanilla {@code playerdata/}, {@code advancements/}
 * and {@code stats/} are keyed on it too. A rename therefore does not rename anything — it mints a
 * brand-new player and orphans the old account.
 *
 * <h3>What has to move</h3>
 * Five stores, and missing any one leaves a half-migrated player:
 * <ol>
 *   <li>MySQL {@code players} (re-keyed, so every column carries over without listing them),
 *       plus {@code trusted_ips} / {@code name_queue}; {@code sessions} is discarded.</li>
 *   <li>{@link ProfileStore}'s in-memory cache — reads are cache-first, so a stale entry would
 *       answer for the rest of the server's uptime.</li>
 *   <li>The display-name index.</li>
 *   <li>The flat-file fallback {@code profiles/&lt;uuid&gt;.json}.</li>
 *   <li>Vanilla {@code playerdata/*.dat(_old)}, {@code advancements/*.json}, {@code stats/*.json}.</li>
 * </ol>
 *
 * <h3>The rule that makes it safe</h3>
 * 🔴 <b>Both accounts must be OFFLINE.</b> Vanilla holds a logged-in player's {@code .dat} and
 * rewrites it on disconnect, so moving it under a live player is simply undone — silently. Every
 * entry point re-checks this immediately before touching anything rather than trusting a caller.
 */
public final class AccountTransfer {

    /** A destination profile with less than this much playtime is an auto-created empty one, safe to
     *  discard. Anything above it is a real account and the transfer refuses rather than overwrite. */
    private static final long AUTOCREATED_MAX_PLAYTIME = 3600;

    private AccountTransfer() {}

    /** Outcome of a plan or a run: whether it may proceed, and the human-readable reasoning. */
    public record Result(boolean ok, List<String> lines) {
        public static Result fail(String why) {
            List<String> l = new ArrayList<>();
            l.add(why);
            return new Result(false, l);
        }
    }

    /** The uuid vanilla mints for a name on an offline-mode server. */
    public static UUID offlineUuid(String name) {
        return UUIDUtil.expectedOfflineUUID(name);
    }

    // ── preflight ────────────────────────────────────────────────────────────

    /**
     * Everything that must be true before a transfer may run. Pure inspection — writes nothing.
     *
     * <p>Returned lines are meant to be shown to the admin verbatim, because the interesting cases
     * (destination occupied, player online) need a human decision, not a retry.
     */
    public static Result plan(MinecraftServer server, String oldName, String newName) {
        if (oldName.equalsIgnoreCase(newName)) {
            return Result.fail("Old and new names are the same — nothing to transfer.");
        }
        return plan(server, offlineUuid(oldName), offlineUuid(newName), newName);
    }

    /**
     * UUID form of {@link #plan(MinecraftServer, String, String)}. Under {@code premiumKeepsMojangUuid}
     * the destination is the Mojang uuid, and old and new may carry the SAME name (the heal that moves a
     * name-derived premium profile onto its Mojang uuid), so identity is decided by uuid, not name.
     */
    public static Result plan(MinecraftServer server, UUID oldId, UUID newId, String newName) {
        List<String> out = new ArrayList<>();

        // 🔴 SERVER THREAD ONLY. The both-offline gate below reads PlayerList, whose `players` list
        // and `playersByUUID` map are plain collections mutated by the server thread. Reading them
        // from a worker is a data race, and the value it races on is the ONE safety invariant of
        // this class — a stale "nobody is online" is what lets files move under a live player.
        // Fail loudly rather than silently sample a torn view.
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                "AccountTransfer must run on the server thread (PlayerList is not thread-safe). "
              + "Wrap the call in server.execute(...).");
        }

        if (oldId.equals(newId)) {
            return Result.fail("Old and new uuids are the same — nothing to transfer.");
        }
        ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return Result.fail("Profile store is not ready.");
        if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) {
            // Re-keying across MySQL + cache + flat file while the DB is down would leave the two
            // stores disagreeing, and the flat file is the one the server falls back to.
            return Result.fail("MySQL is DOWN (flat-file fallback). Refusing — a transfer now would "
                             + "desync the database from the fallback store. Retry when DB: UP.");
        }

        // 🔴 Online players hold their .dat open and rewrite it on disconnect.
        ServerPlayer onOld = server.getPlayerList().getPlayer(oldId);
        ServerPlayer onNew = server.getPlayerList().getPlayer(newId);
        if (onOld != null || onNew != null) {
            return Result.fail("§c" + (onOld != null ? onOld : onNew).getGameProfile().getName() + " is ONLINE. "
                             + "Both accounts must be offline — a logged-in player rewrites their "
                             + "playerdata on disconnect and would undo the move.");
        }

        PlayerProfile oldP = store.get(oldId);
        if (oldP == null) {
            return Result.fail("No profile at §f" + oldId + "§c. "
                             + "Nothing to transfer — check the spelling.");
        }
        PlayerProfile newP = store.get(newId);

        out.add("§7from §f" + oldP.username + " §8" + oldId);
        out.add("§7to   §f" + newName + " §8" + newId);
        out.add("§7account: §f" + oldP.accountType + "§7, playtime §f"
                + (oldP.totalPlaytimeSeconds / 3600) + "h§7, display §f" + oldP.displayName
                + (oldP.discordId != null && !oldP.discordId.isBlank() ? " §7(Discord linked)" : ""));

        if (newP != null) {
            if (newP.totalPlaytimeSeconds >= AUTOCREATED_MAX_PLAYTIME) {
                return Result.fail("§cDestination uuid already belongs to a REAL account (§f"
                        + newP.username + "§c, " + (newP.totalPlaytimeSeconds / 3600)
                        + "h played). Refusing — resolve this by hand.");
            }
            out.add("§e destination holds an auto-created profile ("
                    + newP.totalPlaytimeSeconds + "s played) — it will be discarded.");
        } else {
            out.add("§a destination uuid is free.");
        }

        for (Sub s : SUBS) {
            Path src = s.dir(server).resolve(oldId + s.ext);
            out.add((Files.exists(src) ? "§a will move  §7" : "§8 absent    ")
                    + s.label + "/" + oldId + s.ext);
        }
        return new Result(true, out);
    }

    // ── execute ──────────────────────────────────────────────────────────────

    /**
     * Runs the transfer. Re-runs {@link #plan} first and aborts on any objection, so a stale
     * confirmation (the player logged back in while the admin was reading) cannot slip through.
     */
    public static Result execute(MinecraftServer server, String oldName, String newName) {
        if (oldName.equalsIgnoreCase(newName)) {
            return Result.fail("Old and new names are the same — nothing to transfer.");
        }
        return execute(server, offlineUuid(oldName), offlineUuid(newName), newName);
    }

    /** UUID form of {@link #execute(MinecraftServer, String, String)}; see the UUID form of {@code plan}. */
    public static Result execute(MinecraftServer server, UUID oldId, UUID newId, String newName) {
        Result pre = plan(server, oldId, newId, newName);
        if (!pre.ok()) return pre;

        ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
        PlayerProfile oldP = store.get(oldId);
        String oldDisplayLower = oldP.displayName;

        List<String> out = new ArrayList<>();

        // 1. Database, in one transaction. Re-key rather than copy so every column comes along.
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                // Guarded: can only remove an auto-created profile. If a real account were there,
                // plan() has already refused, and this deleting nothing would make the UPDATE below
                // fail on the primary key — a loud failure, never a silent overwrite.
                exec(c, "DELETE FROM players WHERE uuid=? AND total_playtime<?", newId.toString(),
                     AUTOCREATED_MAX_PLAYTIME);
                exec(c, "DELETE FROM trusted_ips WHERE uuid=?", newId.toString());
                exec(c, "DELETE FROM name_queue  WHERE uuid=?", newId.toString());
                // One-row-per-player tables keyed on uuid: anything the destination session wrote in its
                // few seconds online would collide with the move below. The destination is auto-created
                // (plan() refuses otherwise), so its rows are disposable.
                for (String t : PER_PLAYER_TABLES) execIfTable(c, "DELETE FROM " + t + " WHERE uuid=?", newId.toString());

                int moved = exec(c, "UPDATE players SET uuid=?, username=? WHERE uuid=?",
                                 newId.toString(), newName, oldId.toString());
                if (moved != 1) throw new SQLException(
                        "players UPDATE moved " + moved + " rows, expected 1 — rolled back");

                exec(c, "UPDATE trusted_ips SET uuid=? WHERE uuid=?", newId.toString(), oldId.toString());
                exec(c, "UPDATE name_queue  SET uuid=? WHERE uuid=?", newId.toString(), oldId.toString());
                for (String t : PER_PLAYER_TABLES)
                    execIfTable(c, "UPDATE " + t + " SET uuid=? WHERE uuid=?", newId.toString(), oldId.toString());
                for (String t : HISTORY_TABLES)
                    execIfTable(c, "UPDATE " + t + " SET uuid=? WHERE uuid=?", newId.toString(), oldId.toString());
                exec(c, "DELETE FROM sessions WHERE uuid IN (?,?)", oldId.toString(), newId.toString());

                c.commit();
                out.add("§a✔ database re-keyed §7(players, trusted_ips, name_queue, stats, footprint, "
                      + "confiscations, infractions, session_log; sessions cleared)");
            } catch (SQLException e) {
                c.rollback();
                return Result.fail("§cDatabase transfer FAILED and was rolled back: " + e.getMessage()
                                 + " §7— nothing was moved, files untouched.");
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            return Result.fail("§cCould not reach MySQL: " + e.getMessage());
        }

        // 2. In-memory state. Reads are cache-first, so this is not cosmetic — skip it and the old
        //    uuid keeps answering and the new one looks absent until the next restart.
        store.evict(oldId);
        store.evict(newId);
        PlayerProfile moved = store.get(newId);          // re-reads from MySQL and re-caches
        if (moved != null) {
            store.releaseDisplayName(oldDisplayLower);
            store.registerDisplayName(moved.displayName, newId);
            out.add("§a✔ cache + display-name index updated §7(" + moved.displayName + " → " + newName + ")");
        } else {
            out.add("§e! profile did not read back after the re-key — check MySQL before they rejoin.");
        }

        // 3. Flat-file fallback, so a later DB outage does not resurrect the old identity.
        try {
            Path fOld = store.profileFile(oldId), fNew = store.profileFile(newId);
            if (Files.exists(fOld)) {
                Files.move(fOld, fNew, StandardCopyOption.REPLACE_EXISTING);
                out.add("§a✔ fallback profile moved");
            }
        } catch (IOException e) {
            out.add("§e! fallback profile move failed: " + e.getMessage());
        }

        // 4. World files.
        Path backup = server.getWorldPath(LevelResource.ROOT)
                .resolve("aero-transfer-backup-"
                        + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        for (Sub s : SUBS) {
            Path dir = s.dir(server);
            Path src = dir.resolve(oldId + s.ext), dst = dir.resolve(newId + s.ext);
            if (!Files.exists(src)) continue;
            try {
                Files.createDirectories(backup);
                Files.copy(src, backup.resolve(s.label + "-" + oldId + s.ext),
                           StandardCopyOption.REPLACE_EXISTING);
                if (Files.exists(dst)) {
                    // The empty profile their new-name login created. Still evidence if this goes wrong.
                    Files.copy(dst, backup.resolve(s.label + "-destination-was-" + newId + s.ext),
                               StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                out.add("§a✔ " + s.label + "/" + s.ext.substring(1));
            } catch (IOException e) {
                out.add("§c✘ " + s.label + s.ext + " FAILED: " + e.getMessage()
                        + " §7— DB already moved; fix this file by hand before they rejoin.");
            }
        }
        out.add("§7backups → §f" + backup.getFileName());
        out.add("§6Mod-side data (FTB team/claims/quests, balances, ships) is NOT moved — "
                + "it is uuid-keyed inside mod storage. Have them verify in game.");
        return new Result(true, out);
    }

    // ── vanilla per-player files ─────────────────────────────────────────────

    // ── uuid-keyed tables beyond players / trusted_ips / name_queue / sessions ──
    //
    // Before 2026-10-04 a transfer moved only players, trusted_ips and name_queue, so a renamed player's
    // stats, footprint, moderation history and an active confiscation stayed behind on the old uuid.
    // Store tables (subscriptions, cosmetics_*, store_pending_grants) are keyed by MOJANG uuid and never
    // move. 🔴 A table missing here is a table a transfer silently splits — keep in step with the schema.

    /** One row per player (uuid is the PRIMARY KEY): the destination's row is deleted first. */
    static final String[] PER_PLAYER_TABLES = { "player_stats", "player_footprint", "confiscations", "level_progress" };

    /** Many rows per player: moved as they are, histories merge. */
    static final String[] HISTORY_TABLES = { "infractions", "session_log", "mail" };

    private record Sub(String label, String ext, LevelResource res) {
        Path dir(MinecraftServer s) { return s.getWorldPath(res); }
    }

    private static final Sub[] SUBS = {
        new Sub("playerdata",   ".dat",      LevelResource.PLAYER_DATA_DIR),
        new Sub("playerdata",   ".dat_old",  LevelResource.PLAYER_DATA_DIR),
        new Sub("advancements", ".json",     LevelResource.PLAYER_ADVANCEMENTS_DIR),
        new Sub("stats",        ".json",     LevelResource.PLAYER_STATS_DIR),
    };

    private static int exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }

    /** MySQL error 1146 ER_NO_SUCH_TABLE. A statement error does not abort a MySQL transaction. */
    private static final int ER_NO_SUCH_TABLE = 1146;

    /**
     * {@link #exec} for the tracking/moderation tables, which are created by their own subsystems and can
     * be absent on a test database. A missing table has nothing to move; any other error still rolls back.
     */
    private static int execIfTable(Connection c, String sql, Object... args) throws SQLException {
        try {
            return exec(c, sql, args);
        } catch (SQLException e) {
            if (e.getErrorCode() == ER_NO_SUCH_TABLE) return 0;
            throw e;
        }
    }

    /**
     * {@code premiumKeepsMojangUuid}: the name-derived profile a premium player arriving under their Mojang
     * uuid should fold into, or null.
     *
     * <p>Only an UNLINKED PREMIUM row with no identity hold. Unlinked means no Mojang uuid was ever
     * stamped on it, so nothing contradicts the arrival — the same reasoning as {@link IdentityGate}'s
     * "unlinked premium profile: ALLOW", because the gate only marks a name premium while Mojang resolves
     * it. A HELD row is a released name: whoever arrives under it now bought the name and gets a fresh
     * profile. An OFFLINE row is not folded (its owner proves themselves with the password flow).
     */
    public static UUID unlinkedPremiumAlias(String name, UUID mojangUuid) {
        if (name == null || mojangUuid == null || CoffeesAeroAuth.DB_MANAGER == null
                || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return null;
        UUID alias = offlineUuid(name);
        if (alias.equals(mojangUuid)) return null;
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT uuid FROM players WHERE uuid=? AND account_type='PREMIUM' "
               + "AND (mojang_uuid IS NULL OR mojang_uuid='') AND (identity_hold IS NULL OR identity_hold='')")) {
            ps.setString(1, alias.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? alias : null;
            }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Transfer] unlinkedPremiumAlias lookup failed", e);
            return null;
        }
    }

    /** True if the profile at {@code uuid} carries an identity hold (live DB read; false when unknown). */
    public static boolean isHeld(UUID uuid) {
        if (uuid == null || CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) {
            return false;
        }
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT identity_hold FROM players WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String h = rs.getString(1);
                return h != null && !h.isBlank();
            }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Transfer] isHeld lookup failed", e);
            return false;
        }
    }

    /**
     * The old offline uuid of the account this MOJANG uuid last used, or null.
     *
     * <p>This is the whole basis of automatic rename handling: a premium player's Mojang uuid never
     * changes, so a profile filed under {@code mojang_uuid} but a DIFFERENT offline uuid is proof
     * the human renamed — not a guess, not a heuristic.
     */
    public static UUID previousIdentity(UUID mojangUuid, UUID currentOfflineUuid) {
        if (mojangUuid == null || CoffeesAeroAuth.DB_MANAGER == null
                || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return null;
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT uuid FROM players WHERE mojang_uuid=? LIMIT 1")) {
            ps.setString(1, mojangUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                UUID prior = UUID.fromString(rs.getString(1));
                return prior.equals(currentOfflineUuid) ? null : prior;
            }
        } catch (SQLException | IllegalArgumentException e) {
            CoffeesAeroAuth.LOGGER.warn("[Transfer] previousIdentity lookup failed", e);
            return null;
        }
    }

    /**
     * Record the Mojang uuid against a profile so a future rename is detectable — <b>binding only when
     * the profile is unclaimed.</b>
     *
     * <h3>🔴 Why this is no longer an unconditional UPDATE</h3>
     * Identity here is {@code md5("OfflinePlayer:" + name)}, so whoever holds a Mojang name inherits the
     * profile filed under it. Two ways that happens: a stranger buys a still-free offline player's name,
     * or a premium player renames, the old name is released, and a stranger buys it.
     *
     * <p>This method used to run {@code UPDATE players SET mojang_uuid=? WHERE uuid=?} with no guard, so
     * the takeover <b>overwrote the only evidence that it had happened</b> — the stolen profile came out
     * the far side looking legitimately the newcomer's, with nothing left to compare against. That made
     * a detectable intrusion unrecoverable.
     *
     * <p>Now: bind if unclaimed, no-op if it is already ours, and on a genuine mismatch <b>leave the
     * stored value alone and raise a HIGH alert</b>. A Mojang uuid never changes, so a different one
     * arriving for the same profile means a different human — it is not a heuristic.
     *
     * <h3>Detection here; denial happens earlier</h3>
     * Since 1.11.4 the refusal is {@link IdentityGate}, run inside {@code resolvePlayerType} BEFORE this
     * is called, and the caller only calls this for an ALLOWED login. So a MISMATCH reaching this method
     * means either {@code identityGateEnforce=false} (alert-only mode) or a race with the other process
     * binding the row between the gate's read and this one. It still never overwrites.
     * See {@code planning/store-identity-risk.md}.
     */
    public static void rememberMojangUuid(UUID offlineUuid, UUID mojangUuid) {
        if (offlineUuid == null || mojangUuid == null
                || CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
                String stored = null, username = null, source = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT mojang_uuid, username, link_source FROM players WHERE uuid=?")) {
                    ps.setString(1, offlineUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return;        // no row yet; the caller runs after getOrCreate
                        stored   = rs.getString(1);
                        username = rs.getString(2);
                        source   = rs.getString(3);
                    }
                }

                switch (IdentityLink.classify(stored, mojangUuid)) {
                    case ALREADY_OURS -> {
                        // The common case on every login of a linked player: nothing to do — EXCEPT when
                        // the link was only ever INFERRED by the backfill. The same uuid has now been
                        // observed inside the gate's signed cookie, which promotes a guess to a proof, so
                        // record that. It makes a later mismatch unambiguous instead of leaving us
                        // wondering whether the backfill simply named the wrong human.
                        if (!"GATE".equalsIgnoreCase(String.valueOf(source))) {
                            try (PreparedStatement up = c.prepareStatement(
                                    "UPDATE players SET link_source='GATE' WHERE uuid=? AND mojang_uuid=?")) {
                                up.setString(1, offlineUuid.toString());
                                up.setString(2, stored);
                                if (up.executeUpdate() > 0) {
                                    CoffeesAeroAuth.LOGGER.info(
                                        "[Identity] {} — backfilled link CONFIRMED by a real login "
                                      + "(promoted BACKFILL to GATE).", username);
                                }
                            }
                        }
                    }
                    case MISMATCH -> reportLinkMismatch(username, offlineUuid, stored, mojangUuid, source);
                    case BIND -> {
                        // The WHERE clause re-checks the null, so this is atomic against a concurrent
                        // login binding the same row between our read and our write — the lobby and the
                        // SMP are separate processes and both call this.
                        //
                        // Note it does NOT overwrite a BACKFILL link. A disagreement with one is still a
                        // mismatch and must be reported, not silently corrected: if the name had already
                        // changed hands, quietly rebinding would certify the takeover instead of flagging
                        // it. The confidence of the stored link travels with the alert instead.
                        int n;
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE players SET mojang_uuid=?, link_source='GATE' " +
                                "WHERE uuid=? AND (mojang_uuid IS NULL OR mojang_uuid='')")) {
                            ps.setString(1, mojangUuid.toString());
                            ps.setString(2, offlineUuid.toString());
                            n = ps.executeUpdate();
                        }
                        if (n == 0) {
                            // Lost the race. Re-read to see WHO won: the same account (harmless, the
                            // other process bound it) or a different one (report it).
                            try (PreparedStatement ps = c.prepareStatement(
                                    "SELECT mojang_uuid FROM players WHERE uuid=?")) {
                                ps.setString(1, offlineUuid.toString());
                                try (ResultSet rs = ps.executeQuery()) {
                                    String now = rs.next() ? rs.getString(1) : null;
                                    if (IdentityLink.classify(now, mojangUuid) == IdentityLink.LinkAction.MISMATCH) {
                                        reportLinkMismatch(username, offlineUuid, now, mojangUuid, null);
                                    }
                                }
                            }
                        } else {
                            CoffeesAeroAuth.LOGGER.info(
                                "[Identity] Linked profile {} ({}) to Mojang account {}.",
                                username, offlineUuid, mojangUuid);
                        }
                    }
                }
            } catch (SQLException e) {
                CoffeesAeroAuth.LOGGER.warn("[Transfer] could not record mojang_uuid", e);
            }
        });
    }

    /**
     * A different Mojang account just logged into a profile that is already linked.
     *
     * <p>Logged at ERROR and alerted at HIGH: {@code Severity.HIGH} is what makes WatchdogManager attach
     * the admin role mention, and an embed alone notifies nobody. This is exactly the event that must not
     * be noticed a week later.
     */
    private static void reportLinkMismatch(String username, UUID profileUuid, String stored, UUID arriving, String linkSource) {
        CoffeesAeroAuth.LOGGER.error(
            "[Identity] MISMATCH on profile {} ({}): stored Mojang {} but {} just logged in. "
          + "Stored link left UNCHANGED. Possible account takeover — see planning/store-identity-risk.md",
            username, profileUuid, stored, arriving);

        if (CoffeesAeroAuth.WATCHDOG != null) {
            CoffeesAeroAuth.WATCHDOG.alert(com.coffeesaerosmp.auth.watchdog.WatchdogEvent.of(
                com.coffeesaerosmp.auth.watchdog.Severity.HIGH,
                "Identity mismatch — possible account takeover",
                "Reached the bind step, so the login was NOT blocked (identityGateEnforce=false, or a "
              + "lobby/SMP race). Verify who owns the name, then /aeroid hold if needed.",
                "Profile",        username == null ? "(unknown)" : username,
                "Profile uuid",   String.valueOf(profileUuid),
                "Linked Mojang",  stored == null ? "(none)" : stored,
                "Link source",    linkSource == null ? "(unknown)" : linkSource
                                + ("BACKFILL".equalsIgnoreCase(linkSource)
                                   ? " - INFERRED, so the stored link may itself be wrong"
                                   : " - observed in a signed cookie, so the stored link is trustworthy"),
                "Arriving Mojang", String.valueOf(arriving),
                "Meaning",        "A different Mojang account is using this name. Either the name "
                                + "changed hands, or someone bought a released/free name.",
                "Check",          "https://api.mojang.com/users/profiles/minecraft/"
                                + (username == null ? "" : username)));
        }
    }
}
