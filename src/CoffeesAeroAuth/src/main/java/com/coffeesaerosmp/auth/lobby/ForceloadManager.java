package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

/**
 * Owns the permanently force-loaded region around the overworld spawn, and can take it back.
 *
 * <h2>Why this class exists — the cost nobody accounted for</h2>
 *
 * {@link LobbyManager#initSpawnArea} force-loads {@code spawnForceloadRadiusChunks} (default
 * 7) around the world spawn so that joining and {@code /spawn} are instant. That was the right fix
 * for the 9–75 s join stalls, and it is cheap <b>as long as spawn stays empty</b>.
 *
 * <p>🔴 <b>It stops being cheap the moment somebody builds there.</b> Radius 7 covers chunks −7..7,
 * i.e. blocks <b>−112 to +127</b> on both axes. A base built anywhere inside that box is force-loaded
 * <b>24 hours a day whether or not a single player is online</b> — every block entity in it ticks
 * forever. On a Create pack that is the most expensive thing a chunk can do: the 2026-08-30 profile
 * put {@code SmartBlockEntityTicker.tick} at <b>23.6% of total server wall time</b>.
 *
 * <p>A force-load is not a setting you can simply turn off, either: {@code setChunkForced} writes
 * into the world save's forced-chunk set and <b>persists</b>. Lowering the config does not release
 * chunks that are already held — which is exactly why {@link #clearAround} exists and why
 * {@link #reconcile} actively unforces rather than just declining to add.
 *
 * <h2>Safety</h2>
 * Unforcing is non-destructive: it removes a ticket, nothing else. The chunks stay on disk, the
 * build is untouched, and the area simply stops ticking when no player is nearby — which is the
 * normal behaviour of every other chunk in the world. Re-adding the force-load is one command.
 */
public final class ForceloadManager {

    private ForceloadManager() {}

    /** Hard ceiling on a single clear, so a typo cannot try to iterate a million chunks. */
    public static final int MAX_CLEAR_RADIUS = 128;

    /**
     * Widest radius {@code spawnForceloadRadiusChunks} is allowed to take, per its config range.
     * The reconcile sweep must cover it so that <b>lowering</b> the config actually releases the
     * ring the old value held. Keep in step with the {@code defineInRange} bound in AuthConfig.
     */
    private static final int CONFIG_MAX_RADIUS = 16;

    // ── Applying the configured region: REMOVED 2026-10-04 ─────────────────────
    //
    // reconcile() — the only code in this mod that ever FORCED a chunk — was deleted on the owner's
    // instruction ("remove the forceloading done by authmod, any coding"). What remains here only
    // RELEASES chunks (clearAround) or reports them (status). LobbyManager runs a one-time release of
    // the rings the old code left behind.

    // ── Operator tools ────────────────────────────────────────────────────────

    /** What is currently force-loaded in this level, and how much of it is near a point. */
    public record Status(int total, int nearby, int radius, int centreX, int centreZ) {}

    public static Status status(ServerLevel level, int centreX, int centreZ, int radius) {
        int cx = centreX >> 4, cz = centreZ >> 4;
        LongSet forced = level.getForcedChunks();
        int near = 0;
        for (long key : forced) {
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            if (Math.abs(x - cx) <= radius && Math.abs(z - cz) <= radius) near++;
        }
        return new Status(forced.size(), near, radius, centreX, centreZ);
    }

    /**
     * Releases every forced chunk within {@code radius} chunks of a block position, <b>whatever put
     * it there</b> — this mod, a hand-run {@code /forceload}, or an older config.
     *
     * <p>Iterates a snapshot of the forced set rather than the live one, because
     * {@code setChunkForced} mutates it and iterating a collection while removing from it is how you
     * get a {@code ConcurrentModificationException} on the server thread.
     *
     * @return how many chunks were released
     */
    public static int clearAround(ServerLevel level, int centreX, int centreZ, int radius) {
        radius = Math.min(radius, MAX_CLEAR_RADIUS);
        int cx = centreX >> 4, cz = centreZ >> 4;

        LongSet snapshot = new LongOpenHashSet(level.getForcedChunks());
        int removed = 0;
        for (long key : snapshot) {
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            if (Math.abs(x - cx) <= radius && Math.abs(z - cz) <= radius) {
                if (level.setChunkForced(x, z, false)) removed++;
            }
        }
        CoffeesAeroAuth.LOGGER.info(
            "[Forceload] Released {} forced chunk(s) within {} chunks of ({}, {}) in {}.",
            removed, radius, centreX, centreZ, level.dimension().location());
        return removed;
    }
}
