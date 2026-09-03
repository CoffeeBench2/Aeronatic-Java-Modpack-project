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
    static final List<String> RETIRED = List.of(
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
        "continuity-3.0.0+1.21.jar"                   // replacement is continuity-3.0.0+1.21.neoforge
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
                    String low = name.toLowerCase(Locale.ROOT);
                    // Only real mod files. Anything already disabled is the player's or Potato
                    // mode's business, not ours.
                    if (!low.endsWith(".jar")) continue;
                    for (String prefix : RETIRED) {
                        if (low.startsWith(prefix)) { doomed.add(name); break; }
                    }
                }
            }
            if (doomed.isEmpty()) return;

            Path work = gameDir.resolve(DIR);
            Files.createDirectories(work);
            Files.write(work.resolve(QUEUE), doomed, StandardCharsets.UTF_8);
            launch(gameDir, work);
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

    /** Same fallback as the updater: NeoForge's module classloader does not always expose a
     *  plain {@code file:} code source, so scanning mods/ by name is the reliable path. */
    private static Path resolveSelfJar(Path gameDir) throws IOException {
        try {
            var loc = StaleMods.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                Path p = Paths.get(loc.toURI());
                if (Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return p;
            }
        } catch (Exception ignored) {}
        Path mods = gameDir.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var s = Files.list(mods)) {
                var hit = s.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.startsWith("coffeesaerocore") && n.endsWith(".jar");
                }).findFirst();
                if (hit.isPresent()) return hit.get();
            }
        }
        throw new IOException("could not locate CoffeesAeroCore jar for the cleaner");
    }

    private static String javaw() {
        String home = System.getProperty("java.home", "");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path p = Paths.get(home, "bin", win ? "javaw.exe" : "java");
        return Files.exists(p) ? p.toString() : (win ? "javaw.exe" : "java");
    }
}
