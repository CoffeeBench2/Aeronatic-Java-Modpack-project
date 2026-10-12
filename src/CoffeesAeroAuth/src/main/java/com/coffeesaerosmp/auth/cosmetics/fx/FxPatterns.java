package com.coffeesaerosmp.auth.cosmetics.fx;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;

/**
 * Every cosmetic as a little composed effect instead of one particle puffed at one spot (owner 2026-10-12: "a bit
 * generic ... add a cool vibe"). All vanilla particles, so every client sees them with no mod; each emit sends at
 * most {@link #BUDGET} packets (checked by GameTest for every cosmetic), and the engine's packet budget, viewer
 * radius and lag pause still apply on top.
 *
 * <p>Count &gt; 0 = a burst spread around the point; count 0 = ONE particle with the given (dx, dy, dz) as its
 * velocity, which is how wisps rise and sparks shoot in a direction.
 */
public final class FxPatterns {

    private FxPatterns() {}

    /** Most packets one emit may send. */
    public static final int BUDGET = 3;

    /**
     * Where and how the effect is happening.
     *
     * @param x,y,z     feet (trails) or the tail of the ship (contrails)
     * @param fx,fz     horizontal direction of travel, unit
     * @param halfWidth half the ship's beam (contrails), 0 for trails
     * @param height    the wearer's height (trails), for effects at head level
     * @param tick      server tick (animation clock)
     * @param step      how many times this wearer has emitted (alternation: left/right foot)
     */
    public record Ctx(double x, double y, double z, double fx, double fz, double halfWidth, double height, long tick, long step) {}

    @FunctionalInterface
    public interface Sink {
        void send(ParticleOptions o, boolean longRange, double x, double y, double z, float dx, float dy, float dz, float speed, int count);
    }

    // ── particle options, built once ─────────────────────────────────────────

    private static DustParticleOptions dust(int rgb, float scale) {
        return new DustParticleOptions(new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f), scale);
    }

    private static DustColorTransitionOptions fade(int from, int to, float scale) {
        return new DustColorTransitionOptions(
            new Vector3f(((from >> 16) & 255) / 255f, ((from >> 8) & 255) / 255f, (from & 255) / 255f),
            new Vector3f(((to >> 16) & 255) / 255f, ((to >> 8) & 255) / 255f, (to & 255) / 255f), scale);
    }

    private static final ParticleOptions COFFEE_DUST = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.BROWN_TERRACOTTA.defaultBlockState());
    private static final ParticleOptions PETAL_GLOW = dust(0xFFC4DD, 0.6f);
    private static final ParticleOptions EMBER = dust(0xFF7A1A, 0.55f);
    private static final ParticleOptions BRASS_GLINT = dust(0xF5C451, 0.5f);
    private static final ParticleOptions CREMA = dust(0xF3E5D0, 0.7f);
    private static final ParticleOptions ESPRESSO = fade(0x6F4E37, 0xE8D8C4, 1.4f);
    private static final ParticleOptions[] AURORA = {
        fade(0x2EE6D6, 0x8A4FFF, 1.4f), fade(0x8A4FFF, 0x4CFF9A, 1.4f), fade(0x4CFF9A, 0x2EE6D6, 1.4f)};

    /** True if this id has a designed pattern (it was emitted); false = use the catalog's plain effect. */
    public static boolean emit(String id, Ctx c, Sink s) {
        switch (id) {
            // ── Trails ───────────────────────────────────────────────────────
            case "trail:cloudwalk" -> {               // Sky Step: a soft puff on each footstep, the odd wisp rising
                double[] r = FxGeometry.right(c.fx(), c.fz());
                int f = FxGeometry.foot(c.step());
                s.send(ParticleTypes.CLOUD, false, c.x() + r[0] * 0.22 * f, c.y() + 0.05, c.z() + r[2] * 0.22 * f, 0.08f, 0.02f, 0.08f, 0.004f, 2);
                if (c.step() % 3 == 0) s.send(ParticleTypes.END_ROD, false, c.x(), c.y() + 0.2, c.z(), 0f, 0.035f, 0f, 1f, 0);
            }
            case "trail:brass" -> {                   // Gearwork: a cog of sparks turning round your feet
                double a = c.tick() * 0.21;
                for (int k = 0; k < 2; k++) {
                    double[] p = FxGeometry.ring(0.45, a + k * Math.PI);
                    s.send(ParticleTypes.WAX_OFF, false, c.x() + p[0], c.y() + 0.12, c.z() + p[2], 0f, 0f, 0f, 0f, 1);
                }
                if (c.step() % 4 == 0) s.send(BRASS_GLINT, false, c.x(), c.y() + 0.1, c.z(), 0.15f, 0.02f, 0.15f, 0f, 2);
            }
            case "trail:clockwork" -> {               // Clockwork: a hand that ticks round a clock face at your feet
                double a = FxGeometry.clockAngle(c.tick(), 12, 10);
                for (int k = 1; k <= 3; k++) {
                    double d = 0.17 * k;
                    s.send(ParticleTypes.SCRAPE, false, c.x() + Math.cos(a) * d, c.y() + 0.08, c.z() + Math.sin(a) * d, 0f, 0f, 0f, 0f, 1);
                }
            }
            case "trail:espresso" -> {                // Spilled Beans: grounds scattering, a curl of steam now and then
                s.send(COFFEE_DUST, false, c.x(), c.y() + 0.5, c.z(), 0.2f, 0.1f, 0.2f, 0f, 2);
                if (c.step() % 3 == 0) s.send(ParticleTypes.WHITE_SMOKE, false, c.x(), c.y() + 0.3, c.z(), 0f, 0.025f, 0f, 1f, 0);
            }
            case "trail:kettle" -> {                  // Kettle: steam winding up around you in a spiral
                double[] p = FxGeometry.helix(0.38, 1.4, c.tick() / 30.0, 2);
                s.send(ParticleTypes.WHITE_SMOKE, false, c.x() + p[0], c.y() + p[1], c.z() + p[2], 0f, 0.012f, 0f, 1f, 0);
                double[] q = FxGeometry.helix(0.38, 1.4, c.tick() / 30.0 + 0.5, 2);
                s.send(ParticleTypes.WHITE_SMOKE, false, c.x() + q[0], c.y() + q[1], c.z() + q[2], 0f, 0.012f, 0f, 1f, 0);
            }
            case "trail:petal" -> {                   // Petal Path: blossoms drifting down, a pink shimmer circling
                s.send(ParticleTypes.CHERRY_LEAVES, false, c.x(), c.y() + 1.0, c.z(), 0.35f, 0.15f, 0.35f, 0f, 2);
                double[] p = FxGeometry.ring(0.5, c.tick() * 0.3);
                s.send(PETAL_GLOW, false, c.x() + p[0], c.y() + 0.4, c.z() + p[2], 0.02f, 0.02f, 0.02f, 0f, 1);
            }
            case "trail:ember" -> {                   // Ember Step: a little flame on each footstep, sparks rising
                double[] r = FxGeometry.right(c.fx(), c.fz());
                int f = FxGeometry.foot(c.step());
                s.send(ParticleTypes.SMALL_FLAME, false, c.x() + r[0] * 0.2 * f, c.y() + 0.05, c.z() + r[2] * 0.2 * f, 0.03f, 0f, 0.03f, 0.002f, 1);
                s.send(EMBER, false, c.x() + r[0] * 0.2 * f, c.y() + 0.2, c.z() + r[2] * 0.2 * f, 0.1f, 0.25f, 0.1f, 0f, 1);
                if (c.step() % 2 == 0) s.send(ParticleTypes.WHITE_ASH, false, c.x(), c.y() + 0.8, c.z(), 0.3f, 0.4f, 0.3f, 0f, 3);
            }
            case "trail:snow" -> {                    // Snowfall: your own little cloud, snowing on you
                double top = c.y() + c.height() + 0.6;
                s.send(ParticleTypes.CLOUD, false, c.x(), top, c.z(), 0.3f, 0.04f, 0.3f, 0f, 1);
                s.send(ParticleTypes.SNOWFLAKE, false, c.x(), top - 0.15, c.z(), 0.35f, 0.05f, 0.35f, 0f, 2);
            }

            // ── Contrails ────────────────────────────────────────────────────
            case "contrail:vapour" -> {               // Vapour Trail: twin lines off both wingtips
                double[] r = FxGeometry.right(c.fx(), c.fz());
                double w = Math.max(1, c.halfWidth());
                s.send(ParticleTypes.CLOUD, true, c.x() + r[0] * w, c.y(), c.z() + r[2] * w, 0.05f, 0.05f, 0.05f, 0f, 2);
                s.send(ParticleTypes.CLOUD, true, c.x() - r[0] * w, c.y(), c.z() - r[2] * w, 0.05f, 0.05f, 0.05f, 0f, 2);
            }
            case "contrail:steam" -> {                // Boiler Steam: chug, chug: big puffs on a beat, a thin line between
                boolean beat = (c.tick() / 4) % 3 == 0;
                if (beat) s.send(ParticleTypes.WHITE_SMOKE, true, c.x(), c.y() + 1.2, c.z(), 0.45f, 0.3f, 0.45f, 0.02f, 8);
                else s.send(ParticleTypes.SMOKE, true, c.x(), c.y() + 1.2, c.z(), 0.1f, 0.1f, 0.1f, 0.01f, 2);
            }
            case "contrail:espresso" -> {             // Espresso Wake: coffee into crema, with foam on top
                s.send(ESPRESSO, true, c.x(), c.y(), c.z(), 0.25f, 0.15f, 0.25f, 0f, 4);
                s.send(CREMA, true, c.x(), c.y() + 0.35, c.z(), 0.4f, 0.1f, 0.4f, 0f, 2);
            }
            case "contrail:sakura" -> {               // Sakura Drift: a wide scatter of petals and pink glow
                s.send(ParticleTypes.CHERRY_LEAVES, true, c.x(), c.y() + 0.5, c.z(), 0.8f, 0.4f, 0.8f, 0f, 5);
                s.send(PETAL_GLOW, true, c.x(), c.y(), c.z(), 0.5f, 0.3f, 0.5f, 0f, 2);
            }
            case "contrail:aurora" -> {               // Aurora Ribbon: a snaking ribbon whose colours roll along it
                double[] r = FxGeometry.right(c.fx(), c.fz());
                double[] o = FxGeometry.ribbon(Math.max(1, c.halfWidth()), c.tick());
                ParticleOptions col = AURORA[FxGeometry.cycle(c.tick(), AURORA.length, 30)];
                s.send(col, true, c.x() + r[0] * o[0], c.y() + o[1], c.z() + r[2] * o[0], 0.12f, 0.12f, 0.12f, 0f, 4);
                if (c.step() % 2 == 0) s.send(ParticleTypes.END_ROD, true, c.x() + r[0] * o[0], c.y() + o[1], c.z() + r[2] * o[0],
                    (float) -c.fx() * 0.05f, 0.01f, (float) -c.fz() * 0.05f, 1f, 0);
            }
            default -> { return false; }
        }
        return true;
    }
}
