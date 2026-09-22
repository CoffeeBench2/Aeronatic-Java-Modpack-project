package com.coffeesaerosmp.auth.admin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wipes a player's PROGRESS while keeping their ACCOUNT.
 *
 * <p><b>Deleted:</b> inventory, ender chest (it rides inside the {@code .dat} as {@code EnderItems}),
 * XP, advancements, vanilla stats, position.
 * <br><b>Reset in MySQL:</b> playtime, the frozen season snapshot, first-join and starter-bonus
 * flags, return position.
 * <br><b>Kept:</b> password, display name, Discord link, account type, name approval, join date,
 * first IP, skin, trusted IPs.
 *
 * <p>⚠️ Resetting playtime resets their LEVEL — the sidebar level is playtime-only, so a fresh start
 * drops them to Lv 1. Intended, since playtime is progress, but it is the most visible side effect
 * and the dry run says so out loud.
 *
 * <p>🔴 <b>Does NOT touch third-party mod data.</b> FTB Teams membership, FTB Chunks claims,
 * Waystones, graves and AeroClaims are other mods' storage and are not safely reachable from here.
 * The command prints a manual checklist instead of pretending it handled them.
 */
public final class FreshStart {

    /** Per-player vanilla stores, mirroring {@code AccountTransfer}'s table. */
    private record Sub(String label, String ext, LevelResource res) {
        Path dir(MinecraftServer s) { return s.getWorldPath(res); }
    }

    private static final Sub[] SUBS = {
        new Sub("playerdata",   ".dat",      LevelResource.PLAYER_DATA_DIR),
        new Sub("playerdata",   ".dat_old",  LevelResource.PLAYER_DATA_DIR),
        new Sub("advancements", ".json",     LevelResource.PLAYER_ADVANCEMENTS_DIR),
        new Sub("stats",        ".json",     LevelResource.PLAYER_STATS_DIR),
    };

    public record Result(boolean ok, List<String> lines) {
        static Result fail(String why) {
            List<String> l = new ArrayList<>();
            l.add("§c" + why);
            return new Result(false, l);
        }
    }

    private FreshStart() {}

    /** Dry run: inspects and explains, writes nothing. Server thread only — it reads PlayerList. */
    public static Result plan(MinecraftServer server, String name) {
        requireServerThread(server);
        ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return Result.fail("Profile store is not ready.");

        PlayerProfile p = store.findByAnyName(name);
        boolean dbUp = CoffeesAeroAuth.DB_MANAGER != null && CoffeesAeroAuth.DB_MANAGER.isAvailable();
        // The plan deliberately does NOT refuse for "online" — it explains what will happen instead.
        String why = FreshStartRules.refusalReason(p != null, dbUp, false);
        if (why != null) return Result.fail(why);

        UUID uuid = p.getUUID();
        boolean online = server.getPlayerList().getPlayer(uuid) != null;

        List<String> out = new ArrayList<>();
        out.add("§6=== Fresh start plan: §f" + p.username + " §6===");
        out.add("§7uuid §8" + uuid);
        out.add("§7account §f" + p.accountType + "§7, playtime §f"
                + (p.totalPlaytimeSeconds / 3600) + "h§7, display §f" + p.displayName);
        if (online) {
            out.add("§e player is ONLINE — they will be KICKED. Re-run the command once they are "
                  + "gone; vanilla rewrites their playerdata on disconnect, so wiping now would be "
                  + "undone.");
        }
        out.add("§cWILL DELETE§7: inventory, ender chest, XP, advancements, stats, position");
        out.add("§cWILL RESET§7: playtime §8(drops them to §fLv 1§8)§7, season snapshot, "
              + "first-join + starter-bonus flags");
        out.add("§aWILL KEEP§7: password, display name, Discord link, approvals, trusted IPs, "
              + "join date, skin");
        out.add("§8not handled — do these by hand: FTB Teams · FTB Chunks claims · AeroClaims "
              + "· Waystones · graves");
        out.add("§7Run again with §fconfirm§7 to execute.");
        return new Result(true, out);
    }

    /**
     * Executes the wipe. Server thread only.
     *
     * <p>Refuses outright if the target is still online rather than kicking and waiting. A
     * kick-then-poll would need a delay, and sleeping on the server thread to wait for a disconnect
     * is exactly the kind of thing that stalls a tick loop. The command kicks and asks the admin to
     * re-run, which has no timing race at all.
     */
    public static Result execute(MinecraftServer server, String name) {
        requireServerThread(server);
        ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
        PlayerProfile p = store == null ? null : store.findByAnyName(name);
        boolean dbUp = CoffeesAeroAuth.DB_MANAGER != null && CoffeesAeroAuth.DB_MANAGER.isAvailable();
        UUID uuid = p == null ? null : p.getUUID();
        boolean online = uuid != null && server.getPlayerList().getPlayer(uuid) != null;

        String why = FreshStartRules.refusalReason(p != null, dbUp, online);
        if (why != null) return Result.fail(why);

        List<String> out = new ArrayList<>();

        // 1. BACK UP FIRST. A wipe with no backup does not ship, and a failed copy aborts
        //    everything before a single file is removed.
        Path backup = server.getWorldPath(LevelResource.ROOT)
            .resolve("coffeesaeroauth").resolve("freshstart-backups")
            .resolve(FreshStartRules.backupFolderName(p.username, System.currentTimeMillis()));
        try {
            Files.createDirectories(backup);
            int copied = 0;
            for (Sub s : SUBS) {
                Path src = s.dir(server).resolve(uuid + s.ext());
                if (!Files.exists(src)) continue;
                Files.copy(src, backup.resolve(s.label() + "-" + uuid + s.ext()),
                    StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
            out.add("§7backed up §f" + copied + "§7 file(s) to §f" + backup);
        } catch (Exception e) {
            return Result.fail("Backup FAILED (" + e.getMessage() + ") — nothing was deleted.");
        }

        // 2. Delete.
        int deleted = 0;
        for (Sub s : SUBS) {
            Path f = s.dir(server).resolve(uuid + s.ext());
            try {
                if (Files.deleteIfExists(f)) deleted++;
            } catch (Exception e) {
                out.add("§c could not delete " + f.getFileName() + ": " + e.getMessage());
            }
        }
        out.add("§7deleted §f" + deleted + "§7 file(s)");

        // 3. Reset the progress columns. Synchronous on purpose: the admin must be told whether
        //    this actually landed, and an async failure would be a log line nobody reads while the
        //    command reported success.
        if (!resetColumns(uuid, out)) {
            out.add("§cFiles were deleted but the profile reset FAILED — the account is now "
                  + "half-wiped. Fix the database and re-run; the backup is at " + backup);
            return new Result(false, out);
        }

        // 4. In-memory state. sessionStartEpoch is NOT a database column — it lives only on the
        //    cached object — so clearing it in SQL is impossible and clearing it here is required.
        p.totalPlaytimeSeconds = 0;
        p.season1PlaytimeSeconds = 0;
        p.sessionStartEpoch = 0;
        p.firstJoinComplete = false;
        p.startupBonusGiven = false;
        p.returnDim = null;
        p.returnX = p.returnY = p.returnZ = 0;
        store.evict(uuid);          // force the next read to come from the freshly-written row
        out.add("§7profile progress reset; cache evicted");

        out.add("§a" + p.username + " has a fresh start. Account, password and display name are "
              + "unchanged.");
        out.add("§8Still to do by hand: FTB Teams · FTB Chunks claims · AeroClaims · Waystones "
              + "· graves");
        CoffeesAeroAuth.LOGGER.warn("[FreshStart] {} ({}) wiped — backup at {}",
            p.username, uuid, backup);
        return new Result(true, out);
    }

    /**
     * Zeroes the progress columns.
     *
     * <p>🔑 {@code season1_playtime} must be zeroed alongside {@code total_playtime}. Current-season
     * playtime is computed as {@code total - season1}, so wiping the total while leaving a frozen
     * snapshot behind would make the player's season playtime NEGATIVE. That column is added by
     * {@code SeasonMigration} rather than the base schema, so its absence is tolerated — the rest of
     * the reset still has to succeed.
     */
    private static boolean resetColumns(UUID uuid, List<String> out) {
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET total_playtime=0, first_join_complete=FALSE, " +
                    "startup_bonus_given=FALSE, return_dim=NULL, return_x=0, return_y=0, " +
                    "return_z=0 WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            // Separate statement: the column only exists once SeasonMigration has run.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET season1_playtime=0 WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (Exception noSeasonColumn) {
                out.add("§8(no season1_playtime column — season migration has not run here)");
            }
            return true;
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[FreshStart] column reset failed for {}: {}",
                uuid, e.toString());
            return false;
        }
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                "FreshStart must run on the server thread (it reads PlayerList, which is not "
              + "thread-safe, and a stale 'nobody is online' is what lets files be deleted under a "
              + "live player).");
        }
    }
}
