package com.coffeesaerosmp.core.cleanup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deletes mods the pack has dropped, from the Core that is ACTUALLY RUNNING.
 *
 * <p>🔴 Why this exists, and why it cannot live in the updater. {@code InClientUpdater} also has a
 * retired-mods list, but that code executes from <b>the Core the player already had installed</b>.
 * Adding a name to it therefore does nothing for anyone until they have already received the build
 * that contains it — the first update run prunes with the OLD list and only the second run prunes
 * correctly. Worse, if the dropped mod collides with its replacement (two jars declaring one modId,
 * or a Fabric jar sitting beside its NeoForge build) the client fails to start, so there is never a
 * second run and it can never self-heal. That is exactly what happened on 2026-09-03 with
 * LongerChatHistory, More Armor Trims and Simulated Coasters.
 *
 * <p>Running the sweep from the mod constructor fixes the ordering: the list that runs is the list
 * inside the jar the player just received.
 *
 * <p><b>Deletion is deferred.</b> FML holds every mod jar open, so unlinking one from inside the
 * running game fails on Windows. Matches are written to a queue and a small post-exit process
 * removes them once the JVM is gone — the same approach {@code Applier} and {@code ModeApplier}
 * use. The player therefore sees them disappear on their next launch, with no extra update cycle.
 *
 * <p>Lives outside {@code core/update/**} on purpose: that package is stripped from the
 * CurseForge build, and CF players need this cleanup at least as much as anyone.
 */
public final class StaleMods {

    /**
     * Filenames the pack no longer ships, matched case-insensitively as prefixes.
     *
     * <p>⚠️ Every entry must be specific enough that it cannot also match the REPLACEMENT. A loader
     * swap is the dangerous shape: {@code continuity-3.0.0+1.21.jar} and
     * {@code continuity-3.0.0+1.21.neoforge.jar} share a prefix, so the entry carries the {@code .jar}
     * to pin it to the old one. Get this wrong and the sweep deletes the mod it just installed.
     */
    public static final List<String> RETIRED = List.of(
        // dropped outright
        "zoomify", "simulatedcoasters", "create_parachute", "grand-teleport", "cameraoverhaul",
        "waystones", "waystonessable", "balm-", "balm_", "railwaysuntold",
        "createdeliveryrequired", "create aeronautics gyroscope",
        // deliberately downgraded, so the newer jar is the stale one
        "justzoom_neoforge_2.1.0",
        // Fabric builds replaced by their NeoForge equivalents
        "longerchathistory-fabric",
        "more_armor_trims-1.",                        // replacement is more_armor_trims-neoforge-
        "dynamic-fps-3.11.4+minecraft-1.21.0-fabric",
        "continuity-3.0.0+1.21.jar",                  // replacement is continuity-3.0.0+1.21.neoforge
        // ── Season 2 removals ─────────────────────────────────────────────────────
        // 🔴 2026-09-13: these were added to InClientUpdater's list ONLY, which is version-gated and
        // does not run at startup, so none of them were ever swept. `create_submarine` registers six
        // REQUIRED network channels, so every player that kept it was refused by the lobby with
        // "Incompatible client" — a total lockout that this list, had it been maintained, would have
        // cleared on the next launch with no release at all. THIS is the list that must be updated.
        "wanna_play_chess",
        "easybuilding",
        "tracks_in_bogs",        // NOT tracks-neoforge-* — that is Create Tracks, still shipped
        "wakes-1.21.1",
        "crawl-0.",              // narrow on purpose; nothing kept starts "crawl-0."
        "create_submarine",      // Create Deep Seas
        "vss-0."                 // Voxy Server Side
    );

    private static final String DIR = ".aero-cleanup";
    private static final String QUEUE = "delete.txt";

    private StaleMods() {}

    /** Scans mods/ and schedules any retired jar for deletion after this session ends. */
    public static void sweep(Path gameDir) {
        try {
            Path mods = gameDir.resolve("mods");
            if (!Files.isDirectory(mods)) return;

            List<String> doomed = new ArrayList<>();
            try (var s = Files.list(mods)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String name = p.getFileName().toString();
                    // Also match Potato-disabled copies. A mod the pack has DROPPED must go whether
                    // or not it is currently switched off, otherwise flipping back to Normal quietly
                    // reinstates content the server no longer has — and for a mod with required
                    // network channels that is an instant lockout, not a cosmetic leftover.
                    String bare = name.endsWith(com.coffeesaerosmp.core.mode.ClientMode.DISABLED_SUFFIX)
                        ? name.substring(0, name.length()
                            - com.coffeesaerosmp.core.mode.ClientMode.DISABLED_SUFFIX.length())
                        : name;
                    String low = bare.toLowerCase(Locale.ROOT);
                    if (!low.endsWith(".jar")) continue;
                    for (String prefix : RETIRED) {
                        if (low.startsWith(prefix)) { doomed.add(name); break; }
                    }
                }
            }

            // 🔴 Duplicate Core jars, which are the reason this sweep can fail to run at all.
            // An interrupted update leaves the previous Core in mods/ next to the new one. FML
            // picks the newer and the game looks fine, but every post-exit helper — this cleaner,
            // Potato mode, the updater's applier, the loader applier — used to copy "the first
            // coffeesaerocore*.jar in directory order", which is the OLD one, and died with
            // ClassNotFoundException. SelfJar now picks by content, and removing the stale jar here
            // closes the loop so the instance stops carrying a booby-trapped duplicate.
            //
            // The RUNNING jar is protected by identity, not by name: resolveSelfJar tells us which
            // file we are actually executing from, so a future rename cannot make us delete it.
            try {
                Path running = resolveSelfJar(gameDir).toRealPath();
                try (var s = Files.list(mods)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        String name = p.getFileName().toString();
                        String low = name.toLowerCase(Locale.ROOT);
                        if (!low.startsWith("coffeesaerocore") || !low.endsWith(".jar")) continue;
                        if (p.toRealPath().equals(running)) continue;   // never the one we are in
                        if (!doomed.contains(name)) doomed.add(name);
                    }
                }
            } catch (Exception e) {
                // If we cannot establish which jar is running we must not guess — deleting the
                // live Core would leave an instance with no Core at all.
            }

            if (doomed.isEmpty()) return;

            Path work = gameDir.resolve(DIR);
            Files.createDirectories(work);
            Files.write(work.resolve(QUEUE), doomed, StandardCharsets.UTF_8);

            // 🔴 SPAWN AT SHUTDOWN, NOT HERE. This sweep runs from the mod constructor, i.e. at
            // game START. Launching the helper here left it blocked in waitForExit() for the whole
            // session, and a helper that waits that long does not survive to see the exit.
            //
            // Measured on a real instance 2026-09-13: sweep spawned the cleaner at 20:05, the game
            // ran until 20:24, and `cleanup.log` stayed 0 bytes while `apply.log` — from the
            // updater's Applier, spawned SECONDS before the same exit with byte-identical
            // ProcessBuilder code — recorded "update applied". Same launcher, same javaw, same
            // redirect: the only difference was how long the child sat waiting. Both Prism and the
            // Modrinth app behaved identically, so this is not one launcher's quirk.
            //
            // A shutdown hook puts us in the Applier's shoes: spawn late, wait seconds, exit. If
            // the game is hard-killed the hook is skipped, which costs nothing — the sweep re-runs
            // next launch and re-queues whatever is still on disk.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { launch(gameDir, work); } catch (Exception ignored) {}
            }, "AeroCore-StaleMods-Spawn"));
        } catch (Exception ignored) {
            // Never let a cleanup failure stop the game from starting.
        }
    }

    private static void launch(Path gameDir, Path work) throws Exception {
        Path self = resolveSelfJar(gameDir);
        Path copy = work.resolve("cleaner.jar");
        Files.copy(self, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ProcessBuilder pb = new ProcessBuilder(javaw(), "-cp", copy.toString(),
            "com.coffeesaerosmp.core.cleanup.StaleModsCleaner",
            String.valueOf(ProcessHandle.current().pid()),
            gameDir.toString(), work.resolve(QUEUE).toString());
        pb.directory(gameDir.toFile());
        pb.redirectOutput(work.resolve("cleanup.log").toFile());
        pb.redirectError(work.resolve("cleanup.log").toFile());
        pb.start();
    }

        /**
     * Delegates to {@link com.coffeesaerosmp.core.util.SelfJar}, which picks a jar that actually
     * contains the class the helper JVM will run. The old inline version took the first
     * {@code coffeesaerocore*.jar} in directory order and silently chose a stale duplicate.
     */
    private static Path resolveSelfJar(Path gameDir) throws IOException {
        return com.coffeesaerosmp.core.util.SelfJar.locate(gameDir, "com.coffeesaerosmp.core.cleanup.StaleModsCleaner");
    }

    private static String javaw() {
        String home = System.getProperty("java.home", "");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path p = Paths.get(home, "bin", win ? "javaw.exe" : "java");
        return Files.exists(p) ? p.toString() : (win ? "javaw.exe" : "java");
    }
}
