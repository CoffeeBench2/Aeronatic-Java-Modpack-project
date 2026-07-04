package com.coffeesaerosmp.railguard;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension persistent set of protected railway block positions ({@code BlockPos.asLong}).
 * Populated live by {@link PlacementTracker} as Railways Untold generates track, and manually by
 * {@code /railguard mark} for sections that existed before this mod was installed.
 */
public class RailguardData extends SavedData {

    private static final String NAME = "aero_railguard";
    private static final SavedData.Factory<RailguardData> FACTORY =
        new SavedData.Factory<>(RailguardData::new, RailguardData::load, null);

    private final LongOpenHashSet positions = new LongOpenHashSet();

    public static RailguardData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static RailguardData load(CompoundTag tag, HolderLookup.Provider provider) {
        RailguardData data = new RailguardData();
        for (long l : tag.getLongArray("positions")) data.positions.add(l);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putLongArray("positions", positions.toLongArray());
        return tag;
    }

    public boolean contains(long packedPos) { return positions.contains(packedPos); }

    public void add(long packedPos) {
        if (positions.add(packedPos)) setDirty();
    }

    public void remove(long packedPos) {
        if (positions.remove(packedPos)) setDirty();
    }

    public int size() { return positions.size(); }
}
