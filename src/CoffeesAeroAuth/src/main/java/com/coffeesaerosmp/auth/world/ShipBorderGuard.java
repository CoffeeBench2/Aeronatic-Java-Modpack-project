package com.coffeesaerosmp.auth.world;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.border.WorldBorder;
import net.neoforged.fml.ModList;

/**
 * The world border as a solid wall for Sable ships (owner, 2026-10-15: "aeronautics ships go thru
 * worldborder"). Vanilla's border only collides ENTITIES; a sub-level is a rigid body in Sable's own
 * physics world and never asks, so ships sail straight through — taking their crew with them.
 *
 * <p>Every server tick, for each ship whose hull is at or past the border, the outward part of its
 * velocity is reflected ({@link BorderMath}). Inward motion is never touched, so a ship already outside
 * can come back. Only Sable's public API is used ({@code SubLevelContainer.getContainer},
 * {@code ServerSubLevel.logicalPose/boundingBox}, {@code RigidBodyHandle}).
 *
 * <p>Sable types are touched only inside {@link Impl}, after ModList says Sable is loaded, so the lobby
 * and test servers without Sable never load them.
 */
public final class ShipBorderGuard {

    private static boolean warned;

    private ShipBorderGuard() {}

    public static void onServerTick(MinecraftServer server) {
        if (server == null || !AuthConfig.SHIP_BORDER_WALL.get() || !ModList.get().isLoaded("sable")) return;
        double bounce = AuthConfig.SHIP_BORDER_BOUNCE.get();
        for (ServerLevel level : server.getAllLevels()) {
            try {
                Impl.tick(level, bounce);
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    CoffeesAeroAuth.LOGGER.warn("[ShipBorder] disabled for this session: {}", t.toString());
                }
                return;
            }
        }
    }

    /** @return ships corrected this tick (for tests) */
    public static int tickLevel(ServerLevel level, double bounce) {
        return ModList.get().isLoaded("sable") ? Impl.tick(level, bounce) : 0;
    }

    static final class Impl {
        static int tick(ServerLevel level, double bounce) {
            var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
            if (container == null) return 0;
            WorldBorder wb = level.getWorldBorder();
            double minX = wb.getMinX(), maxX = wb.getMaxX(), minZ = wb.getMinZ(), maxZ = wb.getMaxZ();
            int corrected = 0;
            for (dev.ryanhcode.sable.sublevel.ServerSubLevel ship : container.getAllSubLevels()) {
                if (ship == null || ship.isRemoved()) continue;
                var pos = ship.logicalPose().position();
                var size = ship.boundingBox().size();
                // Half of the larger horizontal extent: conservative for a ship turned 45°, never late.
                double half = Math.max(size.x(), size.z()) / 2.0;
                double x = pos.x(), z = pos.z();
                // Cheap reject: hull nowhere near the border.
                if (x - half > minX + 1 && x + half < maxX - 1 && z - half > minZ + 1 && z + half < maxZ - 1) continue;

                var body = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(ship);
                if (body == null || !body.isValid()) continue;
                var v = body.getLinearVelocity();
                double nvx = BorderMath.axis(x, half, v.x(), minX, maxX, bounce);
                double nvz = BorderMath.axis(z, half, v.z(), minZ, maxZ, bounce);
                if (nvx == v.x() && nvz == v.z()) continue;
                body.addLinearAndAngularVelocity(new org.joml.Vector3d(nvx - v.x(), 0, nvz - v.z()),
                    new org.joml.Vector3d());
                corrected++;
            }
            return corrected;
        }
    }
}
