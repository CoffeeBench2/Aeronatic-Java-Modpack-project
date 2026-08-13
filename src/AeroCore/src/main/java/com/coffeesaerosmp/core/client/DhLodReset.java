package com.coffeesaerosmp.core.client;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * One-shot wipe of Distant Horizons' cached LOD for this server, triggered remotely.
 *
 * <p><b>Why this is needed.</b> A world reset does not invalidate DH's cache. DH files multiplayer LOD
 * under {@code Distant_Horizons_server_data/<server entry name>/<levelId>@<namespace>@@<dimension>},
 * and with {@code serverFolderNameMode = "NAME_ONLY"} the top folder is just the server list entry —
 * which does not change across a season. The {@code levelId} does not save us either: the same id
 * ({@code bfkcgkmofsjok}) was observed under three *different* servers in one instance, so it is not a
 * per-world fingerprint. A brand-new Season 2 world therefore lands in the SAME folder as Season 1 and
 * the old terrain renders as ghost LOD in the distance — mountains and bases that no longer exist.
 * The Season 1 store measured 1.4 GB, so it is also a lot of dead disk.
 *
 * <p><b>Why the trigger is remote and not "on update".</b> The client pack ships BEFORE the season
 * flips. If the wipe fired when a player updated, they would clear the cache, keep playing Season 1
 * for days, re-cache the old world, and have ghosts anyway. So the token comes from {@code version.json}
 * (already fetched once per session with cache-busting) and is flipped on launch day — one wipe, at the
 * right moment, for everyone, without shipping a new jar.
 *
 * <p><b>First-run adoption never wipes.</b> If no marker exists the current token is recorded silently.
 * Only a *change* of token triggers a wipe, so adopting this Core version costs nobody their LOD.
 *
 * <p><b>Timing.</b> DH holds open SQLite handles (plus {@code -wal}/{@code -shm}) on every dimension it
 * has loaded, and Windows refuses to delete an open file — verified: a delete attempt while connected
 * removed only the idle folders and left 2.1 GB behind. So this runs only while no level is loaded
 * (title screen). If a level is loaded the run is skipped and the marker is NOT written, so it retries
 * on the next launch. Same rule if any individual delete fails: a partial wipe that recorded success
 * would leave permanent ghost terrain.
 *
 * <p>Singleplayer LOD lives in {@code saves/<world>/data/} and is deliberately untouched.
 */
public final class DhLodReset {

    private static final Logger LOG = LoggerFactory.getLogger("CoffeesAeroCore-DhReset");

    private static final String DH_ROOT = "Distant_Horizons_server_data";
    private static final String MARKER  = "dh-lod-reset.txt";

    /**
     * Our lobby dimension. Only our server has {@code coffees_aero_auth:auth_lobby}, so a DH folder
     * containing it is proof the player connected HERE — which is how we identify our own stores even
     * when the player joined by typing the address (DH then names the folder "Minecraft Server") or
     * renamed the entry in their server list.
     */
    private static final String OUR_DIMENSION_MARKER = "coffees_aero_auth";

    /**
     * Token compiled into the jar, used ONLY by the CurseForge build.
     *
     * <p>The {@code -PnoUpdater} jar has {@code core/version/**} excluded, so it has no
     * {@link com.coffeesaerosmp.core.version.VersionCheck} and therefore no remote token — and it
     * must not grow one, because fetching from outside the pack is the CF policy that got the
     * updater stripped in the first place. A CF player instead receives a new jar exactly when the
     * pack updates, so a build-time constant lands at the right moment on that channel.
     *
     * <p><b>Bump this to {@code "season2"} only in the CF release published for the Season 2
     * launch</b> — not before, or CF players wipe early and re-cache the old world.
     */
    private static final String BAKED_TOKEN = "season1";

    private DhLodReset() {}

    /**
     * CF-only entry point: applies {@link #BAKED_TOKEN} if this jar has no version subsystem.
     * On the full (Modrinth/GitHub) jar this is a no-op, because {@code VersionCheck} is present and
     * supplies the remote token instead — running both would let a stale baked value fight the
     * remote one.
     */
    public static void applyBakedIfNoVersionCheck(Path gameDir) {
        try {
            Class.forName("com.coffeesaerosmp.core.version.VersionCheck");
            return; // full build — the remote path owns this
        } catch (ClassNotFoundException cfBuild) {
            applyIfRequested(gameDir, BAKED_TOKEN);
        }
    }

    /**
     * @param remoteToken the {@code lodReset} value from version.json; blank/absent disables the feature
     */
    public static void applyIfRequested(Path gameDir, String remoteToken) {
        if (remoteToken == null || remoteToken.isBlank()) return;
        try {
            Path marker = gameDir.resolve(".aero-update").resolve(MARKER);
            String seen = readMarker(marker);

            if (seen == null) {
                // First run with this feature: adopt the token, wipe nothing.
                writeMarker(marker, remoteToken);
                LOG.info("DH LOD reset armed at token '{}' (no wipe on first run).", remoteToken);
                return;
            }
            if (seen.equals(remoteToken)) return;

            // Deleting DH's SQLite while it is open silently fails on Windows.
            if (Minecraft.getInstance().level != null) {
                LOG.info("DH LOD reset '{}' deferred — a level is loaded and DH holds the databases open.",
                    remoteToken);
                return;
            }

            Path root = gameDir.resolve(DH_ROOT);
            if (!Files.isDirectory(root)) {
                writeMarker(marker, remoteToken);
                return;
            }

            List<Path> ours = ourServerFolders(root);
            long bytes = 0;
            int failures = 0;
            for (Path folder : ours) {
                bytes += sizeOf(folder);
                failures += deleteTree(folder);
            }

            if (failures > 0) {
                LOG.warn("DH LOD reset '{}' incomplete — {} file(s) could not be deleted. "
                    + "Not recording it; will retry next launch.", remoteToken, failures);
                return;
            }
            writeMarker(marker, remoteToken);
            LOG.info("DH LOD reset '{}' complete — cleared {} server store(s), {} MB freed.",
                remoteToken, ours.size(), bytes / (1024 * 1024));

        } catch (Exception e) {
            // Housekeeping must never stop the game from starting.
            LOG.warn("DH LOD reset skipped: {}", e.toString());
        }
    }

    /**
     * Server stores belonging to us: any folder holding a dimension subfolder for our lobby, plus any
     * folder whose name still looks like ours (a player who never entered the lobby). DH writes '+'
     * where the entry name had a space.
     */
    private static List<Path> ourServerFolders(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            for (Path server : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(server)) continue;
                if (hasOurDimension(server) || nameLooksLikeOurs(server)) out.add(server);
            }
        }
        return out;
    }

    private static boolean hasOurDimension(Path server) {
        try (Stream<Path> dims = Files.list(server)) {
            return dims.anyMatch(d -> d.getFileName().toString().contains(OUR_DIMENSION_MARKER));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean nameLooksLikeOurs(Path server) {
        String n = server.getFileName().toString().replace('+', ' ').toLowerCase();
        return n.contains("coffee") || n.contains("aero");
    }

    /** Deletes depth-first. Returns the number of entries that could not be removed. */
    private static int deleteTree(Path dir) throws IOException {
        int failures = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException locked) {
                    failures++;
                }
            }
        }
        return failures;
    }

    private static long sizeOf(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0L; }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String readMarker(Path marker) {
        try {
            return Files.exists(marker) ? Files.readString(marker, StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMarker(Path marker, String token) throws IOException {
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, token, StandardCharsets.UTF_8);
    }
}
