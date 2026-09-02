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
 * {@link PrivateRoomManager#initSpawnArea} force-loads {@code spawnForceloadRadiusChunks} (default
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

    // ── Applying the configured region ────────────────────────────────────────

    /**
     * Brings the forced set around spawn in line with {@code spawnForceloadRadiusChunks}.
     *
     * <p>Unforces everything we would previously have held that is now outside the desired radius,
     * then forces what is wanted. Because it reconciles rather than only adding, lowering the config
     * — or setting it to 0 — actually takes effect on the next boot instead of silently leaving the
     * old ring loaded forever.
     *
     * @return number of chunks force-loaded after reconciling
     */
    public static int reconcile(ServerLevel level, int spawnX, int spawnZ, int desiredRadius) {
        int cx = spawnX >> 4, cz = spawnZ >> 4;

        // Sweep out to the widest radius the config could previously have held, so a reduction is
        // actually released. Cheap: this is a bounded box, run once at startup.
        int sweep = Math.max(desiredRadius, CONFIG_MAX_RADIUS);
        int removed = 0, added = 0;

        for (int x = cx - sweep; x <= cx + sweep; x++) {
            for (int z = cz - sweep; z <= cz + sweep; z++) {
                boolean want = desiredRadius > 0
                            && Math.abs(x - cx) <= desiredRadius
                            && Math.abs(z - cz) <= desiredRadius;
                boolean have = level.getForcedChunks().contains(ChunkPos.asLong(x, z));
                if (want && !have)      { level.setChunkForced(x, z, true);  added++; }
                else if (!want && have) { level.setChunkForced(x, z, false); removed++; }
            }
        }

        if (desiredRadius <= 0) {
            CoffeesAeroAuth.LOGGER.info(
                "[Forceload] Spawn force-load DISABLED (spawnForceloadRadiusChunks=0). "
                + "Released {} chunk(s). Spawn will load on demand like anywhere else.", removed);
        } else {
            CoffeesAeroAuth.LOGGER.info(
                "[Forceload] Spawn region reconciled around ({}, {}) — radius {} chunk(s): "
                + "{} added, {} released.", spawnX, spawnZ, desiredRadius, added, removed);
        }
        return (desiredRadius > 0) ? (2 * desiredRadius + 1) * (2 * desiredRadius + 1) : 0;
    }

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
