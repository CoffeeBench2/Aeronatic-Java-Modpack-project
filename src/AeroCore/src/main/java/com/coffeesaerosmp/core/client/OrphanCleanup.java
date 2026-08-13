package com.coffeesaerosmp.core.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deletes files left behind by mods the pack has dropped. Runs on EVERY launch, as early as the mod
 * system allows, and does not involve the updater.
 *
 * <p><b>Why this cannot live in the updater.</b> {@code InClientUpdater} has a retired-file list too,
 * but it only runs when a player actually performs an update — and the code doing the pruning is the
 * Core that is *currently executing*, not the one being installed. So the release that removed
 * {@code createdeliveryrequired} (Core 1.3.19) deleted the mod jar but had no file-prune logic yet;
 * by the time 1.3.21 was installed there was nothing left to update, so its prune would never fire.
 * The player is left permanently on a clean pack with dirty leftovers.
 *
 * <p><b>Why the files come back if you just delete them.</b> {@code createdeliveryrequired} ships its
 * ponder scripts INSIDE the jar at {@code assets/createdeliveryrequired/kubejs/client_scripts/}, and
 * KubeJS extracts mod-provided scripts into the instance's {@code kubejs/client_scripts/} with a
 * {@code cdr_} prefix. While the mod is installed they are recreated on every launch. Once the mod is
 * gone they stop being regenerated but the extracted copies remain as ordinary user files, still
 * referencing {@code createdeliveryrequired:*} item IDs that no longer resolve — which is the red
 * "KubeJS client script errors" screen.
 *
 * <p>So the deletion must happen unconditionally at startup, and it is safe to repeat: nothing
 * regenerates these once the owning mod is absent.
 *
 * <p>This lives in {@code client/} deliberately — {@code -PnoUpdater} strips {@code update/} and
 * {@code version/}, so a CurseForge build would otherwise never clean up.
 */
public final class OrphanCleanup {

    private static final Logger LOG = LoggerFactory.getLogger("CoffeesAeroCore-Cleanup");

    /**
     * Instance-relative files that no longer belong to any installed mod.
     *
     * <p>Keep in sync with {@code InClientUpdater.RETIRED_FILES}. Both exist on purpose: the updater
     * copy removes them during an update for players who are updating anyway, this copy catches
     * everyone else.
     */
    private static final String[] RETIRED = {
        // createdeliveryrequired, dropped in 1.8.4 (KubeJS-extracted, see class docs)
        "kubejs/client_scripts/cdr_contractor_ponder.js",
        "kubejs/client_scripts/cdr_market_ponder.js",
        "kubejs/client_scripts/cdr_p2p_ponder.js",
        // Visual Effects+ resourcepack, dropped in 1.8.4
        "resourcepacks/Visual Effects+.zip",
    };

    private OrphanCleanup() {}

    /** Deletes any retired file present. Cheap: a handful of existence checks. */
    public static void run(Path gameDir) {
        int removed = 0;
        for (String rel : RETIRED) {
            try {
                if (Files.deleteIfExists(gameDir.resolve(rel))) {
                    removed++;
                    LOG.info("Removed orphaned file from a dropped mod: {}", rel);
                }
            } catch (Exception e) {
                // A locked or read-only file must never stop the game from starting; it simply gets
                // retried next launch.
                LOG.warn("Could not remove {}: {}", rel, e.toString());
            }
        }
        if (removed > 0) {
            LOG.info("Cleaned up {} orphaned file(s). If KubeJS still reports errors this launch, "
                + "they were already read before this ran — the next launch will be clean.", removed);
        }
    }
}
