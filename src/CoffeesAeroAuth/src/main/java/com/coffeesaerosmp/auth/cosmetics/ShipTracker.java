package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Sable ship a player is on, and how fast it is moving — the input to an airship contrail.
 *
 * <p>Velocity comes from OUR OWN cache of each ship's {@code logicalPose().position()}, diffed between calls, not
 * from Sable's {@code lastPose()}: when our tick hook runs relative to Sable's pose update is not guaranteed, and a
 * self-cached position cannot be "always zero" for that reason.
 *
 * <p>Isolation exactly like {@code afk/SableShip}: every Sable type lives in {@link Impl}, only touched after
 * {@code ModList} says Sable is loaded; the first failure (a Sable API change) disables contrails for the rest of the
 * run with ONE warning — trails, the AFK tracker and the tick loop carry on.
 */
final class ShipTracker {

    /** A ship in motion: centre, velocity per tick, world bounds (min/max may be plot-space; ContrailMath copes). */
    record Motion(UUID ship, double[] centre, double[] velocity, double[] min, double[] max) {}

    private record Seen(double x, double y, double z, long tick) {}

    private static volatile Boolean available;
    private static final Map<UUID, Seen> LAST = new HashMap<>();

    private ShipTracker() {}

    static boolean available() {
        Boolean ok = available;
        if (ok == null) {
            try { ok = net.neoforged.fml.ModList.get().isLoaded("sable"); } catch (Throwable t) { ok = false; }
            available = ok;
        }
        return ok;
    }

    /** The ship this player is on and its motion since the last call for that ship; null when not on a ship. */
    static Motion motion(ServerPlayer player, long tick) {
        if (!available()) return null;
        try {
            return Impl.motion(player, tick);
        } catch (Throwable t) {
            available = false;
            CoffeesAeroAuth.LOGGER.warn("[Cosmetics] Sable ship lookup failed — contrails off for this run: {}", t.toString());
            return null;
        }
    }

    /** Forget ships not seen for a while (called from the engine's 1 Hz refresh). */
    static void prune(long tick) {
        LAST.values().removeIf(s -> tick - s.tick() > 200);
    }

    private static final class Impl {
        static Motion motion(ServerPlayer player, long tick) {
            if (!(player instanceof dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension ext)) {
                return null;
            }
            dev.ryanhcode.sable.sublevel.SubLevel ship = ext.sable$getTrackingSubLevel();
            if (ship == null || ship.isRemoved()) return null;
            var pos = ship.logicalPose().position();
            UUID id = ship.getUniqueId();
            Seen prev = LAST.put(id, new Seen(pos.x(), pos.y(), pos.z(), tick));
            double[] v = {0, 0, 0};
            if (prev != null && tick > prev.tick()) {
                double dt = tick - prev.tick();
                v = new double[]{(pos.x() - prev.x()) / dt, (pos.y() - prev.y()) / dt, (pos.z() - prev.z()) / dt};
            }
            var bb = ship.boundingBox();
            return new Motion(id, new double[]{pos.x(), pos.y(), pos.z()}, v,
                new double[]{bb.minX(), bb.minY(), bb.minZ()}, new double[]{bb.maxX(), bb.maxY(), bb.maxZ()});
        }
    }
}
