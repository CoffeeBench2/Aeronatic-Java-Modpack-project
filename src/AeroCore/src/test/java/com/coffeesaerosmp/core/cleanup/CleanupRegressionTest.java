package com.coffeesaerosmp.core.cleanup;

import com.coffeesaerosmp.core.util.AeroState;
import com.coffeesaerosmp.core.version.VersionCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** StaleMods / StaleModsCleaner / version.json retired list / PostExitAudit regressions (2026-10-15). */
class CleanupRegressionTest {

    @Test
    void cleanerDeletesQueuedJarsOnlyInsideMods(@TempDir Path root) throws Exception {
        Path game = root.resolve("inst");
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("mods/createultimine-1.21.1-neoforge-1.3.3.jar"), "x");
        Files.writeString(root.resolve("outside.jar"), "keep");
        Path q = game.resolve(".aero-cleanup/delete.txt");
        Files.createDirectories(q.getParent());
        Files.writeString(q, "createultimine-1.21.1-neoforge-1.3.3.jar\n../../outside.jar\n", StandardCharsets.UTF_8);

        StaleModsCleaner.main(new String[]{String.valueOf(Long.MAX_VALUE), game.toString(), q.toString()});

        assertFalse(Files.exists(game.resolve("mods/createultimine-1.21.1-neoforge-1.3.3.jar")));
        assertTrue(Files.exists(root.resolve("outside.jar")), "queue entries resolve by name inside mods/ only");
        assertFalse(Files.exists(q), "queue removed when done");
    }

    @Test
    void compiledRetiredListNeverMatchesKeptUltimineMods() {
        for (String kept : List.of("ftb-ultimine-neoforge-2101.1.15.jar", "ultimine_rewind-2.1.0.jar",
                "create_ultimate_factory-2.2.4-neoforge-1.21.1.jar", "sable_sa_compat-1.0.4.jar",
                "ssrd-1.8.7-1.21.1.jar", "distanthorizons-3.3.3-1.21.1-fabric-neoforge.jar")) {
            for (String p : StaleMods.RETIRED) {
                assertFalse(kept.toLowerCase().startsWith(p), p + " would delete " + kept);
            }
        }
        assertTrue(StaleMods.RETIRED.stream().anyMatch("createultimine-1.21.1-neoforge-1.3.3.jar"::startsWith));
    }

    @Test
    void versionJsonRetiredParsing() {
        assertNull(VersionCheck.parseRetired("{\"version\":\"3.0.5\"}"), "absent = keep cache");
        assertEquals(List.of(), VersionCheck.parseRetired("{\"retired\":[]}"), "present+empty = clear");
        assertEquals(List.of("createultimine-", "zoomify"),
            VersionCheck.parseRetired("{\"retired\":[\"createultimine-\",\"ab\",\"mods/x\",\"ZOOMIFY\",3]}"));
        assertNull(VersionCheck.parseRetired("not json"));
    }

    @Test
    void cachedRetiredRoundTrip(@TempDir Path game) {
        VersionCheck.cacheRetired(game, List.of("createultimine-"));
        assertEquals(List.of("createultimine-"), AeroState.remoteRetiredPrefixes(game));
    }

    @Test
    void switchingToNormalNeverRevivesARetiredModOrOverwritesALiveJar(@TempDir Path game) throws Exception {
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Files.writeString(mods.resolve("distanthorizons-3.3.3.jar.aerodisabled"), "dh");          // normal re-enable
        Files.writeString(mods.resolve("createultimine-1.21.1-neoforge-1.3.3.jar.aerodisabled"), "x"); // compiled-retired
        Files.writeString(mods.resolve("remotegone-1.0.jar.aerodisabled"), "x");                   // remote-retired
        Files.writeString(mods.resolve("sodium-0.6.13.jar"), "NEW");
        Files.writeString(mods.resolve("sodium-0.6.13.jar.aerodisabled"), "STALE");
        VersionCheck.cacheRetired(game, List.of("remotegone-"));

        com.coffeesaerosmp.core.mode.ModeApplier.main(new String[]{String.valueOf(Long.MAX_VALUE), game.toString(), "normal"});

        assertEquals("dh", Files.readString(mods.resolve("distanthorizons-3.3.3.jar")));
        assertFalse(Files.exists(mods.resolve("createultimine-1.21.1-neoforge-1.3.3.jar")));
        assertFalse(Files.exists(mods.resolve("createultimine-1.21.1-neoforge-1.3.3.jar.aerodisabled")));
        assertFalse(Files.exists(mods.resolve("remotegone-1.0.jar")));
        assertEquals("NEW", Files.readString(mods.resolve("sodium-0.6.13.jar")), "live jar never overwritten");
    }

    @Test
    void postExitAuditIgnoresThisSessionsQueue(@TempDir Path dir) throws Exception {
        Method m = Class.forName("com.coffeesaerosmp.core.util.PostExitAudit")
            .getDeclaredMethod("writtenThisSession", Path.class);
        m.setAccessible(true);
        Path fresh = dir.resolve("delete.txt");
        Files.writeString(fresh, "a.jar");
        assertTrue((boolean) m.invoke(null, fresh), "queued this session = not a failure");

        Path old = dir.resolve("old.txt");
        Files.writeString(old, "a.jar");
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L * 24));
        assertFalse((boolean) m.invoke(null, old), "left over from a previous session = real failure");
    }
}
