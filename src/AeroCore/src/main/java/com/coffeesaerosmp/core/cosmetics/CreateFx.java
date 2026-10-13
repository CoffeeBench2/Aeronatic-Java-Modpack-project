package com.coffeesaerosmp.core.cosmetics;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;

/**
 * Create's own particles for the cosmetics (owner 2026-10-13: "reuse the textures from Create"): the tumbling
 * cubes trains throw, steam-engine jets, encased-fan air, the kinetic rotation rings you see when a shaft spins up,
 * and fluid drips of Create's real chocolate and honey. They are drawn with Create's own sprites and shaders.
 *
 * <p>Isolation like {@link ShipProbe}: every Create type lives in {@link Impl}, touched only after {@code ModList}
 * says Create is loaded; the first failure (Create renamed something) turns this off for the session with one log
 * line. Every method returns false when it drew nothing, so the caller draws its vanilla fallback.
 */
public final class CreateFx {

    private CreateFx() {}

    private static Boolean available;

    public static boolean available() {
        if (available == null) {
            try { available = net.neoforged.fml.ModList.get().isLoaded("create"); } catch (Throwable t) { available = false; }
        }
        return available;
    }

    private static boolean fail(Throwable t) {
        available = false;
        com.mojang.logging.LogUtils.getLogger().warn("[Cosmetics] Create particles failed, vanilla ones from now on: {}", t.toString());
        return false;
    }

    /** A tumbling Create cube. {@code hot} = glowing. {@code scale} ~0.05–0.2; {@code age} ticks. */
    public static boolean cube(ClientLevel l, double x, double y, double z, double vx, double vy, double vz,
                               int rgb, float scale, int age, boolean hot) {
        if (!available()) return false;
        try { Impl.cube(l, x, y, z, vx, vy, vz, rgb, scale, age, hot); return true; } catch (Throwable t) { return fail(t); }
    }

    /** A steam-engine jet puff blowing along (vx, vy, vz). */
    public static boolean steam(ClientLevel l, double x, double y, double z, double vx, double vy, double vz) {
        if (!available()) return false;
        try { Impl.steam(l, x, y, z, vx, vy, vz); return true; } catch (Throwable t) { return fail(t); }
    }

    /** Encased-fan air: a wisp that twists its way from (x,y,z) to (x+dx, y+dy, z+dz). */
    public static boolean air(ClientLevel l, double x, double y, double z, double dx, double dy, double dz, float drag, float speed) {
        if (!available()) return false;
        try { Impl.air(l, x, y, z, dx, dy, dz, drag, speed); return true; } catch (Throwable t) { return fail(t); }
    }

    /**
     * {@code n} glints of Create's rotation indicator, orbiting (x,y,z) around {@code axis} from radius r1 out to r2
     * over {@code life} ticks — the ring a shaft shows when it changes speed.
     */
    public static boolean ring(ClientLevel l, double x, double y, double z, int rgb, float speed, float r1, float r2,
                               int life, Direction.Axis axis, int n) {
        // Create shows rotation indicators ONLY to players wearing Engineer's Goggles (its particle factory checks
        // GogglesItem.isWearingGoggles), so as a cosmetic they'd be invisible to almost everyone. Never used; the
        // callers' dust rings are drawn instead.
        return false;
    }

    /** A drip of a Create fluid ({@code "create:chocolate"}, {@code "create:honey"}); false if the fluid is gone. */
    public static boolean drip(ClientLevel l, double x, double y, double z, String fluid) {
        if (!available()) return false;
        try { return Impl.drip(l, x, y, z, fluid); } catch (Throwable t) { return fail(t); }
    }

    private static final class Impl {
        static void cube(ClientLevel l, double x, double y, double z, double vx, double vy, double vz, int rgb, float scale, int age, boolean hot) {
            l.addParticle(new com.simibubi.create.content.trains.CubeParticleData(((rgb >> 16) & 255) / 255f,
                ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, scale, age, hot), x, y, z, vx, vy, vz);
        }

        static void steam(ClientLevel l, double x, double y, double z, double vx, double vy, double vz) {
            l.addAlwaysVisibleParticle(new com.simibubi.create.content.kinetics.steamEngine.SteamJetParticleData(1), x, y, z, vx, vy, vz);
        }

        static void air(ClientLevel l, double x, double y, double z, double dx, double dy, double dz, float drag, float speed) {
            l.addParticle(new com.simibubi.create.foundation.particle.AirParticleData(drag, speed), x, y, z, dx, dy, dz);
        }

        static void ring(ClientLevel l, double x, double y, double z, int rgb, float speed, float r1, float r2, int life,
                         Direction.Axis axis, int n) {
            var data = new com.simibubi.create.content.kinetics.base.RotationIndicatorParticleData(rgb, speed, r1, r2, life, axis);
            for (int i = 0; i < n; i++) l.addParticle(data, x, y, z, 0, 0, 0);
        }

        private static final java.util.Map<String, net.neoforged.neoforge.fluids.FluidStack> FLUIDS = new java.util.HashMap<>();

        static boolean drip(ClientLevel l, double x, double y, double z, String fluid) {
            var stack = FLUIDS.computeIfAbsent(fluid, k -> {
                var rl = net.minecraft.resources.ResourceLocation.tryParse(k);
                if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.FLUID.containsKey(rl)) {
                    return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
                }
                return new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.core.registries.BuiltInRegistries.FLUID.get(rl), 1000);
            });
            if (stack.isEmpty()) return false;
            l.addParticle(new com.simibubi.create.content.fluids.particle.FluidParticleData(
                com.simibubi.create.AllParticleTypes.FLUID_DRIP.get(), stack), x, y, z, 0, 0, 0);
            return true;
        }
    }
}
