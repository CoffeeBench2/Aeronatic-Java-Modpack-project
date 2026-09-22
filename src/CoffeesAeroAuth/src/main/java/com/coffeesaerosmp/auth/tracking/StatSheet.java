package com.coffeesaerosmp.auth.tracking;

import java.util.Locale;

/**
 * Formatting for the {@code /authmod player} stat sheet.
 *
 * <p>Pure — no Minecraft imports — so every rule here is unit-testable rather than only visible by
 * running a command on a live server.
 *
 * <p>Every method pins {@link Locale#ROOT}. A server running under a European locale would
 * otherwise render 1,234,567 as {@code 1.234.567}, which reads as a decimal to everyone else.
 */
public final class StatSheet {

    private StatSheet() {}

    /** Seconds as "2h 30m". Truncates rather than rounds; clamps negatives to zero. */
    public static String duration(long seconds) {
        if (seconds <= 0) return "0m";
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    /** Centimetres as kilometres, two decimals. */
    public static String distance(long centimetres) {
        return String.format(Locale.ROOT, "%.2f km", Math.max(0, centimetres) / 100_000.0);
    }

    /**
     * Sidebar level.
     *
     * <p>🔑 {@code 1 + floor(2 * sqrt(hours))}, and PLAYTIME IS THE ONLY INPUT (owner decision
     * 2026-08-19). Do not add other terms here without changing the sidebar in the same commit, or
     * the card and the scoreboard will quietly disagree about a number players care about.
     *
     * <p>Note that since 2026-09-22 playtime excludes lobby time, so this figure is SMP hours.
     */
    public static int level(long playtimeSeconds) {
        if (playtimeSeconds <= 0) return 1;
        double hours = playtimeSeconds / 3600.0;
        return 1 + (int) Math.floor(2 * Math.sqrt(hours));
    }

    /** Thousands separators, always ',' regardless of the host locale. */
    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
