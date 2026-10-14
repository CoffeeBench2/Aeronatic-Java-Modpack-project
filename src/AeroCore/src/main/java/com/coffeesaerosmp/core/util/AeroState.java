package com.coffeesaerosmp.core.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code .aero-state/} — the ONE directory the Core keeps across updates.
 *
 * <p>🔴 <b>Why this exists (2026-10-15).</b> Everything used to live in {@code .aero-update/}, and
 * the pack {@code Applier} wipes that whole directory after every successful update. That silently
 * destroyed:
 * <ul>
 *   <li>the updater's manifest — so orphan removal had never once worked since the updater was
 *       written (2026-06-24), and every dropped mod survived on disk;</li>
 *   <li>the NeoForge loader request, racing the {@code LoaderApplier} that starts at the same exit;</li>
 *   <li>the DH reset / Xaero migration markers and the news image cache.</li>
 * </ul>
 * Nothing in this directory is ever deleted wholesale. Work directories ({@code .aero-update/},
 * {@code .aero-cleanup/}, {@code .aero-loader/}) hold only throwaway files.
 *
 * <p>No Minecraft classes: the post-exit helpers load this from a bare jar copy.
 */
public final class AeroState {

    public static final String DIR = ".aero-state";

    private AeroState() {}

    public static Path dir(Path gameDir) { return gameDir.resolve(DIR); }

    public static Path file(Path gameDir, String name) { return dir(gameDir).resolve(name); }

    /** Updater manifest: every path the pack manages (plus any removal still pending). */
    public static Path manifest(Path gameDir) { return file(gameDir, "manifest.txt"); }

    /** Remote retired-mod prefixes, cached from version.json {@code "retired"} by VersionCheck. */
    public static Path remoteRetired(Path gameDir) { return file(gameDir, "retired.txt"); }

    /**
     * Reads the manifest, falling back to the pre-1.4.4 location. The old file only ever survives
     * when an update failed before the Applier's wipe, but it is still better than nothing.
     */
    public static List<String> readManifest(Path gameDir) {
        List<String> l = readLines(manifest(gameDir));
        return l.isEmpty() ? readLines(gameDir.resolve(".aero-update").resolve("manifest.txt")) : l;
    }

    /** Lower-cased file names under {@code mods/} that the pack manages, per the manifest. */
    public static Set<String> managedModNames(Path gameDir) {
        Set<String> out = new LinkedHashSet<>();
        for (String rel : readManifest(gameDir)) {
            String r = rel.replace('\\', '/');
            if (r.startsWith("mods/") && r.indexOf('/', 5) < 0) out.add(r.substring(5).toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Cached remote retired prefixes, lower-cased; empty when never fetched. */
    public static List<String> remoteRetiredPrefixes(Path gameDir) {
        List<String> out = new ArrayList<>();
        for (String l : readLines(remoteRetired(gameDir))) {
            String p = l.trim().toLowerCase(Locale.ROOT);
            // A prefix shorter than 4 chars could match half of mods/ — refuse it outright.
            if (p.length() >= 4 && !p.startsWith("#") && !p.contains("/") && !p.contains("\\")) out.add(p);
        }
        return out;
    }

    public static List<String> readLines(Path p) {
        try {
            if (!Files.isRegularFile(p)) return new ArrayList<>();
            List<String> out = new ArrayList<>();
            for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l.trim());
            return out;
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** Write-then-rename so a crash mid-write can never leave a truncated manifest. */
    public static void writeLines(Path p, Iterable<String> lines) throws IOException {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Player data the updater must NEVER delete, whatever a manifest says. Orphan removal only
     * started working in 1.4.4; before arming it, every directory that holds a player's own world
     * knowledge is fenced off: maps, waypoints, MapSyncer state, LODs, singleplayer saves, replays,
     * screenshots, schematics.
     */
    private static final List<String> PROTECTED_PREFIXES = List.of(
        "xaero/", "xaeroworldmap/", "xaerowaypoints", "journeymap/", "sablexaeromaps/",
        "distant_horizons_server_data/", "saves/", "replay_recordings/", "screenshots/",
        "schematics/", "local/", "backups/", ".aero-state/");

    public static boolean isProtected(String rel) {
        if (rel == null) return true;
        String r = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (r.startsWith("/") || r.contains("../") || r.contains(":")) return true;   // never escape the instance
        for (String p : PROTECTED_PREFIXES) if (r.startsWith(p)) return true;
        return false;
    }
}
