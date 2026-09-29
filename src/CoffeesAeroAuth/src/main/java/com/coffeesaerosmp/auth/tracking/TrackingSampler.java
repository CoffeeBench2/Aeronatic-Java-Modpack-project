package com.coffeesaerosmp.auth.tracking;

import net.minecraft.server.MinecraftServer;

/**
 * Drives the periodic tracking samplers off the server tick.
 *
 * <p>Follows the {@code SaveGuard.onServerTick} pattern rather than a {@code ScheduledExecutor}:
 * both samplers read live server state (the stats map, the claim saved-data) and must therefore run
 * on the server thread. A scheduled executor would also reintroduce the failure this codebase
 * already documents — {@code scheduleAtFixedRate} cancels itself forever on the first throw.
 */
public final class TrackingSampler {

    /** 20 ticks/s × 60 s × 5 min. */
    private static final int INTERVAL_TICKS = 20 * 60 * 5;

    private static int tickCounter;

    private TrackingSampler() {}

    public static void reset() {
        tickCounter = 0;
    }

    /** Called from the mod's {@code ServerTickEvent.Post} listener. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        // Nothing is played on the lobby, so there is no activity or footprint worth sampling
        // there — and the lobby writing rows would just add noise to a shared database.
        if (ActivitySampler.disabledHere()) return;
        if (++tickCounter < INTERVAL_TICKS) return;
        tickCounter = 0;
        try {
            ActivitySampler.sampleAll(server);
            FootprintSampler.sampleAll(server);
        } catch (Throwable t) {
            // A sampler must never be able to take the tick loop down.
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.error(
                "[Tracking] periodic sample failed: {}", t.toString());
        }
    }
}
