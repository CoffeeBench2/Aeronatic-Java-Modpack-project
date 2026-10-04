package com.coffeesaerosmp.auth.launch;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The Season 3 "fresh start" for staff-test accounts (owner, 2026-10-04).
 *
 * <p>Admins build and test S3 with their PREMIUM accounts, which are also the accounts they will play
 * survival on after launch. At launch those accounts must look like brand-new players, while the world they
 * built (spawn) stays exactly as it is. Flip {@code launchReset=true} once; the next boot does this, writes a
 * stamp, and never runs again — so the lever "doesn't matter afterwards", as asked.
 *
 * <h2>What is reset, per account</h2>
 * Vanilla {@code playerdata/<uuid>.dat(_old)} (inventory, ender chest, position, XP, effects),
 * {@code advancements/<uuid>.json}, {@code stats/<uuid>.json}, personal FTB Quests progress
 * {@code ftbquests/<uuid>.snbt}, FTB Essentials {@code ftbessentials/playerdata/<uuid>.snbt} (homes, etc.).
 * Optionally op is removed.
 *
 * <h2>What is NOT touched</h2>
 * FTB teams and claims (spawn must stay protected — the owner has not decided their fate yet), party quest
 * progress, the world, anything in MySQL (the test phase runs on its own database, which launch replaces).
 *
 * <h2>Safety</h2>
 * Runs at ServerStarted, before anyone can join. Files are MOVED into
 * {@code <world>/coffeesaeroauth/launch-reset-backup-<time>/}, never deleted. Names resolve through
 * {@code usercache.json} — i.e. accounts that actually joined — so a typo resets nobody rather than the wrong
 * person, and is logged.
 */
public final class LaunchReset {

    private static final String STAMP = "launch_reset_done.txt";

    private LaunchReset() {}

    private record Personal(String label, LevelResource base, String sub, String ext) {}

    private static final Personal[] FILES = {
        new Personal("playerdata",   LevelResource.PLAYER_DATA_DIR,         "",                        ".dat"),
        new Personal("playerdata",   LevelResource.PLAYER_DATA_DIR,         "",                        ".dat_old"),
        new Personal("advancements", LevelResource.PLAYER_ADVANCEMENTS_DIR, "",                        ".json"),
        new Personal("stats",        LevelResource.PLAYER_STATS_DIR,        "",                        ".json"),
        new Personal("ftbquests",    LevelResource.ROOT,                    "ftbquests",               ".snbt"),
        new Personal("ftbessentials",LevelResource.ROOT,                    "ftbessentials/playerdata", ".snbt"),
    };

    public static void onServerStarted(MinecraftServer server) {
        boolean armed;
        try { armed = AuthConfig.LAUNCH_RESET.get(); } catch (Exception e) { return; }
        if (!armed) return;
        Path dataDir = server.getWorldPath(LevelResource.ROOT).resolve("coffeesaeroauth");
        Path stamp = dataDir.resolve(STAMP);
        if (Files.exists(stamp)) {
            CoffeesAeroAuth.LOGGER.info("[Launch] launchReset=true but already done ({}) — nothing to do.", stamp);
            return;
        }
        List<? extends String> names = AuthConfig.LAUNCH_RESET_ACCOUNTS.get();
        Map<String, UUID> resolved = resolve(server, names);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = dataDir.resolve("launch-reset-backup-" + ts);
        StringBuilder report = new StringBuilder("Season launch reset " + ts + "\n");

        for (String n : names) {
            if (!resolved.containsKey(n)) {
                CoffeesAeroAuth.LOGGER.warn("[Launch] '{}' never joined (not in usercache.json) — skipped.", n);
                report.append("SKIPPED ").append(n).append(" (never joined)\n");
            }
        }
        for (Map.Entry<String, UUID> e : resolved.entrySet()) {
            UUID id = e.getValue();
            int moved = 0;
            for (Personal f : FILES) {
                Path dir = server.getWorldPath(f.base());
                if (!f.sub().isEmpty()) dir = dir.resolve(f.sub());
                Path src = dir.resolve(id + f.ext());
                if (!Files.exists(src)) continue;
                try {
                    Path dst = backup.resolve(f.label()).resolve(src.getFileName().toString());
                    Files.createDirectories(dst.getParent());
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    moved++;
                } catch (IOException ex) {
                    CoffeesAeroAuth.LOGGER.error("[Launch] could not move {}: {}", src, ex.getMessage());
                    report.append("ERROR moving ").append(src).append(": ").append(ex.getMessage()).append('\n');
                }
            }
            boolean deopped = false;
            if (AuthConfig.LAUNCH_RESET_DEOP.get()) {
                GameProfile gp = new GameProfile(id, e.getKey());
                if (server.getPlayerList().isOp(gp)) {
                    server.getPlayerList().deop(gp);
                    deopped = true;
                }
            }
            CoffeesAeroAuth.LOGGER.warn("[Launch] reset {} ({}): {} file(s) moved to backup{}.",
                e.getKey(), id, moved, deopped ? ", de-opped" : "");
            report.append("RESET ").append(e.getKey()).append(' ').append(id).append(": ").append(moved)
                  .append(" file(s)").append(deopped ? ", de-opped" : "").append('\n');
        }
        try {
            Files.createDirectories(dataDir);
            report.append("Backup: ").append(backup).append('\n');
            Files.writeString(stamp, report.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // Without the stamp it would run again next boot — but every file is already moved, so a re-run
            // finds nothing and only re-deops. Say so loudly rather than pretend.
            CoffeesAeroAuth.LOGGER.error("[Launch] could not write the stamp {} — set launchReset=false by hand.", stamp);
        }
        CoffeesAeroAuth.LOGGER.warn("[Launch] Season launch reset complete: {} account(s). Report: {}", resolved.size(), stamp);
    }

    /** lowercase name → uuid, from usercache.json (accounts that actually joined this server). */
    private static Map<String, UUID> resolve(MinecraftServer server, List<? extends String> names) {
        Map<String, UUID> out = new LinkedHashMap<>();
        Map<String, UUID> cache = new LinkedHashMap<>();
        Path file = server.getServerDirectory().resolve("usercache.json");
        try {
            JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                cache.put(o.get("name").getAsString().toLowerCase(Locale.ROOT), UUID.fromString(o.get("uuid").getAsString()));
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Launch] cannot read {} — nobody will be reset: {}", file, e.getMessage());
            return out;
        }
        for (String n : names) {
            UUID id = cache.get(n.toLowerCase(Locale.ROOT));
            if (id != null) out.put(n, id);                    // keep the spelling staff typed, for the report
        }
        return out;
    }
}
