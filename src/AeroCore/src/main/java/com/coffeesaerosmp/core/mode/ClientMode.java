package com.coffeesaerosmp.core.mode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Normal / Potato client mode — the potato pack, applied onto an installed main pack.
 *
 * <p>The potato channel has always been a SEPARATE mrpack you download by hand. This lets a player
 * on the normal pack get the same thing from the settings screen: the heavy client mods are switched
 * off in place and switched back on again on demand.
 *
 * <p><b>Disabling is a RENAME, never a delete.</b> {@code X.jar} becomes
 * {@code X.jar.aerodisabled}; FML only loads {@code *.jar}, so the mod is inert but the bytes are
 * still there. Going back to Normal is another rename — no download, no network, and it works
 * offline. Deleting would mean a potato player needs a working connection (and the exact pack
 * version) just to undo a setting.
 *
 * <p><b>This package must not live under {@code core/update/}.</b> The CurseForge build is compiled
 * with {@code -PnoUpdater}, which excludes {@code core/update/**} and {@code core/version/**} from
 * the jar entirely. Mode switching has to keep working on that build, so it gets its own package.
 * It also means nothing here may reference the updater.
 *
 * <p>State lives in a plain text file rather than the NeoForge config, because
 * {@link ModeApplier} runs in a bare JVM with no Minecraft on the classpath and has to read the
 * same value. One source of truth beats a config key and a file that can disagree.
 */
public final class ClientMode {

    public enum Mode {
        NORMAL("Normal"), POTATO("Potato");

        private final String label;
        Mode(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** Suffix that makes FML ignore a jar. Anything but {@code .jar} would do; this one is greppable. */
    public static final String DISABLED_SUFFIX = ".aerodisabled";

    /** Where the player's real options.txt is parked while potato settings are in force. */
    public static final String OPTIONS_BACKUP = "options.txt.aeronormal";

    static final String DIR = ".aero-mode";
    static final String STATE = "mode.txt";
    static final String PENDING = "pending.txt";

    /**
     * Jar prefixes switched off by Potato mode. Kept in lockstep with {@code BASE_EXCLUDE} in
     * {@code scripts/build_potato_mrpack.py} — if the two ever disagree, a player toggling Potato
     * gets a different pack from one who downloaded the potato mrpack, which is the whole point of
     * the feature.
     *
     * <p>Prefix match, not exact filenames, so a version bump in the main pack cannot silently stop
     * matching. That failure mode is the dangerous direction: it fails OPEN, shipping the heavy mod
     * to someone who asked for potato.
     *
     * <p>Every entry is client-only cosmetics, audio or rendering, and each was dependency-swept
     * against the whole pack. No content mod is ever cut: the client would be missing registry
     * entries the server sends and could not join at all. {@code CoffeesAeroCore-} appears in the
     * build script's list but NOT here — there it is swapped for the {@code -cf} jar at build time,
     * which is not something a running client can do to itself.
     */
    public static final List<String> POTATO_EXCLUDE = List.of(
        // CameraOverhaul was dropped from the pack on 2026-09-03; its entry is gone rather than
        // left as a harmless no-op, so this list keeps meaning "mods that exist and get disabled".
        "punchy-",                      // hit shake / punch effects
        "DistantHorizons-",             // LOD renderer — the single biggest low-end cost
        "sound-physics-remastered-",    // raytraced audio (CPU)
        "sounds-",                      // Sounds (hibi) — UI/ambient audio
        "SubtleEffects-"                // ambient particles
    );

    /** Potato video defaults, applied over the player's options.txt (original kept in the backup). */
    static final java.util.Map<String, String> POTATO_OPTIONS = java.util.Map.of(
        "renderDistance",      "6",
        "simulationDistance",  "6",
        "particles",           "2",     // minimal
        "graphicsMode",        "0",     // fast
        "ao",                  "false",
        "renderClouds",        "\"false\"",
        "entityShadows",       "false",
        "biomeBlendRadius",    "0",
        "mipmapLevels",        "0"
    );

    private ClientMode() {}

    static Path dir(Path gameDir)     { return gameDir.resolve(DIR); }
    static Path statePath(Path g)     { return dir(g).resolve(STATE); }
    static Path pendingPath(Path g)   { return dir(g).resolve(PENDING); }

    /** The mode this client is CURRENTLY running in. Defaults to Normal for every existing install. */
    public static Mode current(Path gameDir) {
        return read(statePath(gameDir));
    }

    /** The mode staged for the next launch, or null when nothing is pending. */
    public static Mode pending(Path gameDir) {
        Path p = pendingPath(gameDir);
        return Files.exists(p) ? read(p) : null;
    }

    private static Mode read(Path p) {
        try {
            String s = Files.readString(p, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            return "potato".equals(s) ? Mode.POTATO : Mode.NORMAL;
        } catch (IOException e) {
            return Mode.NORMAL;
        }
    }

    static void write(Path p, Mode m) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, m.name().toLowerCase(Locale.ROOT), StandardCharsets.UTF_8);
    }

    /** True if this managed pack path is a mod Potato mode switches off. */
    public static boolean isPotatoExcluded(String managedRel) {
        if (managedRel == null) return false;
        String rel = managedRel.replace('\\', '/');
        int slash = rel.lastIndexOf('/');
        if (slash < 0 || !rel.regionMatches(true, 0, "mods/", 0, 5)) return false;
        String name = rel.substring(slash + 1);
        for (String prefix : POTATO_EXCLUDE) {
            if (name.regionMatches(true, 0, prefix, 0, prefix.length())) return true;
        }
        return false;
    }

    /**
     * Stages a switch and hands off to {@link ModeApplier}, which waits for this process to exit
     * before touching anything — FML holds every mod jar open, so renaming them from inside the
     * running game fails on Windows.
     *
     * <p>Returns false if the handoff could not be launched, so the screen can say so rather than
     * telling the player to restart into a change that will not happen.
     */
    public static boolean requestSwitch(Path gameDir, Mode target) {
        try {
            Files.createDirectories(dir(gameDir));
            write(pendingPath(gameDir), target);
            launchApplier(gameDir, target);
            return true;
        } catch (Exception e) {
            try { Files.deleteIfExists(pendingPath(gameDir)); } catch (IOException ignored) {}
            return false;
        }
    }

    /** Cancels a staged switch that has not been applied yet. */
    public static void cancelPending(Path gameDir) {
        try { Files.deleteIfExists(pendingPath(gameDir)); } catch (IOException ignored) {}
    }

    private static void launchApplier(Path gameDir, Mode target) throws Exception {
        Path work = dir(gameDir);
        Files.createDirectories(work);
        // Run from a COPY of our own jar: the applier must be free to touch mods/ after we exit,
        // and on Windows the original is still locked by the launcher's classloader.
        Path selfJar = resolveSelfJar(gameDir);
        Path applierJar = work.resolve("modeapplier.jar");
        Files.copy(selfJar, applierJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        long pid = ProcessHandle.current().pid();
        ProcessBuilder pb = new ProcessBuilder(javaw(), "-cp", applierJar.toString(),
            "com.coffeesaerosmp.core.mode.ModeApplier",
            String.valueOf(pid), gameDir.toString(), target.name().toLowerCase(Locale.ROOT));
        pb.directory(gameDir.toFile());
        pb.redirectOutput(work.resolve("mode.log").toFile());
        pb.redirectError(work.resolve("mode.log").toFile());
        pb.start();
    }

    /** Same fallback as the updater: NeoForge's module classloader does not always expose a
     *  plain {@code file:} code source, so scanning mods/ by name is the reliable path. */
    private static Path resolveSelfJar(Path gameDir) throws IOException {
        try {
            var loc = ClientMode.class.getProtectionDomain().getCodeSource().getLocation();
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
        throw new IOException("could not locate CoffeesAeroCore jar for the mode applier");
    }

    private static String javaw() {
        String home = System.getProperty("java.home", "");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path p = Paths.get(home, "bin", win ? "javaw.exe" : "java");
        return Files.exists(p) ? p.toString() : (win ? "javaw.exe" : "java");
    }
}
