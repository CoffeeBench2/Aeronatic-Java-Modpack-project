package com.coffeesaerosmp.railguard;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension persistent railway "server property": exact block positions ({@code BlockPos.asLong})
 * PLUS every chunk the railway touches ({@code ChunkPos.toLong}). Chunks are derived automatically —
 * recording a position claims its chunk, and on load the chunk set is backfilled from all stored
 * positions (so worlds recorded by older versions gain chunk coverage instantly).
 */
public class RailguardData extends SavedData {

    private static final String NAME = "aero_railguard";
    private static final SavedData.Factory<RailguardData> FACTORY =
        new SavedData.Factory<>(RailguardData::new, RailguardData::load, null);

    private final LongOpenHashSet positions = new LongOpenHashSet();
    private final LongOpenHashSet chunks = new LongOpenHashSet();

    public static RailguardData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static RailguardData load(CompoundTag tag, HolderLookup.Provider provider) {
        RailguardData data = new RailguardData();
        for (long l : tag.getLongArray("positions")) data.positions.add(l);
        for (long l : tag.getLongArray("chunks")) data.chunks.add(l);
        // Backfill: chunk coverage for positions recorded by pre-chunk versions.
        LongIterator it = data.positions.iterator();
        while (it.hasNext()) data.chunks.add(chunkKeyOfPacked(it.nextLong()));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLongArray("positions", positions.toLongArray());
        tag.putLongArray("chunks", chunks.toLongArray());
        return tag;
    }

    public static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static long chunkKeyOfPacked(long packedPos) {
        return ChunkPos.asLong(BlockPos.getX(packedPos) >> 4, BlockPos.getZ(packedPos) >> 4);
    }

    /** Exact recorded railway block, OR anywhere inside a railway chunk. */
    public boolean isProtected(BlockPos pos) {
        return positions.contains(pos.asLong()) || chunks.contains(chunkKey(pos));
    }

    public boolean isPositionProtected(BlockPos pos) { return positions.contains(pos.asLong()); }

    public boolean isChunkProtected(BlockPos pos) { return chunks.contains(chunkKey(pos)); }

    public boolean isChunkKeyProtected(long chunkKey) { return chunks.contains(chunkKey); }

    public void add(BlockPos pos) {
        boolean a = positions.add(pos.asLong());
        boolean b = chunks.add(chunkKey(pos));
        if (a || b) setDirty();
    }

    /** Releases the exact position (chunk coverage stays unless explicitly unmarked). */
    public void releasePosition(BlockPos pos) {
        if (positions.remove(pos.asLong())) setDirty();
    }

    /** Chunks claimed by the machine-placement auto-recorder THIS session — kept so a player
     *  placement in the same spot can roll the claim back (never confiscate player track).
     *  In-memory only; on restart these chunks are already legitimately persisted or gone. */
    private final transient LongOpenHashSet autoClaimedChunks = new LongOpenHashSet();

    /** Is any chunk in the 3×3 neighborhood of this position's chunk protected? Lets machine-placed
     *  curve blocks chain protection into fresh chunks as Create walks the bezier outward. */
    public boolean isNearProtectedChunk(BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (chunks.contains(ChunkPos.asLong(cx + dx, cz + dz))) return true;
        return false;
    }

    /** Auto-record from the machine-placement path (tracks whether the chunk claim was new). */
    public void addAuto(BlockPos pos) {
        boolean a = positions.add(pos.asLong());
        long ck = chunkKey(pos);
        if (chunks.add(ck)) autoClaimedChunks.add(ck);
        if (a) setDirty();
    }

    /** A PLAYER legitimately placed here after a tentative auto-record — give it back. */
    public void rollbackAuto(BlockPos pos) {
        if (positions.remove(pos.asLong())) setDirty();
        long ck = chunkKey(pos);
        if (autoClaimedChunks.remove(ck) && chunks.remove(ck)) setDirty();
    }

    public void addChunk(long chunkKey) {
        if (chunks.add(chunkKey)) setDirty();
    }

    public void removeChunk(long chunkKey) {
        if (chunks.remove(chunkKey)) setDirty();
    }

    public int positionCount() { return positions.size(); }

    public int chunkCount() { return chunks.size(); }
}
