package com.coffeesaerosmp.core.update;

import com.coffeesaerosmp.core.util.AeroState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the 2026-10-15 updater audit. Each test pins one bug that actually shipped:
 * the manifest wiped with .aero-update (orphan removal never ran), the loader request lost to the
 * same wipe, an old jar deleted after its replacement failed, retired prefixes deleting a live mod.
 */
class UpdaterRegressionTest {

    /** A pid that is certainly not running, so the helpers skip their wait-for-exit. */
    private static final String DEAD_PID = String.valueOf(Long.MAX_VALUE);

    static void jar(Path p, String... entries) throws Exception {
        Files.createDirectories(p.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (String e : entries) { z.putNextEntry(new ZipEntry(e)); z.write(1); z.closeEntry(); }
            if (entries.length == 0) { z.putNextEntry(new ZipEntry("x.txt")); z.closeEntry(); }
        }
    }

    static void write(Path p, String s) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    // ── Applier ────────────────────────────────────────────────────────────────

    @Test
    void applierKeepsPersistentStateAndLoaderRequest(@TempDir Path game) throws Exception {
        Path upd = game.resolve(".aero-update");
        jar(game.resolve("mods/CoffeesAeroCore-1.4.3.jar"));
        jar(game.resolve("mods/createultimine-1.21.1-neoforge-1.3.3.jar"));
        jar(upd.resolve("staging/mods/CoffeesAeroCore-1.4.4.jar"));
        write(upd.resolve("removals.txt"),
            "mods/CoffeesAeroCore-1.4.3.jar\nmods/createultimine-1.21.1-neoforge-1.3.3.jar\n");
        write(AeroState.manifest(game), "mods/CoffeesAeroCore-1.4.4.jar\n");
        write(game.resolve(".aero-loader/loader.txt"), "MODRINTH\nx\n\n21.1.250\n21.1.251\n");
        write(AeroState.file(game, "dh-lod-reset.txt"), "season3");
        write(upd.resolve("apply.log"), "");   // the live log of this very run

        Applier.main(new String[]{DEAD_PID, game.toString(), upd.resolve("staging").toString(),
            upd.resolve("removals.txt").toString()});

        assertTrue(Files.exists(game.resolve("mods/CoffeesAeroCore-1.4.4.jar")), "new Core applied");
        assertFalse(Files.exists(game.resolve("mods/CoffeesAeroCore-1.4.3.jar")), "old Core removed in the same apply");
        assertFalse(Files.exists(game.resolve("mods/createultimine-1.21.1-neoforge-1.3.3.jar")), "dropped mod removed");
        // The 2026-10-15 bug: these were all wiped.
        assertTrue(Files.exists(AeroState.manifest(game)), "manifest survives the apply");
        assertTrue(Files.exists(game.resolve(".aero-loader/loader.txt")), "loader request survives the apply");
        assertTrue(Files.exists(AeroState.file(game, "dh-lod-reset.txt")), "markers survive the apply");
        // Only this run's scratch files are cleaned.
        assertFalse(Files.exists(upd.resolve("staging")), "staging cleaned");
        assertFalse(Files.exists(upd.resolve("removals.txt")), "removals.txt cleaned (audit = success)");
    }

    @Test
    void applierKeepsOldJarWhenReplacementFails(@TempDir Path game) throws Exception {
        Path upd = game.resolve(".aero-update");
        jar(game.resolve("mods/CoffeesAeroCore-1.4.3.jar"));
        jar(upd.resolve("staging/mods/CoffeesAeroCore-1.4.4.jar"));
        // Make the destination impossible to replace: a non-empty DIRECTORY with the jar's name.
        write(game.resolve("mods/CoffeesAeroCore-1.4.4.jar/blocker.txt"), "x");
        write(upd.resolve("removals.txt"), "mods/CoffeesAeroCore-1.4.3.jar\n");

        Applier.main(new String[]{DEAD_PID, game.toString(), upd.resolve("staging").toString(),
            upd.resolve("removals.txt").toString()});

        assertTrue(Files.exists(game.resolve("mods/CoffeesAeroCore-1.4.3.jar")),
            "old Core must survive when the new one failed — otherwise the instance has no Core at all");
    }

    @Test
    void applierNeverDeletesOutsideTheInstance(@TempDir Path root) throws Exception {
        Path game = root.resolve("instance");
        Path outside = root.resolve("precious.txt");
        write(outside, "keep");
        Path upd = game.resolve(".aero-update");
        Files.createDirectories(upd.resolve("staging"));
        write(upd.resolve("removals.txt"), "../precious.txt\n");

        Applier.main(new String[]{DEAD_PID, game.toString(), upd.resolve("staging").toString(),
            upd.resolve("removals.txt").toString()});

        assertTrue(Files.exists(outside));
    }

    @Test
    void applierModKeyMatchesUpdater() throws Exception {
        Method m = InClientUpdater.class.getDeclaredMethod("modKey", String.class);
        m.setAccessible(true);
        for (String n : List.of("CoffeesAeroCore-1.4.4.jar", "sodium-neoforge-0.6.13+mc1.21.1.jar",
                                "createultimine-1.21.1-neoforge-1.3.3.jar", "weird.jar")) {
            assertEquals(m.invoke(null, n), Applier.modKey(n), n);
        }
    }

    // ── InClientUpdater planning ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static List<String> call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = InClientUpdater.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, args);
    }

    @Test
    void orphansComeFromThePersistentManifestAndSkipPlayerData(@TempDir Path game) throws Exception {
        jar(game.resolve("mods/oldmod-1.0.jar"));
        write(game.resolve("xaero/world-map/Multiplayer_x/sync_timestamps.cache"), "x");
        write(game.resolve("options.txt"), "x");
        write(AeroState.manifest(game), String.join("\n",
            "mods/oldmod-1.0.jar", "mods/kept-2.0.jar",
            "xaero/world-map/Multiplayer_x/sync_timestamps.cache", "options.txt", "mods/gone-1.0.jar"));

        Set<String> nowManaged = new LinkedHashSet<>(List.of("mods/kept-2.0.jar"));
        List<String> o = call("orphans", new Class<?>[]{Path.class, Set.class}, game, nowManaged);

        assertEquals(List.of("mods/oldmod-1.0.jar"), o,
            "de-indexed jar removed; map data, player-owned files and already-gone files untouched");
    }

    @Test
    void manifestIncludesPendingRemovalsSoAFailedDeleteIsRetried(@TempDir Path game) throws Exception {
        Method w = InClientUpdater.class.getDeclaredMethod("writeManifest", Path.class, Set.class, List.class);
        w.setAccessible(true);
        w.invoke(null, game, new LinkedHashSet<>(List.of("mods/a-1.jar")), List.of("mods/stuck-1.jar"));

        jar(game.resolve("mods/stuck-1.jar"));   // the Applier could not delete it
        List<String> o = call("orphans", new Class<?>[]{Path.class, Set.class},
            game, new LinkedHashSet<>(List.of("mods/a-1.jar")));
        assertEquals(List.of("mods/stuck-1.jar"), o);
        assertFalse(Files.exists(game.resolve(".aero-update/manifest.txt")), "never back in the wiped dir");
    }

    @Test
    void retiredModsUseRemoteListButNeverAManagedJar(@TempDir Path game) throws Exception {
        jar(game.resolve("mods/createultimine-1.21.1-neoforge-1.3.3.jar"));
        jar(game.resolve("mods/badprefix-shipped-1.0.jar"));
        jar(game.resolve("mods/ftb-ultimine-neoforge-2101.1.15.jar"));
        Field f = InClientUpdater.class.getDeclaredField("remoteRetired");
        f.setAccessible(true);
        f.set(null, List.of("createultimine-", "badprefix-"));
        try {
            Set<String> managed = new LinkedHashSet<>(List.of(
                "mods/badprefix-shipped-1.0.jar", "mods/ftb-ultimine-neoforge-2101.1.15.jar"));
            List<String> r = call("retiredMods", new Class<?>[]{Path.class, Set.class}, game, managed);
            assertEquals(List.of("mods/createultimine-1.21.1-neoforge-1.3.3.jar"), r);
        } finally {
            f.set(null, List.of());
        }
    }

    // ── AeroState ──────────────────────────────────────────────────────────────

    @Test
    void protectedPathsCoverMapsLodsSavesAndEscapes() {
        for (String p : List.of("xaero/world-map/a/b.zip", "XaeroWaypoints/x.txt",
                "Distant_Horizons_server_data/x.sqlite", "saves/World/level.dat", "replay_recordings/a.mcpr",
                "../outside.txt", "/abs.txt", "C:/x.txt", ".aero-state/manifest.txt")) {
            assertTrue(AeroState.isProtected(p), p);
        }
        for (String p : List.of("mods/a.jar", "config/foo.toml", "resourcepacks/x.zip", "kubejs/client_scripts/a.js")) {
            assertFalse(AeroState.isProtected(p), p);
        }
    }

    @Test
    void remoteRetiredRejectsTinyPrefixesAndPaths(@TempDir Path game) throws Exception {
        write(AeroState.remoteRetired(game), "createultimine-\nab\n# comment\nmods/x\n..\\y\nZOOMIFY\n");
        assertEquals(List.of("createultimine-", "zoomify"), AeroState.remoteRetiredPrefixes(game));
    }

    @Test
    void manifestFallsBackToTheOldLocation(@TempDir Path game) throws Exception {
        write(game.resolve(".aero-update/manifest.txt"), "mods/a-1.jar\nmods/sub/b.jar\nconfig/c.toml\n");
        assertEquals(Set.of("a-1.jar"), AeroState.managedModNames(game));
    }
}
