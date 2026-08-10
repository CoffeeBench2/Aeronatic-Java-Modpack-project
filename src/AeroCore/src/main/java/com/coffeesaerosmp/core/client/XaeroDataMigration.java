package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.net.ServerLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Moves Xaero's per-server data across when the address the Join button uses changes.
 *
 * <p><b>Why this is needed.</b> Xaero's Minimap and World Map file their data under
 * {@code Multiplayer_<address>} — the literal address the client connected to. Waypoints live in
 * there too ({@code minimap/Multiplayer_x/dim%0/mw$<world>_1.txt}). So the address is not just how
 * we reach the server, it is the primary key for everything a player has explored and marked.
 *
 * <p>Core 1.3.17 switched {@link ServerLocator} from the baked raw IP to the DuckDNS hostname, which
 * was right — a hostname survives the Oracle IP changing — but it silently renamed that key. Every
 * player's map and waypoints were still on disk under the old IP folder while the game wrote to a
 * new empty one. Nothing was deleted; it was orphaned, which looks identical from the player's side.
 *
 * <p>This runs once per target address and merges the old folder forward. It <b>never overwrites</b>
 * a file that already exists at the destination: if a player has already explored some of the map
 * under the new address, that newer data wins and only the missing files are brought across.
 *
 * <p>Distant Horizons is NOT affected and needs no migration — it keys on the server ENTRY NAME
 * ({@code Distant_Horizons_server_data/Coffees+Aero+SMP}), and the Join button has always used the
 * same name. A player who instead joins by typing the address gets DH's default "Minecraft Server"
 * store, which is a separate pile of LOD data; that is a reason to use the Join button, not a bug
 * we can safely auto-merge.
 */
public final class XaeroDataMigration {

    private static final Logger LOG = LoggerFactory.getLogger("CoffeesAeroCore-XaeroMigrate");

    /** Addresses the pack has previously shipped as the Join target, oldest first. */
    private static final List<String> LEGACY_HOSTS = List.of("92.4.65.219");

    /** Xaero roots that are partitioned by server address. */
    private static final List<String> ROOTS = List.of("xaero/minimap", "xaero/world-map", "XaeroWaypoints");

    private XaeroDataMigration() {}

    public static void run(Path gameDir) {
        try {
            String target = "Multiplayer_" + hostOf(ServerLocator.resolve());
            Path marker = gameDir.resolve(".aero-update").resolve("xaero-migrated.txt");
            if (alreadyDone(marker, target)) return;

            int moved = 0;
            for (String root : ROOTS) {
                Path base = gameDir.resolve(root);
                if (!Files.isDirectory(base)) continue;
                for (String legacyHost : LEGACY_HOSTS) {
                    Path from = base.resolve("Multiplayer_" + legacyHost);
                    Path to = base.resolve(target);
                    if (!Files.isDirectory(from) || from.equals(to)) continue;
                    moved += merge(from, to);
                }
            }

            Files.createDirectories(marker.getParent());
            Files.writeString(marker, target, StandardCharsets.UTF_8);
            if (moved > 0) LOG.info("Carried {} Xaero file(s) forward to {}", moved, target);
        } catch (Exception e) {
            // Never let a housekeeping step stop the game from starting.
            LOG.warn("Xaero data migration skipped: {}", e.toString());
        }
    }

    private static boolean alreadyDone(Path marker, String target) {
        try {
            return Files.exists(marker) && Files.readString(marker, StandardCharsets.UTF_8).trim().equals(target);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Brings {@code from} across to {@code to}. An absent destination is a plain rename (instant, and
     * costs no disk — a world-map folder can be gigabytes). Otherwise copy only the files the
     * destination lacks, so newer data is never clobbered by older data.
     */
    private static int merge(Path from, Path to) throws IOException {
        if (!Files.exists(to)) {
            try {
                Files.move(from, to);
                LOG.info("Moved {} -> {}", from.getFileName(), to.getFileName());
                return 1;
            } catch (IOException e) {
                // Cross-device or a lock: fall through to the copy path.
                LOG.debug("Rename failed, copying instead: {}", e.toString());
            }
        }
        int copied = 0;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else if (!Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                    copied++;
                }
            }
        }
        return copied;
    }

    /** Strips the port; Xaero folders are named after the host only. */
    private static String hostOf(String address) {
        int colon = address.lastIndexOf(':');
        return colon > 0 ? address.substring(0, colon) : address;
    }
}
