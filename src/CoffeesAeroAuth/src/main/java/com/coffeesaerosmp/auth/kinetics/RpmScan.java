package com.coffeesaerosmp.auth.kinetics;

import com.coffeesaerosmp.auth.config.AuthConfig;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Counts kinetic blocks that the configured cap would affect — <b>before</b> anyone turns
 * destruction on.
 *
 * <h2>Why this exists</h2>
 * Create destroys a block that exceeds {@code maxRotationSpeed}. Lowering the cap under a live
 * server therefore has a blast radius measured in other people's machines, and there is no way to
 * put them back. "Run the scan first" is the difference between an announced change and an
 * incident.
 *
 * <p>⚠ <b>This only sees LOADED chunks near online players.</b> A farm in an unloaded chunk is
 * invisible to it and will still be affected the moment someone walks over there. Treat the number
 * as a floor, never as a total.
 *
 * <h2>Cost</h2>
 * Bounded by construction: {@code players × (2r+1)²} chunks with {@code r = 8}, deduplicated, and
 * it never generates or loads a chunk ({@code getChunk(..., false)}). It is an operator command run
 * by hand, not anything on the tick path.
 *
 * <p>This class is only ever touched behind a {@code ModList.isLoaded("create")} check, so the
 * {@link KineticBlockEntity} reference is never linked on a server without Create.
 */
public final class RpmScan {

    private RpmScan() {}

    /** Chunk radius around each online player. 8 comfortably covers a build without being a sweep. */
    private static final int RADIUS = 8;
    /** Stop listing individual offenders after this many; the count keeps going. */
    private static final int MAX_SAMPLES = 10;

    public record Result(int scannedChunks, int kineticBlocks, int overWorldCap, int onShips,
                         List<String> samples) {}

    public static Result run(MinecraftServer server) {
        int worldCap = AuthConfig.RPM_CAP_WORLD.get();

        Set<Long> seen = new LinkedHashSet<>();
        List<String> samples = new ArrayList<>();
        int kinetic = 0, over = 0, ships = 0, chunks = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.serverLevel();
            int pcx = player.blockPosition().getX() >> 4;
            int pcz = player.blockPosition().getZ() >> 4;

            for (int cx = pcx - RADIUS; cx <= pcx + RADIUS; cx++) {
                for (int cz = pcz - RADIUS; cz <= pcz + RADIUS; cz++) {
                    // Dedupe across players AND across dimensions — a chunk key alone would collide
                    // between the overworld and the nether at the same coordinates.
                    long key = (((long) cx) << 32 ^ (cz & 0xFFFFFFFFL))
                             * 31 + level.dimension().location().hashCode();
                    if (!seen.add(key)) continue;

                    LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                    if (chunk == null) continue;                  // not loaded — never force it
                    chunks++;

                    for (var entry : chunk.getBlockEntities().entrySet()) {
                        if (!(entry.getValue() instanceof KineticBlockEntity kbe)) continue;
                        kinetic++;

                        float speed = Math.abs(kbe.getSpeed());
                        if (speed <= worldCap) continue;

                        if (RpmCap.inSubLevel(kbe)) { ships++; continue; }   // exempt by design

                        over++;
                        if (samples.size() < MAX_SAMPLES) {
                            BlockPos p = entry.getKey();
                            samples.add(String.format("%.0f RPM at %d,%d,%d (%s)",
                                speed, p.getX(), p.getY(), p.getZ(),
                                level.dimension().location().getPath()));
                        }
                    }
                }
            }
        }
        return new Result(chunks, kinetic, over, ships, samples);
    }
}
