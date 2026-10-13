package com.coffeesaerosmp.core.cosmetics;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Sable ship a player is riding, as this client sees it: where its stern is and how fast it moves. Feeds the
 * contrails. Mirrors the server's {@code ShipTracker} + {@code ContrailMath}: velocity comes from our own cache of
 * the ship's pose between ticks; the stern is the far side of the ship's bounds, opposite the travel direction.
 *
 * <p>Isolation: every Sable type lives in {@link Impl}, only touched after {@code ModList} says Sable is loaded,
 * and the first failure (a Sable API change) switches contrails off for the session with one log line.
 */
public final class ShipProbe {

    private ShipProbe() {}

    /** A moving ship: stern point, unit travel direction, half-width across travel, ship id. */
    public record Stern(UUID ship, double x, double y, double z, double fx, double fy, double fz, double beam) {}

    private record Seen(double x, double y, double z, long tick) {}

    /** Below this speed (blocks/tick, 5 m/s) a ship is docked or hovering: no contrail. Same as the server. */
    static final double MIN_SPEED = 0.25;
    private static final double GAP = 1.5, FALLBACK_HALF = 6.0;

    private static Boolean available;
    private static final Map<UUID, Seen> LAST = new HashMap<>();

    static boolean available() {
        if (available == null) {
            try { available = net.neoforged.fml.ModList.get().isLoaded("sable"); } catch (Throwable t) { available = false; }
        }
        return available;
    }

    /** The stern of the ship this player rides, or null (not on a ship, or too slow). */
    public static Stern stern(ClientLevel level, Player player, long tick) {
        if (!available()) return null;
        try {
            return Impl.stern(level, player, tick);
        } catch (Throwable t) {
            return off(t, null);
        }
    }

    /** The id of the ship this player rides (moving or not), or null. */
    public static UUID shipOf(ClientLevel level, Player player) {
        if (!available()) return null;
        try { return Impl.shipOf(level, player); } catch (Throwable t) { return off(t, null); }
    }

    /** Every ship within {@code range} of (x,y,z) that is flying fast enough for a contrail. */
    public static java.util.List<Stern> moving(ClientLevel level, double x, double y, double z, double range, long tick) {
        if (!available()) return java.util.List.of();
        try { return Impl.moving(level, x, y, z, range, tick); } catch (Throwable t) { return off(t, java.util.List.of()); }
    }

    private static <T> T off(Throwable t, T value) {
        available = false;
        com.mojang.logging.LogUtils.getLogger().warn("[Cosmetics] Sable lookup failed, contrails off this session: {}", t.toString());
        return value;
    }

    static void prune(long tick) { LAST.values().removeIf(s -> tick - s.tick() > 100); }

    static void clear() { LAST.clear(); }

    /** Stern from centre, velocity and bounds. Pure, so the maths matches the server's ContrailMath exactly. */
    static double[] tail(double[] c, double[] v, double[] min, double[] max) {
        double speed = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (speed < MIN_SPEED) return null;
        double[] d = {-v[0] / speed, -v[1] / speed, -v[2] / speed};
        double h = FALLBACK_HALF;
        boolean inside = true;
        for (int i = 0; i < 3; i++) if (c[i] < min[i] - 0.5 || c[i] > max[i] + 0.5) inside = false;
        if (inside) {
            h = 0;
            for (int i = 0; i < 3; i++) h += Math.abs(d[i]) * (max[i] - min[i]) / 2.0;
        }
        double t = h + GAP;
        return new double[]{c[0] + d[0] * t, c[1] + d[1] * t, c[2] + d[2] * t};
    }

    /** Sterns already worked out this tick, per ship: the pose cache must advance once per ship per tick. */
    private static final Map<UUID, Stern> THIS_TICK = new HashMap<>();
    private static final java.util.Set<UUID> SLOW_THIS_TICK = new java.util.HashSet<>();
    private static long cachedTick = Long.MIN_VALUE;

    private static final class Impl {
        static UUID shipOf(ClientLevel level, Player player) {
            var s = find(level, player);
            return s == null ? null : s.getUniqueId();
        }

        static dev.ryanhcode.sable.sublevel.SubLevel find(ClientLevel level, Player player) {
            dev.ryanhcode.sable.sublevel.SubLevel ship = null;
            if (player instanceof dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension ext) {
                ship = ext.sable$getTrackingSubLevel();
            }
            if (ship == null) {
                // Other players: the client doesn't track their collision, so find the ship whose bounds hold them.
                var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
                if (container == null) return null;
                double px = player.getX(), py = player.getY(), pz = player.getZ();
                for (var s : container.getAllSubLevels()) {
                    if (s == null || s.isRemoved()) continue;
                    var bb = s.boundingBox();
                    if (px >= bb.minX() - 1 && px <= bb.maxX() + 1 && py >= bb.minY() - 2 && py <= bb.maxY() + 2
                        && pz >= bb.minZ() - 1 && pz <= bb.maxZ() + 1) { ship = s; break; }
                }
            }
            return ship == null || ship.isRemoved() ? null : ship;
        }

        static Stern stern(ClientLevel level, Player player, long tick) {
            var ship = find(level, player);
            return ship == null ? null : sternOf(ship, tick);
        }

        static java.util.List<Stern> moving(ClientLevel level, double x, double y, double z, double range, long tick) {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container == null) return java.util.List.of();
            java.util.List<Stern> out = new java.util.ArrayList<>();
            double r2 = range * range;
            for (var s : container.getAllSubLevels()) {
                if (s == null || s.isRemoved()) continue;
                var pos = s.logicalPose().position();
                double dx = pos.x() - x, dy = pos.y() - y, dz = pos.z() - z;
                if (dx * dx + dy * dy + dz * dz > r2) continue;
                Stern st = sternOf(s, tick);
                if (st != null) out.add(st);
            }
            return out;
        }

        /** One velocity sample per ship per tick, however many players (or the fitted-ship pass) ask. */
        static Stern sternOf(dev.ryanhcode.sable.sublevel.SubLevel ship, long tick) {
            if (tick != cachedTick) { THIS_TICK.clear(); SLOW_THIS_TICK.clear(); cachedTick = tick; }
            UUID id = ship.getUniqueId();
            Stern hit = THIS_TICK.get(id);
            if (hit != null || SLOW_THIS_TICK.contains(id)) return hit;
            Stern st = compute(ship, id, tick);
            if (st == null) SLOW_THIS_TICK.add(id); else THIS_TICK.put(id, st);
            return st;
        }

        static Stern compute(dev.ryanhcode.sable.sublevel.SubLevel ship, UUID id, long tick) {
            var pos = ship.logicalPose().position();
            Seen prev = LAST.put(id, new Seen(pos.x(), pos.y(), pos.z(), tick));
            if (prev == null || tick <= prev.tick()) return null;
            double dt = tick - prev.tick();
            double[] v = {(pos.x() - prev.x()) / dt, (pos.y() - prev.y()) / dt, (pos.z() - prev.z()) / dt};
            var bb = ship.boundingBox();
            double[] min = {bb.minX(), bb.minY(), bb.minZ()}, max = {bb.maxX(), bb.maxY(), bb.maxZ()};
            double[] c = {pos.x(), pos.y(), pos.z()};
            double[] t = tail(c, v, min, max);
            if (t == null) return null;
            double sp = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            double fx = v[0] / sp, fy = v[1] / sp, fz = v[2] / sp;
            double h = Math.hypot(fx, fz);
            double hx = h < 1e-4 ? 1 : fx / h, hz = h < 1e-4 ? 0 : fz / h;
            double beam = (Math.abs(-hz * (max[0] - min[0])) + Math.abs(hx * (max[2] - min[2]))) / 2;
            return new Stern(id, t[0], t[1], t[2], fx, fy, fz, Math.min(8, Math.max(1, beam)));
        }
    }
}
