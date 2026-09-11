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
        List<String> out = new ArrayList<>();

        if (oldName.equalsIgnoreCase(newName)) {
            return Result.fail("Old and new names are the same — nothing to transfer.");
        }
        ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return Result.fail("Profile store is not ready.");
        if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) {
            // Re-keying across MySQL + cache + flat file while the DB is down would leave the two
            // stores disagreeing, and the flat file is the one the server falls back to.
            return Result.fail("MySQL is DOWN (flat-file fallback). Refusing — a transfer now would "
                             + "desync the database from the fallback store. Retry when DB: UP.");
        }

        UUID oldId = offlineUuid(oldName);
        UUID newId = offlineUuid(newName);

        // 🔴 Online players hold their .dat open and rewrite it on disconnect.
        ServerPlayer onOld = server.getPlayerList().getPlayer(oldId);
        ServerPlayer onNew = server.getPlayerList().getPlayer(newId);
        if (onOld != null || onNew != null) {
            return Result.fail("§c" + (onOld != null ? oldName : newName) + " is ONLINE. "
                             + "Both accounts must be offline — a logged-in player rewrites their "
                             + "playerdata on disconnect and would undo the move.");
        }

        PlayerProfile oldP = store.get(oldId);
        if (oldP == null) {
            return Result.fail("No profile for §f" + oldName + "§c (" + oldId + "). "
                             + "Nothing to transfer — check the spelling.");
        }
        PlayerProfile newP = store.get(newId);

        out.add("§7from §f" + oldName + " §8" + oldId);
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
        Result pre = plan(server, oldName, newName);
        if (!pre.ok()) return pre;

        ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
        UUID oldId = offlineUuid(oldName);
        UUID newId = offlineUuid(newName);
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

                int moved = exec(c, "UPDATE players SET uuid=?, username=? WHERE uuid=?",
                                 newId.toString(), newName, oldId.toString());
                if (moved != 1) throw new SQLException(
                        "players UPDATE moved " + moved + " rows, expected 1 — rolled back");

                exec(c, "UPDATE trusted_ips SET uuid=? WHERE uuid=?", newId.toString(), oldId.toString());
                exec(c, "UPDATE name_queue  SET uuid=? WHERE uuid=?", newId.toString(), oldId.toString());
                exec(c, "DELETE FROM sessions WHERE uuid IN (?,?)", oldId.toString(), newId.toString());

                c.commit();
                out.add("§a✔ database re-keyed §7(players, trusted_ips, name_queue; sessions cleared)");
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

    /** Record the Mojang uuid against a profile so a future rename is detectable. */
    public static void rememberMojangUuid(UUID offlineUuid, UUID mojangUuid) {
        if (offlineUuid == null || mojangUuid == null
                || CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
                exec(c, "UPDATE players SET mojang_uuid=? WHERE uuid=?",
                     mojangUuid.toString(), offlineUuid.toString());
            } catch (SQLException e) {
                CoffeesAeroAuth.LOGGER.warn("[Transfer] could not record mojang_uuid", e);
            }
        });
    }
}
