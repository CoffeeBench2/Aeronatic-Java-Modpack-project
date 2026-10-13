package com.coffeesaerosmp.core.cosmetics;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

/**
 * Every particle cosmetic, drawn by this client (1.14.0). The server only says who wears what; everything here is
 * computed from the player as this client sees them, every tick:
 *
 * <ul>
 *   <li><b>Trails</b> are laid along the exact segment the feet moved this tick, so they stay continuous at any
 *       speed and sit where the player actually walked, not where a server packet said they were.</li>
 *   <li><b>Auras</b> are anchored to the player's position (spirals, rings, falling snow) and keep running while
 *       they stand still.</li>
 *   <li><b>Contrails</b> pour out of the ship's stern along the stern's own path ({@link ShipProbe}).</li>
 *   <li><b>Wing accents</b>: embers, glints or flakes shed from the wingtips while they beat.</li>
 * </ul>
 *
 * <p>All vanilla particle types (nothing to register, so no registry mismatch with the server) and never one that
 * mimics a game signal: no crits, totems, omens, explosions or damage indicators. Counts scale with the player's
 * density setting.
 */
public final class ParticleFx {

    private ParticleFx() {}

    /** Per-wearer state: fractional emission carried between ticks, and a step counter for rhythms. */
    public static final class Acc {
        float carry;
        long step;
        double walked;           // blocks walked, for patterns that repeat along the ground
        long beat;               // footstep index, see stepped()
    }

    // ── trails ───────────────────────────────────────────────────────────────

    /**
     * Lay this tick's piece of trail from feet position a to b. {@code moved} = horizontal distance a→b.
     * {@code fx, fz} = unit direction of travel.
     */
    public static void trail(String id, ClientLevel lvl, RandomSource r, Acc acc, float density,
                             double ax, double ay, double az, double bx, double by, double bz, double fx, double fz, long t) {
        double moved = Math.hypot(bx - ax, bz - az);
        acc.walked += moved;
        double sx = -fz, sz = fx;                                   // sideways (right of travel)
        switch (id) {
            case "trail:cloudwalk" -> along(acc, density, 4.0f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double o = (r.nextDouble() - 0.5) * 0.5;
                add(lvl, ParticleTypes.CLOUD, p[0] + sx * o, p[1] + 0.05, p[2] + sz * o, -fx * 0.02, 0.01, -fz * 0.02);
                // Create fan air: wisps that curl away behind each step
                if (r.nextFloat() < 0.35f) CreateFx.air(lvl, p[0] + sx * o, p[1] + 0.1, p[2] + sz * o, -fx * 0.8 + sx * o, 0.25, -fz * 0.8 + sz * o, 0.5f, 0.05f);
                if (r.nextFloat() < 0.5f) add(lvl, dust(0xF4F8FF, 1.5f), p[0] + sx * o * 1.4, p[1] + 0.1, p[2] + sz * o * 1.4, 0, 0, 0);
            });
            case "trail:brass" -> along(acc, density, 5f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double o = (r.nextDouble() - 0.5) * 0.45;
                add(lvl, dust(r.nextBoolean() ? 0xF0C05A : 0xC9973B, 1.0f), p[0] + sx * o, p[1] + 0.08, p[2] + sz * o, 0, 0, 0);
                // brass offcuts: Create's tumbling cubes, kicked up from the heel
                if (r.nextFloat() < 0.4f) CreateFx.cube(lvl, p[0] + sx * o, p[1] + 0.1, p[2] + sz * o, (r.nextDouble() - 0.5) * 0.08,
                    0.12 + r.nextDouble() * 0.08, (r.nextDouble() - 0.5) * 0.08, r.nextBoolean() ? 0xF0C05A : 0xB8862E, 0.018f, 14, false);
                if (r.nextFloat() < 0.35f) add(lvl, ParticleTypes.WAX_OFF, p[0] + sx * o, p[1] + 0.25, p[2] + sz * o,
                    (r.nextDouble() - 0.5) * 2, 1 + r.nextDouble(), (r.nextDouble() - 0.5) * 2);
            });
            case "trail:clockwork" -> {
                // A little gear tooth-ring stamped at each step, plus copper scrape glints.
                if (stepped(acc, 0.9)) {
                    double cxp = bx - fx * 0.3, czp = bz - fz * 0.3;
                    // Create's own kinetic rotation ring at each footstep, as if the ground were a spinning shaft
                    boolean ringed = CreateFx.ring(lvl, cxp, by + 0.05, czp, 0xE3A857, 0.6f, 0.28f, 0.45f, 12,
                            net.minecraft.core.Direction.Axis.Y, Math.max(8, Math.round(16 * density)));
                    int teeth = ringed ? 0 : Math.max(6, Math.round(10 * density));
                    double spin = t * 0.15;
                    for (int i = 0; i < teeth; i++) {
                        double a = spin + i * Math.PI * 2 / teeth;
                        add(lvl, dust(i % 2 == 0 ? 0xE3A857 : 0x8A6420, 0.6f), cxp + Math.cos(a) * 0.32, by + 0.05, czp + Math.sin(a) * 0.32, 0, 0, 0);
                    }
                }
                along(acc, density, 0.8f, moved, (k, u) -> {
                    double[] p = lerp(ax, ay, az, bx, by, bz, u);
                    add(lvl, ParticleTypes.SCRAPE, p[0], p[1] + 0.2, p[2], (r.nextDouble() - 0.5) * 2, 1, (r.nextDouble() - 0.5) * 2);
                });
            }
            case "trail:espresso" -> along(acc, density, 5f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double o = (r.nextDouble() - 0.5) * 0.4;
                add(lvl, dust(r.nextFloat() < 0.7f ? 0x4B2E1E : 0x6F4E37, 1.2f), p[0] + sx * o, p[1] + 0.06, p[2] + sz * o, 0, 0, 0);
                if (r.nextFloat() < 0.15f) add(lvl, ParticleTypes.WHITE_SMOKE, p[0], p[1] + 0.3, p[2], 0, 0.02, 0);
                if (r.nextFloat() < 0.3f) CreateFx.cube(lvl, p[0] + sx * o, p[1] + 0.12, p[2] + sz * o, (r.nextDouble() - 0.5) * 0.05, 0.05, (r.nextDouble() - 0.5) * 0.05, r.nextBoolean() ? 0x4B2E1E : 0x6F4E37, 0.015f, 16, false);     // beans
            });
            case "trail:kettle" -> along(acc, density, 1.6f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                // a little Create steam-engine jet puffing up from the heel
                if (CreateFx.steam(lvl, p[0], p[1] + 0.15, p[2], -fx * 0.05, 0.18, -fz * 0.05)) return;
                add(lvl, ParticleTypes.WHITE_SMOKE, p[0] + (r.nextDouble() - 0.5) * 0.3, p[1] + 0.2, p[2] + (r.nextDouble() - 0.5) * 0.3,
                    0, 0.03 + r.nextDouble() * 0.02, 0);
            });
            case "trail:petal" -> along(acc, density, 3f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                add(lvl, ParticleTypes.CHERRY_LEAVES, p[0] + (r.nextDouble() - 0.5) * 0.7, p[1] + 0.4 + r.nextDouble() * 0.9,
                    p[2] + (r.nextDouble() - 0.5) * 0.7, 0, 0, 0);
            });
            case "trail:ember" -> along(acc, density, 4f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double o = (r.nextDouble() - 0.5) * 0.35;
                add(lvl, ParticleTypes.SMALL_FLAME, p[0] + sx * o, p[1] + 0.05, p[2] + sz * o, 0, 0.01, 0);
                if (r.nextFloat() < 0.3f) CreateFx.cube(lvl, p[0] + sx * o, p[1] + 0.1, p[2] + sz * o, (r.nextDouble() - 0.5) * 0.06, 0.07, (r.nextDouble() - 0.5) * 0.06, r.nextBoolean() ? 0xFF9A2E : 0xFFD24A, 0.013f, 12, true);       // glowing cinders
                if (r.nextFloat() < 0.5f) add(lvl, fade(0xFFC447, 0xFF3A1F, 0.9f), p[0] + sx * o, p[1] + 0.2, p[2] + sz * o, 0, 0, 0);
            });
            case "trail:snow" -> along(acc, density, 3f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                add(lvl, ParticleTypes.SNOWFLAKE, p[0] + (r.nextDouble() - 0.5) * 0.8, p[1] + 0.3 + r.nextDouble() * 1.4,
                    p[2] + (r.nextDouble() - 0.5) * 0.8, 0, -0.02, 0);
            });
            case "trail:rainbow" -> {
                // A two-lane ribbon whose colour flows along the ground: hue follows distance walked.
                // (Bigger dust lives longer in vanilla: scale 1.5 is what makes the ribbon stretch out behind you.)
                along(acc, density, 9f, moved, (k, u) -> {
                    double[] p = lerp(ax, ay, az, bx, by, bz, u);
                    double walkedHere = acc.walked - moved * (1 - u);
                    for (int lane = -1; lane <= 1; lane++) {
                        float hue = (float) ((walkedHere * 0.18 + lane * 0.05) % 1.0);
                        add(lvl, dust(hsv(hue, 0.85f, 1f), 1.5f), p[0] + sx * 0.2 * lane, p[1] + 0.06, p[2] + sz * 0.2 * lane, 0, 0, 0);
                    }
                    // and every so often a glowing cube of the same colour hops out of the ribbon
                    if (r.nextFloat() < 0.18f) CreateFx.cube(lvl, p[0], p[1] + 0.1, p[2], (r.nextDouble() - 0.5) * 0.06, 0.07,
                        (r.nextDouble() - 0.5) * 0.06, hsv((float) ((walkedHere * 0.18) % 1.0), 0.85f, 1f), 0.015f, 16, true);
                });
            }
            case "trail:starfall" -> along(acc, density, 2f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                add(lvl, ParticleTypes.END_ROD, p[0] + (r.nextDouble() - 0.5) * 0.8, p[1] + 1.6 + r.nextDouble() * 0.6,
                    p[2] + (r.nextDouble() - 0.5) * 0.8, 0, -0.04 - r.nextDouble() * 0.03, 0);
                if (r.nextFloat() < 0.6f) add(lvl, dust(0xFFF3A8, 1.0f), p[0] + (r.nextDouble() - 0.5) * 0.6, p[1] + 0.1, p[2] + (r.nextDouble() - 0.5) * 0.6, 0, 0, 0);
            });
            case "trail:livewire" -> along(acc, density, 5f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double zig = ((acc.step + k) % 2 == 0 ? 1 : -1) * 0.22;           // a zig-zag arc along the path
                add(lvl, ParticleTypes.ELECTRIC_SPARK, p[0] + sx * zig, p[1] + 0.1, p[2] + sz * zig,
                    (r.nextDouble() - 0.5) * 0.1, 0.05, (r.nextDouble() - 0.5) * 0.1);
                add(lvl, dust(0x7FE9FF, 0.9f), p[0] + sx * zig * 0.5, p[1] + 0.12, p[2] + sz * zig * 0.5, 0, 0, 0);
                if (r.nextFloat() < 0.2f) CreateFx.cube(lvl, p[0] + sx * zig, p[1] + 0.12, p[2] + sz * zig, (r.nextDouble() - 0.5) * 0.1, 0.05, (r.nextDouble() - 0.5) * 0.1, 0x9FF6FF, 0.010f, 8, true);
            });
            case "trail:soulfire" -> along(acc, density, 3.5f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double o = (r.nextDouble() - 0.5) * 0.35;
                add(lvl, ParticleTypes.SOUL_FIRE_FLAME, p[0] + sx * o, p[1] + 0.05, p[2] + sz * o, 0, 0.012, 0);
                if (r.nextFloat() < 0.08f) add(lvl, ParticleTypes.SOUL, p[0], p[1] + 0.3, p[2], 0, 0.03, 0);
            });
            case "trail:notes" -> {
                if (stepped(acc, 1.3)) {
                    float colour = (acc.beat % 24) / 24f;
                    add(lvl, ParticleTypes.NOTE, bx - fx * 0.4 + (r.nextDouble() - 0.5) * 0.4, by + 1.9 + r.nextDouble() * 0.3,
                        bz - fz * 0.4 + (r.nextDouble() - 0.5) * 0.4, colour, 0, 0);
                }
            }
            case "trail:fireflies" -> along(acc, density, 1.5f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                add(lvl, ParticleTypes.GLOW, p[0] + (r.nextDouble() - 0.5) * 1.2, p[1] + 0.4 + r.nextDouble() * 1.2,
                    p[2] + (r.nextDouble() - 0.5) * 1.2, (r.nextDouble() - 0.5) * 0.02, 0.01, (r.nextDouble() - 0.5) * 0.02);
                add(lvl, dust(0xD8FF7A, 0.8f), p[0] + (r.nextDouble() - 0.5) * 1.0, p[1] + 0.5 + r.nextDouble(), p[2] + (r.nextDouble() - 0.5) * 1.0, 0, 0, 0);
            });
            case "trail:bubbles" -> along(acc, density, 4f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                add(lvl, ParticleTypes.BUBBLE_POP, p[0] + (r.nextDouble() - 0.5) * 0.5, p[1] + 0.1 + r.nextDouble() * 0.3,
                    p[2] + (r.nextDouble() - 0.5) * 0.5, 0, 0.04, 0);
                if (r.nextFloat() < 0.5f) add(lvl, dust(0xBFE9FF, 0.9f), p[0], p[1] + 0.15, p[2], 0, 0, 0);
            });
            case "trail:rosequartz" -> along(acc, density, 5f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double o = (r.nextDouble() - 0.5) * 0.45;
                add(lvl, dust(r.nextFloat() < 0.6f ? 0xF27BA8 : 0xFFD0E2, 1.1f), p[0] + sx * o, p[1] + 0.08, p[2] + sz * o, 0, 0, 0);
                if (r.nextFloat() < 0.25f) add(lvl, ParticleTypes.WAX_ON, p[0] + sx * o, p[1] + 0.3, p[2] + sz * o,
                    (r.nextDouble() - 0.5) * 2, 1, (r.nextDouble() - 0.5) * 2);
                // polished rose quartz chips, glinting as they tumble
                if (r.nextFloat() < 0.35f) CreateFx.cube(lvl, p[0] + sx * o, p[1] + 0.1, p[2] + sz * o, (r.nextDouble() - 0.5) * 0.06, 0.06, (r.nextDouble() - 0.5) * 0.06, r.nextBoolean() ? 0xF27BA8 : 0xFFB8D2, 0.015f, 14, true);
            });
            case "trail:honey" -> along(acc, density, 3f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double o = (r.nextDouble() - 0.5) * 0.4;
                // Create's real honey, dripping as you walk, and golden honeycomb cubes
                if (r.nextFloat() < 0.5f && !CreateFx.drip(lvl, p[0] + sx * o, p[1] + 0.9 + r.nextDouble() * 0.4, p[2] + sz * o, "create:honey")) {
                    add(lvl, ParticleTypes.DRIPPING_HONEY, p[0] + sx * o, p[1] + 1.0, p[2] + sz * o, 0, 0, 0);
                }
                add(lvl, dust(r.nextBoolean() ? 0xF5B731 : 0xE08A12, 1.1f), p[0] + sx * o, p[1] + 0.06, p[2] + sz * o, 0, 0, 0);
                if (r.nextFloat() < 0.2f) CreateFx.cube(lvl, p[0], p[1] + 0.1, p[2], 0, 0.05, 0, 0xF5B731, 0.015f, 14, false);
            });
            case "trail:chocolate" -> along(acc, density, 3f, moved, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double o = (r.nextDouble() - 0.5) * 0.4;
                // Create's chocolate, dripping off you, and chocolate-bar chunks
                if (r.nextFloat() < 0.5f && !CreateFx.drip(lvl, p[0] + sx * o, p[1] + 0.9 + r.nextDouble() * 0.4, p[2] + sz * o, "create:chocolate")) {
                    add(lvl, dust(0x5A3018, 1.2f), p[0] + sx * o, p[1] + 0.6, p[2] + sz * o, 0, 0, 0);
                }
                add(lvl, dust(r.nextBoolean() ? 0x5A3018 : 0x7A4428, 1.2f), p[0] + sx * o, p[1] + 0.06, p[2] + sz * o, 0, 0, 0);
                if (r.nextFloat() < 0.25f) CreateFx.cube(lvl, p[0], p[1] + 0.1, p[2], (r.nextDouble() - 0.5) * 0.05, 0.05,
                    (r.nextDouble() - 0.5) * 0.05, 0x6B3A1E, 0.020f, 16, false);
            });
            default -> { }
        }
        acc.step++;
    }

    // ── auras ────────────────────────────────────────────────────────────────

    /**
     * Particle auras around a player standing at feet (x, y, z), {@code h} tall. {@code firstPerson}: this is the
     * local player looking out of their own eyes, so nothing is put at head height in front of the camera.
     */
    public static void aura(String id, ClientLevel lvl, RandomSource r, Acc acc, float density,
                            double x, double y, double z, double h, long t, boolean firstPerson) {
        switch (id) {
            case "aura:updraft" -> {
                // Two intertwined spirals of wind climbing to the shoulders.
                int n = Math.max(1, Math.round(4 * density));
                for (int i = 0; i < n; i++) {
                    double a = t * 0.25 + i * Math.PI * 2 / n;
                    double up = (t * 0.06 + i * 0.5) % 1.0;
                    double rad = 0.75 - up * 0.25;
                    if (firstPerson && up > 0.75) continue;
                    add(lvl, dust(0xEAF6FF, 0.8f), x + Math.cos(a) * rad, y + 0.1 + up * h * 0.9, z + Math.sin(a) * rad, 0, 0, 0);
                }
                if (r.nextFloat() < 0.08f * density) add(lvl, ParticleTypes.CLOUD, x, y + 0.05, z, 0, 0.02, 0);
                // Create fan air climbing the spiral
                if (r.nextFloat() < 0.5f * density) {
                    double a = t * 0.25 + r.nextDouble() * Math.PI * 2;
                    CreateFx.air(lvl, x + Math.cos(a) * 0.7, y + 0.1, z + Math.sin(a) * 0.7, -Math.sin(a) * 0.6, h * 0.9, Math.cos(a) * 0.6, 0.5f, 0.06f);
                }
            }
            case "aura:spectrum" -> {
                // A rainbow ring at the waist, turning: 6 bright points per tick around the circle.
                int n = Math.max(3, Math.round(6 * density));
                for (int i = 0; i < n; i++) {
                    double a = t * 0.08 + i * Math.PI * 2 / n;
                    float hue = (float) (((a / (Math.PI * 2)) % 1.0 + 1.0) % 1.0);
                    add(lvl, dust(hsv(hue, 0.9f, 1f), 0.7f), x + Math.cos(a) * 0.8, y + h * 0.5, z + Math.sin(a) * 0.8, 0, 0, 0);
                }
                // Create rotation rings in a slowly turning hue, stacked at the waist
                if (t % 3 == 0) CreateFx.ring(lvl, x, y + h * 0.5, z, hsv((t * 0.01f) % 1f, 0.9f, 1f), 0.5f, 0.78f, 0.82f, 10,
                    net.minecraft.core.Direction.Axis.Y, Math.max(2, Math.round(4 * density)));
            }
            case "aura:raincloud" -> {
                // The cloud itself is a model (AuraLayer); these are its drops.
                if (r.nextFloat() < 0.6f * density) {
                    add(lvl, ParticleTypes.FALLING_WATER, x + (r.nextDouble() - 0.5) * 0.7, y + h + 0.45, z + (r.nextDouble() - 0.5) * 0.7, 0, 0, 0);
                }
            }
            case "aura:enchanted" -> {
                // Glyphs streaming in from a ring around the player to their chest.
                int n = Math.max(1, Math.round(2 * density));
                for (int i = 0; i < n; i++) {
                    double a = r.nextDouble() * Math.PI * 2;
                    add(lvl, ParticleTypes.ENCHANT, x, y + h * 0.6, z, Math.cos(a) * 1.4, r.nextDouble() * 0.8 - 0.2, Math.sin(a) * 1.4);
                }
            }
            case "aura:snowglobe" -> {
                if (r.nextFloat() < 0.8f * density) {
                    double a = r.nextDouble() * Math.PI * 2, d = 0.4 + r.nextDouble() * 0.9;
                    add(lvl, ParticleTypes.SNOWFLAKE, x + Math.cos(a) * d, y + h + 0.6, z + Math.sin(a) * d, 0, -0.03, 0);
                }
                if (t % 4 == 0) {                         // a faint glass dome rim at the feet
                    for (int i = 0; i < 8; i++) {
                        double a = t * 0.02 + i * Math.PI / 4;
                        add(lvl, dust(0xDFF3FF, 0.4f), x + Math.cos(a) * 1.3, y + 0.05, z + Math.sin(a) * 1.3, 0, 0, 0);
                    }
                }
            }
            case "aura:halo" -> {
                if (!firstPerson && t % 4 == 0) CreateFx.ring(lvl, x, y + h + 0.32, z, 0xFFE38A, 0.3f, 0.3f, 0.36f, 8,
                    net.minecraft.core.Direction.Axis.Y, Math.max(1, Math.round(3 * density)));
                if (!firstPerson && r.nextFloat() < 0.35f * density) {   // gold motes drifting off the ring
                    double a = r.nextDouble() * Math.PI * 2;
                    add(lvl, dust(0xFFE38A, 0.35f), x + Math.cos(a) * 0.34, y + h + 0.32, z + Math.sin(a) * 0.34, 0, 0, 0);
                }
            }
            case "aura:orbit" -> {
                if (t % 5 == 0) CreateFx.ring(lvl, x, y + h * 0.5, z, 0xC9973B, 0.4f, 0.85f, 0.95f, 10,
                    net.minecraft.core.Direction.Axis.Y, Math.max(1, Math.round(3 * density)));    // the cogs' track
                if (r.nextFloat() < 0.12f * density) {
                    double a = t * 0.07 + r.nextInt(3) * Math.PI * 2 / 3;
                    add(lvl, ParticleTypes.SCRAPE, x + Math.cos(a) * 0.95, y + h * 0.55, z + Math.sin(a) * 0.95, 0, 0, 0);
                }
            }
            case "aura:propeller" -> {
                if (!firstPerson && r.nextFloat() < 0.3f * density) {     // downwash under the propeller
                    double a = r.nextDouble() * Math.PI * 2;
                    CreateFx.air(lvl, x + Math.cos(a) * 0.3, y + h + 0.3, z + Math.sin(a) * 0.3, Math.cos(a) * 0.4, -0.9, Math.sin(a) * 0.4, 0.5f, 0.08f);
                }
                if (!firstPerson && r.nextFloat() < 0.1f * density) {
                    add(lvl, ParticleTypes.CLOUD, x + (r.nextDouble() - 0.5) * 0.5, y + h + 0.25, z + (r.nextDouble() - 0.5) * 0.5, 0, -0.03, 0);
                }
            }
            default -> { }
        }
    }

    // ── wing accents ─────────────────────────────────────────────────────────

    /**
     * Shed from a wingtip at (x, y, z) during a beat. {@code power} 0..1 = how hard the wings are beating.
     */
    public static void wingTip(String id, ClientLevel lvl, RandomSource r, float density, double x, double y, double z, float power) {
        float chance = (0.15f + 0.5f * power) * density;
        if (r.nextFloat() > chance) return;
        switch (id) {
            case "wings:phoenix" -> {
                add(lvl, ParticleTypes.FLAME, x, y, z, 0, -0.01, 0);
                add(lvl, fade(0xFFC447, 0xFF3A1F, 0.6f), x, y - 0.1, z, 0, 0, 0);
            }
            case "wings:seraph" -> add(lvl, ParticleTypes.END_ROD, x, y, z, 0, -0.02, 0);
            case "wings:prism" -> add(lvl, dust(hsv(r.nextFloat(), 0.7f, 1f), 0.7f), x, y, z, 0, 0, 0);
            case "wings:frost" -> add(lvl, ParticleTypes.SNOWFLAKE, x, y, z, 0, -0.03, 0);
            case "wings:monarch" -> { if (r.nextFloat() < 0.3f) add(lvl, dust(0xFFB347, 0.5f), x, y, z, 0, 0, 0); }
            case "wings:wyvern" -> { if (r.nextFloat() < 0.4f) add(lvl, ParticleTypes.SMOKE, x, y, z, 0, 0.01, 0); }
            case "wings:ornithopter" -> { if (r.nextFloat() < 0.4f) add(lvl, ParticleTypes.WAX_OFF, x, y, z, 0, -0.5, 0); }
            case "wings:windmill" -> { if (r.nextFloat() < 0.3f) add(lvl, ParticleTypes.CLOUD, x, y, z, 0, 0, 0); }
            default -> { }
        }
    }

    // ── contrails ────────────────────────────────────────────────────────────

    /**
     * This tick's piece of contrail from the stern's previous position a to its current b. (fx, fy, fz) = unit
     * direction of travel, {@code beam} = half the ship's width across it (wide ships get wider wakes).
     */
    public static void contrail(String id, ClientLevel lvl, RandomSource r, Acc acc, float density,
                                double ax, double ay, double az, double bx, double by, double bz,
                                double fx, double fy, double fz, double beam, long t) {
        double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        acc.walked += len;
        double sx = -fz, sz = fx;                                    // across the ship, horizontal
        double w = Math.min(2.5, Math.max(0.6, beam * 0.35));
        switch (id) {
            case "contrail:vapour" -> along(acc, density, 5f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                for (int side = -1; side <= 1; side += 2) {                // twin wingtip lines
                    addFar(lvl, ParticleTypes.CLOUD, p[0] + sx * w * side, p[1], p[2] + sz * w * side, 0, 0, 0);
                    // and Create air spiralling off each tip, like a wingtip vortex
                    if (r.nextFloat() < 0.3f) CreateFx.air(lvl, p[0] + sx * w * side, p[1], p[2] + sz * w * side,
                        -fx * 2 + sx * side * 0.4, 0.2, -fz * 2 + sz * side * 0.4, 0.4f, 0.04f);
                }
            });
            case "contrail:steam" -> along(acc, density, 3f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                // Create steam-engine jets blasting out of the stern
                CreateFx.steam(lvl, p[0] + (r.nextDouble() - 0.5) * w * 0.6, p[1], p[2] + (r.nextDouble() - 0.5) * w * 0.6,
                    -fx * 0.5, -fy * 0.5 + 0.05, -fz * 0.5);
                addFar(lvl, ParticleTypes.WHITE_SMOKE, p[0] + (r.nextDouble() - 0.5) * w, p[1], p[2] + (r.nextDouble() - 0.5) * w, 0, 0.05, 0);
                if (r.nextFloat() < 0.3f) addFar(lvl, ParticleTypes.CLOUD, p[0], p[1] + 0.3, p[2], 0, 0.04, 0);
            });
            case "contrail:espresso" -> along(acc, density, 4f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                addFar(lvl, fade(0x6F4E37, 0xE8D8C4, 2.0f), p[0] + (r.nextDouble() - 0.5) * w, p[1] + (r.nextDouble() - 0.5) * 0.4,
                    p[2] + (r.nextDouble() - 0.5) * w, 0, 0, 0);
                if (r.nextFloat() < 0.25f) CreateFx.cube(lvl, p[0], p[1], p[2], (r.nextDouble() - 0.5) * 0.1, -0.05,
                    (r.nextDouble() - 0.5) * 0.1, r.nextBoolean() ? 0x4B2E1E : 0xE8D8C4, 0.025f, 30, false);   // beans and crema
            });
            case "contrail:sakura" -> along(acc, density, 3f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                addFar(lvl, ParticleTypes.CHERRY_LEAVES, p[0] + (r.nextDouble() - 0.5) * w * 1.6, p[1] + (r.nextDouble() - 0.5),
                    p[2] + (r.nextDouble() - 0.5) * w * 1.6, 0, 0, 0);
            });
            case "contrail:aurora" -> along(acc, density, 6f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double wave = Math.sin((acc.walked - len * (1 - u)) * 0.35) * 0.6;
                for (int i = 0; i < 3; i++) {                              // a wavy ribbon, three strands high
                    float tt = i / 2f;
                    int c = lerpRgb(0x2EE6D6, 0x8A4FFF, (float) ((Math.sin(acc.walked * 0.05) + 1) / 2 * 0.5 + tt * 0.5));
                    addFar(lvl, dust(c, 1.6f), p[0] + sx * wave, p[1] + i * 0.35 + wave * 0.3, p[2] + sz * wave, 0, 0, 0);
                }
            });
            case "contrail:rainbow" -> along(acc, density, 5f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                for (int band = 0; band < 7; band++) {                     // seven bands stacked across the wake
                    double off = (band - 3) * 0.32;
                    addFar(lvl, dust(hsv(band / 7f, 0.9f, 1f), 1.5f), p[0] + sx * off, p[1] + Math.abs(band - 3) * -0.05,
                        p[2] + sz * off, 0, 0, 0);
                }
                if (r.nextFloat() < 0.4f) {
                    int band = r.nextInt(7);
                    CreateFx.cube(lvl, p[0] + sx * (band - 3) * 0.32, p[1], p[2] + sz * (band - 3) * 0.32, 0, -0.02, 0,
                        hsv(band / 7f, 0.9f, 1f), 0.022f, 30, true);
                }
            });
            case "contrail:starlight" -> along(acc, density, 4f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                addFar(lvl, ParticleTypes.END_ROD, p[0] + (r.nextDouble() - 0.5) * w * 1.4, p[1] + (r.nextDouble() - 0.5) * 0.8,
                    p[2] + (r.nextDouble() - 0.5) * w * 1.4, (r.nextDouble() - 0.5) * 0.01, -0.005, (r.nextDouble() - 0.5) * 0.01);
                if (r.nextFloat() < 0.5f) addFar(lvl, dust(0xFFF3A8, 1.0f), p[0], p[1], p[2], 0, 0, 0);
            });
            case "contrail:storm" -> along(acc, density, 4f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                addFar(lvl, ParticleTypes.LARGE_SMOKE, p[0] + (r.nextDouble() - 0.5) * w, p[1] + (r.nextDouble() - 0.5) * 0.6,
                    p[2] + (r.nextDouble() - 0.5) * w, 0, 0.01, 0);
                if (r.nextFloat() < 0.35f) addFar(lvl, ParticleTypes.ELECTRIC_SPARK, p[0], p[1] - 0.3, p[2],
                    (r.nextDouble() - 0.5) * 0.3, -0.1, (r.nextDouble() - 0.5) * 0.3);
            });
            case "contrail:sparkler" -> along(acc, density, 4f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                addFar(lvl, ParticleTypes.FIREWORK, p[0], p[1], p[2], (r.nextDouble() - 0.5) * 0.15, (r.nextDouble() - 0.5) * 0.15,
                    (r.nextDouble() - 0.5) * 0.15);
                if (r.nextFloat() < 0.5f) addFar(lvl, dust(0xFFD36A, 0.8f), p[0], p[1], p[2], 0, 0, 0);
                if (r.nextFloat() < 0.5f) CreateFx.cube(lvl, p[0], p[1], p[2], (r.nextDouble() - 0.5) * 0.25, (r.nextDouble() - 0.3) * 0.2,
                    (r.nextDouble() - 0.5) * 0.25, r.nextBoolean() ? 0xFFD36A : 0xFFF3C4, 0.013f, 20, true);
            });
            case "contrail:levitite" -> along(acc, density, 5f, len, (k, u) -> {
                double[] p = lerp(ax, ay, az, bx, by, bz, u);
                double shimmer = Math.sin((acc.walked - len * (1 - u)) * 0.8);
                int c = lerpRgb(0xE8F4FF, 0xC9A8FF, (float) (shimmer + 1) / 2);
                addFar(lvl, dust(c, 1.4f), p[0] + (r.nextDouble() - 0.5) * w, p[1] + (r.nextDouble() - 0.5) * 0.5, p[2] + (r.nextDouble() - 0.5) * w, 0, 0, 0);
                if (r.nextFloat() < 0.2f) addFar(lvl, ParticleTypes.END_ROD, p[0], p[1], p[2], 0, 0.01, 0);
                if (r.nextFloat() < 0.3f) CreateFx.cube(lvl, p[0] + (r.nextDouble() - 0.5) * w, p[1], p[2] + (r.nextDouble() - 0.5) * w,
                    0, 0.02, 0, c, 0.015f, 30, true);                                          // levitite flakes, glowing
            });
            default -> { }
        }
        acc.step++;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private interface Each { void at(int k, double u); }

    /**
     * Emit {@code perBlock × distance} (at least {@code perBlock / 3} per tick while moving) particles spread evenly
     * along the segment, scaled by density, carrying the fraction to the next tick.
     */
    private static void along(Acc acc, float density, float perBlock, double distance, Each each) {
        double want = Math.max(perBlock / 3.0, perBlock * distance) * density;
        acc.carry += (float) want;
        int n = Math.min(48, (int) acc.carry);
        acc.carry -= n;
        for (int k = 0; k < n; k++) each.at(k, n == 1 ? 1.0 : (k + 0.5) / n);
    }

    /** True once per {@code every} blocks walked (a footstep rhythm). */
    private static boolean stepped(Acc acc, double every) {
        long now = (long) (acc.walked / every);
        if (now == acc.beat) return false;
        acc.beat = now;
        return true;
    }

    private static double[] lerp(double ax, double ay, double az, double bx, double by, double bz, double u) {
        return new double[]{ax + (bx - ax) * u, ay + (by - ay) * u, az + (bz - az) * u};
    }

    private static void add(ClientLevel lvl, ParticleOptions o, double x, double y, double z, double vx, double vy, double vz) {
        lvl.addParticle(o, x, y, z, vx, vy, vz);
    }

    /** Contrails are watched from the ground: draw them past the 32-block particle cut-off. */
    private static void addFar(ClientLevel lvl, ParticleOptions o, double x, double y, double z, double vx, double vy, double vz) {
        lvl.addAlwaysVisibleParticle(o, x, y, z, vx, vy, vz);
    }

    static DustParticleOptions dust(int rgb, float scale) {
        return new DustParticleOptions(vec(rgb), scale);
    }

    static DustColorTransitionOptions fade(int from, int to, float scale) {
        return new DustColorTransitionOptions(vec(from), vec(to), scale);
    }

    private static Vector3f vec(int rgb) {
        return new Vector3f(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f);
    }

    /** HSV → 0xRRGGBB, h in [0,1). No AWT (macOS clients). */
    public static int hsv(float h, float s, float v) {
        h = ((h % 1f) + 1f) % 1f;
        float c = v * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = v - c;
        float r, g, b;
        int seg = (int) (h * 6);
        switch (seg) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return ((int) ((r + m) * 255) << 16) | ((int) ((g + m) * 255) << 8) | (int) ((b + m) * 255);
    }

    static int lerpRgb(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
