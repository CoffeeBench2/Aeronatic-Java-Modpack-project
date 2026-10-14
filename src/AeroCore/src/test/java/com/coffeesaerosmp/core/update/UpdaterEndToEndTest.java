package com.coffeesaerosmp.core.update;

import com.coffeesaerosmp.core.util.AeroState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real updater against a real (local) packwiz pack, followed by the real Applier — twice.
 *
 * <p>This is the scenario that failed for months: an instance installed from the mrpack (no
 * manifest), an update, then a later update that DROPS a mod. Before 1.4.4 the second update never
 * removed it, because the Applier wiped the manifest the first update wrote.
 */
class UpdaterEndToEndTest {

    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    private String serve(Path root) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Path f = root.resolve(ex.getRequestURI().getPath().substring(1)).normalize();
            byte[] body = Files.isRegularFile(f) && f.startsWith(root) ? Files.readAllBytes(f) : null;
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) try (OutputStream o = ex.getResponseBody()) { o.write(body); }
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    static String hex(String algo, byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance(algo).digest(b)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Writes pack.toml + index.toml + metafiles for the given jars (+ one override file). */
    static void publish(Path pack, String url, List<String> jars, List<String> retired) throws Exception {
        if (Files.exists(pack.resolve("mods"))) {
            try (var s = Files.list(pack.resolve("mods"))) { for (Path p : s.toList()) Files.delete(p); }
        }
        Files.createDirectories(pack.resolve("mods"));
        StringBuilder idx = new StringBuilder("hash-format = \"sha256\"\n");
        for (String jar : jars) {
            byte[] bytes = Files.readAllBytes(pack.resolve("files").resolve(jar));
            String meta = "name = \"" + jar + "\"\nfilename = \"" + jar + "\"\nside = \"both\"\n\n[download]\n"
                + "url = \"" + url + "files/" + jar + "\"\nhash-format = \"sha512\"\nhash = \"" + hex("SHA-512", bytes) + "\"\n";
            String metaName = "mods/" + jar.replace(".jar", "") + ".pw.toml";
            Files.writeString(pack.resolve(metaName), meta, StandardCharsets.UTF_8);
            idx.append("\n[[files]]\nfile = \"").append(metaName).append("\"\nhash = \"")
               .append(hex("SHA-256", meta.getBytes(StandardCharsets.UTF_8))).append("\"\nmetafile = true\n");
        }
        Path cfg = pack.resolve("overrides/config/aero-test.toml");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, "x = 1\n");
        idx.append("\n[[files]]\nfile = \"overrides/config/aero-test.toml\"\nhash = \"")
           .append(hex("SHA-256", Files.readAllBytes(cfg))).append("\"\n");
        String index = idx.toString();
        Files.writeString(pack.resolve("index.toml"), index, StandardCharsets.UTF_8);
        Files.writeString(pack.resolve("pack.toml"), "name = \"t\"\nversion = \"1\"\n\n[index]\nfile = \"index.toml\"\n"
            + "hash-format = \"sha256\"\nhash = \"" + hex("SHA-256", index.getBytes(StandardCharsets.UTF_8)) + "\"\n");
        StringBuilder r = new StringBuilder();
        for (String p : retired) r.append(r.length() == 0 ? "" : ",").append('"').append(p).append('"');
        Files.writeString(pack.resolve("version.json"), "{\"version\":\"1\",\"retired\":[" + r + "]}");
    }

    private void update(Path game, String url) {
        InClientUpdater.runWith(game, url + "pack.toml", url + "version.json", false);
        assertTrue(InClientUpdater.success, "updater failed: " + InClientUpdater.error);
        Path upd = game.resolve(".aero-update");
        Applier.main(new String[]{String.valueOf(Long.MAX_VALUE), game.toString(),
            upd.resolve("staging").toString(), upd.resolve("removals.txt").toString()});
    }

    @Test
    void droppedModIsRemovedOnTheNextUpdate(@TempDir Path tmp) throws Exception {
        Path pack = tmp.resolve("pack"), game = tmp.resolve("instance");
        Path files = pack.resolve("files");
        UpdaterRegressionTest.jar(files.resolve("CoffeesAeroCore-1.4.4.jar"), "com/coffeesaerosmp/core/update/Applier.class", "v144");
        UpdaterRegressionTest.jar(files.resolve("oldmod-1.0.jar"), "old");
        UpdaterRegressionTest.jar(files.resolve("keeper-2.0.jar"), "keep");
        String url = serve(pack);

        // An mrpack install: the previous Core, a mod the pack has since retired, a map — NO manifest.
        UpdaterRegressionTest.jar(game.resolve("mods/CoffeesAeroCore-1.4.3.jar"), "com/coffeesaerosmp/core/update/Applier.class");
        UpdaterRegressionTest.jar(game.resolve("mods/createultimine-1.21.1-neoforge-1.3.3.jar"));
        UpdaterRegressionTest.jar(game.resolve("mods/players-own-mod-1.0.jar"));
        UpdaterRegressionTest.write(game.resolve("xaero/world-map/Multiplayer_x/sync_timestamps.cache"), "map");
        UpdaterRegressionTest.write(game.resolve("options.txt"), "keybinds");

        // ── Update 1: ships Core 1.4.4 + oldmod + keeper; createultimine is on the remote retired list.
        publish(pack, url, List.of("CoffeesAeroCore-1.4.4.jar", "oldmod-1.0.jar", "keeper-2.0.jar"),
                List.of("createultimine-"));
        update(game, url);

        assertTrue(Files.exists(game.resolve("mods/CoffeesAeroCore-1.4.4.jar")));
        assertFalse(Files.exists(game.resolve("mods/CoffeesAeroCore-1.4.3.jar")), "old Core gone in the same update");
        assertFalse(Files.exists(game.resolve("mods/createultimine-1.21.1-neoforge-1.3.3.jar")),
            "remote-retired mod gone in the SAME update — no Core release, no second restart");
        assertTrue(Files.exists(game.resolve("mods/oldmod-1.0.jar")));
        assertTrue(Files.exists(game.resolve("config/aero-test.toml")));
        assertTrue(Files.exists(AeroState.manifest(game)), "manifest survived the Applier");

        // ── Update 2: the pack drops oldmod and says nothing else. Orphan tracking must remove it.
        publish(pack, url, List.of("CoffeesAeroCore-1.4.4.jar", "keeper-2.0.jar"), List.of("createultimine-"));
        update(game, url);

        assertFalse(Files.exists(game.resolve("mods/oldmod-1.0.jar")),
            "THE BUG: a de-indexed mod must be removed by the next update");
        assertTrue(Files.exists(game.resolve("mods/keeper-2.0.jar")));
        assertTrue(Files.exists(game.resolve("mods/CoffeesAeroCore-1.4.4.jar")));
        assertTrue(Files.exists(game.resolve("mods/players-own-mod-1.0.jar")), "a player's own mod is never touched");
        assertEquals("map", Files.readString(game.resolve("xaero/world-map/Multiplayer_x/sync_timestamps.cache")),
            "map / MapSyncer state untouched by updates");
        assertEquals("keybinds", Files.readString(game.resolve("options.txt")));

        // ── Update 3: nothing changed — must be a clean no-op that keeps the manifest.
        InClientUpdater.runWith(game, url + "pack.toml", url + "version.json", false);
        assertTrue(InClientUpdater.success);
        List<String> manifest = new ArrayList<>(AeroState.readManifest(game));
        assertTrue(manifest.contains("mods/keeper-2.0.jar"));
        assertFalse(manifest.contains("mods/oldmod-1.0.jar") && Files.exists(game.resolve("mods/oldmod-1.0.jar")));
    }
}
