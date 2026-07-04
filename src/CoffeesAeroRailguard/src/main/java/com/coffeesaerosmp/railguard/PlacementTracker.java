package com.coffeesaerosmp.railguard;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Marks the window during which Railways Untold is placing blocks. RU does ALL of its generation
 * inside its {@code ServerTickHandler.onServerTickEnd} (server thread), so bracketing that method
 * (see {@code RailwaysTickHandlerMixin}) and recording every non-air {@code Level.setBlock} inside
 * the bracket (see {@code LevelSetBlockMixin}) captures exactly the generated railway — and nothing
 * a player ever placed.
 */
public final class PlacementTracker {

    private PlacementTracker() {}

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static long recorded = 0;

    public static void begin() { ACTIVE.set(Boolean.TRUE); }

    public static void end() { ACTIVE.set(Boolean.FALSE); }

    public static boolean active() { return ACTIVE.get(); }

    public static void record(ServerLevel level, BlockPos pos) {
        RailguardData.get(level).add(pos.asLong());
        if (++recorded % 5000 == 0) {
            CoffeesAeroRailguard.LOGGER.info("[Railguard] {} railway blocks recorded so far ({} protected in {}).",
                recorded, RailguardData.get(level).size(), level.dimension().location());
        }
    }
}
