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
 * <br><b>Reset in MySQL:</b> playtime, the frozen season snapshots ({@code season1_playtime},
 * {@code season_start_playtime}), first-join and starter-bonus flags, return position.
 * <br><b>Deleted in MySQL (2026-10-04):</b> their {@code mail} (incl. the claimed welcome mail — its dedupe
 * key otherwise BLOCKED the new welcome mail, so a fresh start never got one), {@code level_progress}
 * (level rewards count again from Lv 1) and {@code player_stats}. Rows are written to the backup first.
 * <br><b>Numismatics (2026-10-04):</b> the player's bank account is removed from Numismatics' live bank
 * (saved to the backup as SNBT first). It is recreated empty the next time they use a bank; ID cards still
 * point at it by uuid. Done through {@code Numismatics.BANK} in memory — editing {@code numismatics_bank.dat}
 * on disk would be overwritten by the running server.
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
        out.add("§cWILL RESET§7: playtime §8(drops them to §fLv 1§8)§7, season snapshots, "
              + "first-join + starter-bonus flags");
        out.add("§cWILL DELETE§7: their mail §8(" + (dbUp ? "a new welcome mail follows" : "DB down")
              + ")§7, level rewards, stats · Numismatics bank: " + bankSummary(uuid));
        out.add("§aWILL KEEP§7: password, display name, Discord link, approvals, trusted IPs, "
              + "join date, skin");
        out.add("§cAT NEXT RESTART§7: personal FTB Quests progress, FTB Essentials data (homes)");
        out.add("§8not handled — do these by hand: FTB Teams · FTB Chunks claims · AeroClaims · Waystones · graves");
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

        // 3b. Mail, level rewards, stats — backed up as JSON lines, then deleted.
        out.add(wipeRows(uuid, backup));

        // 3c. Numismatics bank (live, in memory — see the class javadoc).
        out.add("§7bank: " + resetBank(server, uuid, backup));

        // 3d. FTB Quests personal progress + FTB Essentials (homes, back, etc.). Both mods keep these in
        //     memory and write them back on save, so the files can only be removed once they have saved for
        //     the last time: queued, moved at server STOP (see onServerStopped).
        queueOnStop(server, uuid, backup);
        out.add("§7quests + homes: §fqueued §7— reset at the next restart (FTB writes them from memory until then)");

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
        out.add("§8Still to do by hand: FTB Teams · FTB Chunks claims · AeroClaims · Waystones · graves");
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
            // Season 3 clock. Separate: the column only exists on 1.13+ databases.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE players SET season_start_playtime=0 WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (Exception noColumn) { /* pre-1.13 schema */ }
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

    // ── files only removable once FTB has saved for the last time ─────────────

    private static final String PENDING = "freshstart-pending.txt";

    /** {@code <world>/ftbquests/<uuid>.snbt} (personal team progress) and FTB Essentials' player file. */
    private static final String[][] ON_STOP_FILES = {
        { "ftbquests", ".snbt" },
        { "ftbessentials/playerdata", ".snbt" },
    };

    private static void queueOnStop(MinecraftServer server, UUID uuid, Path backup) {
        Path f = server.getWorldPath(LevelResource.ROOT).resolve("coffeesaeroauth").resolve(PENDING);
        try {
            Files.writeString(f, uuid + "\t" + backup + System.lineSeparator(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[FreshStart] could not queue the quest/home reset for {}: {}", uuid, e.toString());
        }
    }

    /**
     * ServerStoppedEvent: every mod has saved, nothing will write these files again until the next boot.
     * Moves the queued files into each player's fresh-start backup folder. A crash before a clean stop just
     * leaves the queue for the next stop.
     */
    public static void onServerStopped(MinecraftServer server) {
        Path f = server.getWorldPath(LevelResource.ROOT).resolve("coffeesaeroauth").resolve(PENDING);
        if (!Files.exists(f)) return;
        try {
            for (String line : Files.readAllLines(f)) {
                String[] parts = line.split("\t", 2);
                if (parts.length < 2 || parts[0].isBlank()) continue;
                Path backup = Path.of(parts[1]);
                Files.createDirectories(backup);
                for (String[] spec : ON_STOP_FILES) {
                    Path src = server.getWorldPath(LevelResource.ROOT).resolve(spec[0]).resolve(parts[0] + spec[1]);
                    if (Files.exists(src)) {
                        Files.move(src, backup.resolve(spec[0].replace('/', '-') + "-" + parts[0] + spec[1]),
                            StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                CoffeesAeroAuth.LOGGER.info("[FreshStart] {}: quest progress + FTB Essentials data reset on stop.", parts[0]);
            }
            Files.delete(f);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[FreshStart] on-stop reset failed (queue kept for next stop): {}", e.toString());
        }
    }

    /** Tables whose rows for this player are progress, not account. Missing tables are skipped. */
    private static final String[] PROGRESS_TABLES = { "mail", "level_progress", "player_stats" };

    /** Backs the rows up to {@code <backup>/db-rows.jsonl}, then deletes them. Returns a summary line. */
    private static String wipeRows(UUID uuid, Path backup) {
        StringBuilder summary = new StringBuilder("§7deleted rows:");
        StringBuilder dump = new StringBuilder();
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
            for (String t : PROGRESS_TABLES) {
                int n = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + t + " WHERE uuid=?")) {
                    ps.setString(1, uuid.toString());
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        var md = rs.getMetaData();
                        while (rs.next()) {
                            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                            o.addProperty("_table", t);
                            for (int i = 1; i <= md.getColumnCount(); i++) {
                                o.addProperty(md.getColumnLabel(i), rs.getString(i));
                            }
                            dump.append(o).append('\n');
                            n++;
                        }
                    }
                    try (PreparedStatement del = c.prepareStatement("DELETE FROM " + t + " WHERE uuid=?")) {
                        del.setString(1, uuid.toString());
                        del.executeUpdate();
                    }
                    summary.append(" §f").append(t).append("§7=").append(n);
                } catch (java.sql.SQLException missingTable) {
                    summary.append(" §8").append(t).append("=n/a");
                }
            }
            Files.writeString(backup.resolve("db-rows.jsonl"), dump.toString());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[FreshStart] row wipe failed for {}: {}", uuid, e.toString());
            return "§cmail/level/stats wipe FAILED: " + e.getMessage();
        }
        return summary.toString();
    }

    /** "1,234 spurs" / "no account" / "Numismatics not installed" — for the dry run. */
    private static String bankSummary(UUID uuid) {
        try {
            Object acct = bankAccounts().get(uuid);
            if (acct == null) return "no account";
            return acct.getClass().getMethod("getBalance").invoke(acct) + " spurs §8(+ any overflow)§7 → removed";
        } catch (ClassNotFoundException e) {
            return "Numismatics not installed";
        } catch (Throwable t) {
            return "unreadable (" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * Removes the player's Numismatics account from the live bank and marks it dirty so the next save
     * drops it. The account is saved to {@code <backup>/numismatics-account.snbt} first; restoring is
     * {@code BankAccount.load} of that tag. Reflection: auth does not compile against Numismatics.
     */
    private static String resetBank(MinecraftServer server, UUID uuid, Path backup) {
        try {
            Class<?> num = Class.forName("dev.ithundxr.createnumismatics.Numismatics");
            Object bank = num.getField("BANK").get(null);
            @SuppressWarnings("unchecked")
            java.util.Map<UUID, Object> accounts = (java.util.Map<UUID, Object>) bank.getClass().getField("accounts").get(bank);
            Object acct = accounts.get(uuid);
            if (acct == null) return "no account (nothing to reset)";
            int balance = (int) acct.getClass().getMethod("getBalance").invoke(acct);
            // The save signature differs by version (1.1.0: save(CompoundTag, Provider); 1.0.x: save(CompoundTag)).
            // Whatever happens here, the balance is recorded before anything is removed.
            Object tag = null;
            try {
                tag = acct.getClass()
                    .getMethod("save", net.minecraft.nbt.CompoundTag.class, net.minecraft.core.HolderLookup.Provider.class)
                    .invoke(acct, new net.minecraft.nbt.CompoundTag(), server.registryAccess());
            } catch (NoSuchMethodException older) {
                try {
                    tag = acct.getClass().getMethod("save", net.minecraft.nbt.CompoundTag.class)
                        .invoke(acct, new net.minecraft.nbt.CompoundTag());
                } catch (NoSuchMethodException none) { /* recorded as text below */ }
            }
            Files.writeString(backup.resolve("numismatics-account.snbt"),
                tag != null ? String.valueOf(tag) : "# account " + uuid + " balance " + balance + " (no NBT save available)");
            accounts.remove(uuid);
            try {
                bank.getClass().getMethod("markBankDirty").invoke(bank);
            } catch (NoSuchMethodException older) {
                // Older Numismatics: the removal still lands on its next bank save (any deposit or the
                // world save marks it dirty). Say so rather than claim it is already on disk.
                return "§faccount removed in memory §7(had " + balance + " spurs) — written at the next bank save";
            }
            CoffeesAeroAuth.LOGGER.warn("[FreshStart] Numismatics account {} removed (balance {}).", uuid, balance);
            return "§faccount removed §7(had " + balance + " spurs; backup numismatics-account.snbt)";
        } catch (ClassNotFoundException e) {
            return "Numismatics not installed";
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.error("[FreshStart] Numismatics reset failed for {}: {}", uuid, t.toString());
            return "§cFAILED (" + t.getClass().getSimpleName() + ") — reset the balance by hand";
        }
    }

    private static java.util.Map<?, ?> bankAccounts() throws Exception {
        Class<?> num = Class.forName("dev.ithundxr.createnumismatics.Numismatics");
        Object bank = num.getField("BANK").get(null);
        return (java.util.Map<?, ?>) bank.getClass().getField("accounts").get(bank);
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
