package com.coffeesaerosmp.auth.cosmetics.fx;

import java.util.ArrayList;
import java.util.List;

/**
 * Choreography for the fitting room's sprite effects (resource-pack models on display entities). Pure: given a
 * cosmetic and the time, where every sprite is. The fitting room sends these as keyframes every few ticks and the
 * client interpolates between them, which is why the motion is smooth instead of flickering like particles.
 *
 * <p>Coordinates are relative to the mannequin's feet (trails) or the ship's tail (contrails), in blocks. "Back"
 * is opposite the direction of travel ({@code fx, fz}); "right" is to its right.
 */
public final class SpriteChoreo {

    private SpriteChoreo() {}

    /** Which model a sprite shows (mirrors RpModels / build_rp.py CMD numbers). */
    public static final int COG = 9010, CLOUD = 9011, SNOWFLAKE = 9012, PETAL = 9013, STEAM = 9014, BEAN = 9015,
        FLAME = 9016, CLOCK_FACE = 9017, CLOCK_HAND = 9018, RIBBON_AQUA = 9019, RIBBON_VIOLET = 9020,
        RIBBON_GREEN = 9021, EMBER = 9022, VAPOUR = 9023, CREMA = 9024, SPARK = 9025;

    /**
     * One sprite at one moment.
     *
     * @param flat  lies flat on the ground (clock face) instead of turning to face the camera
     * @param spin  rotation in its own plane, radians
     * @param wrap  it just looped back to its start: jump there, don't slide
     */
    public record Frame(int model, double x, double y, double z, float scale, float spin, boolean flat, boolean wrap) {}

    /** Number of sprites an effect uses (0 = none designed; particles only). */
    public static int count(String id) {
        return switch (id) {
            case "trail:cloudwalk" -> 6;
            case "trail:brass" -> 8;
            case "trail:clockwork" -> 2;
            case "trail:espresso" -> 8;
            case "trail:kettle" -> 8;
            case "trail:petal" -> 10;
            case "trail:ember" -> 9;
            case "trail:snow" -> 10;
            case "contrail:vapour" -> 12;
            case "contrail:steam" -> 8;
            case "contrail:espresso" -> 10;
            case "contrail:sakura" -> 12;
            case "contrail:aurora" -> 12;
            default -> 0;
        };
    }

    /** Phase in [0,1) for sprite k of n, cycling at {@code rate} loops per second. */
    static double phase(double t, double rate, int k, int n) {
        double p = t * rate + (double) k / n;
        return p - Math.floor(p);
    }

    /** True when sprite k wrapped between {@code tPrev} and {@code t}. */
    static boolean wrapped(double tPrev, double t, double rate, int k, int n) {
        return Math.floor(tPrev * rate + (double) k / n) != Math.floor(t * rate + (double) k / n);
    }

    private static double hash(int k) {               // stable per-sprite variety, 0..1
        double h = Math.sin(k * 12.9898 + 78.233) * 43758.5453;
        return h - Math.floor(h);
    }

    /**
     * @param t       seconds since the preview started
     * @param dt      seconds since the previous keyframe (to detect wraps)
     * @param fx,fz   direction of travel (unit, horizontal)
     * @param height  the mannequin's height in blocks (snow cloud sits above it)
     */
    public static List<Frame> frames(String id, double t, double dt, double fx, double fz, double height) {
        List<Frame> out = new ArrayList<>();
        int n = count(id);
        double bx = -fx, bz = -fz;                     // back
        double rx = -fz, rz = fx;                      // right
        double tp = t - dt;
        switch (id) {
            case "trail:cloudwalk" -> {                // puffs on alternate feet, streaming back and shrinking
                for (int k = 0; k < n; k++) {
                    double p = phase(t, 0.7, k, n);
                    double side = (k % 2 == 0 ? 0.22 : -0.22);
                    double d = p * 1.6;
                    out.add(new Frame(CLOUD, bx * d + rx * side, 0.08 + p * 0.25, bz * d + rz * side,
                        (float) (0.55 * (1 - p) + 0.12), 0, false, wrapped(tp, t, 0.7, k, n)));
                }
            }
            case "trail:brass" -> {                    // a ring of cogs turning round the feet, two sparks twinkling
                for (int k = 0; k < 6; k++) {
                    double a = t * 1.1 + k * Math.PI / 3;
                    out.add(new Frame(COG, Math.cos(a) * 0.72, 0.18 + Math.sin(t * 2 + k) * 0.04, Math.sin(a) * 0.72,
                        0.42f, (float) (t * (k % 2 == 0 ? 3 : -3)), false, false));
                }
                for (int k = 0; k < 2; k++) {
                    double a = t * 1.1 + Math.PI / 6 + k * Math.PI;
                    float s = (float) Math.max(0.05, 0.32 * Math.sin(t * 6 + k * 2));
                    out.add(new Frame(SPARK, Math.cos(a) * 0.72, 0.42, Math.sin(a) * 0.72, s, (float) t, false, false));
                }
            }
            case "trail:clockwork" -> {                // a clock face at your feet; the hand ticks round
                out.add(new Frame(CLOCK_FACE, 0, 0.03, 0, 1.6f, 0, true, false));
                double step = Math.floor(t * 2) / 12.0 * Math.PI * 2;   // one tick every half second
                out.add(new Frame(CLOCK_HAND, 0, 0.05, 0, 1.3f, (float) step, true, false));
            }
            case "trail:espresso" -> {                 // beans tumbling down behind you, a curl of steam
                for (int k = 0; k < 6; k++) {
                    double p = phase(t, 0.8, k, 6);
                    double side = (hash(k) - 0.5) * 0.7;
                    out.add(new Frame(BEAN, bx * (0.2 + p * 0.6) + rx * side, 0.9 * (1 - p * p) + 0.05,
                        bz * (0.2 + p * 0.6) + rz * side, 0.28f, (float) (t * 4 + k), false, wrapped(tp, t, 0.8, k, 6)));
                }
                for (int k = 0; k < 2; k++) {
                    double p = phase(t, 0.4, k, 2);
                    out.add(new Frame(STEAM, bx * 0.5, 0.5 + p * 1.4, bz * 0.5, (float) (0.25 + p * 0.35), 0, false,
                        wrapped(tp, t, 0.4, k, 2)));
                }
            }
            case "trail:kettle" -> {                   // steam winding up around you
                for (int k = 0; k < n; k++) {
                    double p = phase(t, 0.45, k, n);
                    double a = p * Math.PI * 4 + k;
                    out.add(new Frame(STEAM, Math.cos(a) * 0.45, 0.1 + p * 2.1, Math.sin(a) * 0.45,
                        (float) (0.22 + p * 0.38), 0, false, wrapped(tp, t, 0.45, k, n)));
                }
            }
            case "trail:petal" -> {                    // blossoms drifting down and back, swaying
                for (int k = 0; k < n; k++) {
                    double p = phase(t, 0.35, k, n);
                    double sway = Math.sin(t * 2.4 + k) * 0.3;
                    double side = (hash(k) - 0.5) * 1.2;
                    out.add(new Frame(PETAL, bx * p * 1.2 + rx * (side + sway), 2.0 * (1 - p) + 0.05,
                        bz * p * 1.2 + rz * (side + sway), 0.3f, (float) (t * 2 + k), false, wrapped(tp, t, 0.35, k, n)));
                }
            }
            case "trail:ember" -> {                    // flames flickering on the ground, embers rising
                for (int k = 0; k < 3; k++) {
                    double a = k * Math.PI * 2 / 3 + t * 0.6;
                    float s = (float) (0.32 + 0.08 * Math.sin(t * 9 + k * 2));
                    out.add(new Frame(FLAME, Math.cos(a) * 0.35, 0.15, Math.sin(a) * 0.35, s, 0, false, false));
                }
                for (int k = 0; k < 6; k++) {
                    double p = phase(t, 0.6, k, 6);
                    double a = hash(k) * Math.PI * 2;
                    out.add(new Frame(EMBER, Math.cos(a) * 0.3, 0.2 + p * 1.8, Math.sin(a) * 0.3,
                        (float) (0.18 * (1 - p) + 0.04), 0, false, wrapped(tp, t, 0.6, k, 6)));
                }
            }
            case "trail:snow" -> {                     // your own little cloud, snowing on you
                double top = height + 0.55;
                out.add(new Frame(CLOUD, -0.25, top + Math.sin(t * 1.5) * 0.05, 0, 0.9f, 0, false, false));
                out.add(new Frame(CLOUD, 0.3, top + 0.08 + Math.sin(t * 1.5 + 1) * 0.05, 0.1, 0.75f, 0, false, false));
                for (int k = 0; k < 8; k++) {
                    double p = phase(t, 0.5, k, 8);
                    double a = hash(k) * Math.PI * 2, r = 0.15 + hash(k + 9) * 0.4;
                    out.add(new Frame(SNOWFLAKE, Math.cos(a) * r + Math.sin(t * 2 + k) * 0.08, top - 0.15 - p * (top - 0.1),
                        Math.sin(a) * r, 0.2f, (float) (t * 1.5 + k), false, wrapped(tp, t, 0.5, k, 8)));
                }
            }
            case "contrail:vapour" -> {                // twin lines off the wingtips
                for (int k = 0; k < n; k++) {
                    int half = n / 2;
                    double p = phase(t, 0.9, k % half, half);
                    double side = k < half ? 0.55 : -0.55;
                    double d = p * 4.0;
                    out.add(new Frame(VAPOUR, bx * d + rx * side, -0.1, bz * d + rz * side,
                        (float) (0.55 * (1 - p) + 0.15), 0, false, wrapped(tp, t, 0.9, k % half, half)));
                }
            }
            case "contrail:steam" -> {                 // puffs from the stack, chugging back
                for (int k = 0; k < n; k++) {
                    double p = phase(t, 0.55, k, n);
                    out.add(new Frame(STEAM, bx * (0.6 + p * 3.0), 0.5 + p * 1.1, bz * (0.6 + p * 3.0),
                        (float) (0.3 + p * 0.6), 0, false, wrapped(tp, t, 0.55, k, n)));
                }
            }
            case "contrail:espresso" -> {              // coffee into crema
                for (int k = 0; k < n; k++) {
                    double p = phase(t, 0.6, k, n);
                    double wob = Math.sin(t * 2 + k) * 0.15;
                    out.add(new Frame(p < 0.5 ? BEAN : CREMA, bx * p * 3.6 + rx * wob, Math.cos(t + k) * 0.1,
                        bz * p * 3.6 + rz * wob, (float) (0.3 + p * 0.25), (float) (t + k), false, wrapped(tp, t, 0.6, k, n)));
                }
            }
            case "contrail:sakura" -> {                // a wide scatter of petals
                for (int k = 0; k < n; k++) {
                    double p = phase(t, 0.45, k, n);
                    double side = (hash(k) - 0.5) * 2.2 * p;
                    out.add(new Frame(PETAL, bx * p * 3.4 + rx * side, Math.sin(t * 2 + k) * 0.25 - p * 0.6,
                        bz * p * 3.4 + rz * side, 0.3f, (float) (t * 2 + k), false, wrapped(tp, t, 0.45, k, n)));
                }
            }
            case "contrail:aurora" -> {                // a ribbon that snakes and rolls its colours
                int[] cols = {RIBBON_AQUA, RIBBON_VIOLET, RIBBON_GREEN};
                for (int k = 0; k < n; k++) {      // close-set and overlapping, so it reads as one band
                    double d = 0.2 + k * 0.24;
                    double side = Math.sin(t * 1.8 - k * 0.5) * 0.45;
                    double up = Math.cos(t * 1.1 - k * 0.4) * 0.22;
                    int c = cols[(int) Math.floor(t * 0.8 + k / 4.0) % 3];
                    out.add(new Frame(c, bx * d + rx * side, up, bz * d + rz * side,
                        (float) (0.5 - k * 0.028), (float) (Math.sin(t * 1.8 - k * 0.5) * 0.35), false, false));
                }
            }
            default -> { }
        }
        return out;
    }
}
