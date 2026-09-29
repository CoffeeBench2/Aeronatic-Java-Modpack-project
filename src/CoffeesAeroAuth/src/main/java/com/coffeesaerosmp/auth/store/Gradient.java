package com.coffeesaerosmp.auth.store;

import java.util.List;

/**
 * Per-character colour maths for gradient names. Pure — no {@code net.minecraft}, so the arithmetic
 * that decides what every paying Captain and Admiral looks like is unit-testable.
 *
 * <p>A legacy § code cannot express an intermediate colour, so gradients have to be built as a
 * Component with a per-character {@code TextColor}. That is also why gradients never reach the
 * nametag above the head: a scoreboard team prefix is one global string with one colour.
 */
public final class Gradient {

    private Gradient() {}

    /**
     * One packed RGB per character of a name.
     *
     * @param gradientId a curated gradient id from {@link Palette}
     * @param length     number of characters to colour
     * @return array of length {@code length}, or {@code null} when the gradient is unknown
     */
    public static int[] forLength(String gradientId, int length) {
        List<String> stops = Palette.gradient(gradientId);
        if (stops == null || length < 0) return null;
        int[] rgb = new int[stops.size()];
        for (int i = 0; i < rgb.length; i++) {
            rgb[i] = Palette.rgb(stops.get(i));
            if (rgb[i] < 0) return null;        // palette edited badly — refuse rather than render black
        }
        return interpolate(rgb, length);
    }

    /**
     * Spread {@code stops} across {@code length} positions, linearly between adjacent stops.
     *
     * <p>Single-character names take the FIRST stop rather than dividing by zero — a one-character
     * display name is legal (the shortest allowed is 3 for an account, but a display name can be
     * shorter) and it must not throw in the render path.
     */
    static int[] interpolate(int[] stops, int length) {
        int[] out = new int[length];
        if (length == 0) return out;
        if (stops.length == 0) return out;
        if (stops.length == 1 || length == 1) {
            java.util.Arrays.fill(out, stops[0]);
            return out;
        }
        // t runs 0..1 inclusive across the name, so the last character lands exactly on the last stop.
        int segments = stops.length - 1;
        for (int i = 0; i < length; i++) {
            double t = (double) i / (length - 1);
            double scaled = t * segments;
            int seg = (int) Math.floor(scaled);
            if (seg >= segments) seg = segments - 1;         // t == 1.0 lands one past the end
            double local = scaled - seg;
            out[i] = lerp(stops[seg], stops[seg + 1], local);
        }
        return out;
    }

    /** Channel-wise linear blend. {@code t} is clamped, so a rounding overshoot cannot wrap a channel. */
    static int lerp(int from, int to, double t) {
        double k = t < 0 ? 0 : (t > 1 ? 1 : t);
        int r = round(((from >> 16) & 0xFF), ((to >> 16) & 0xFF), k);
        int g = round(((from >> 8) & 0xFF),  ((to >> 8) & 0xFF),  k);
        int b = round((from & 0xFF),         (to & 0xFF),         k);
        return (r << 16) | (g << 8) | b;
    }

    private static int round(int from, int to, double t) {
        int v = (int) Math.round(from + (to - from) * t);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
