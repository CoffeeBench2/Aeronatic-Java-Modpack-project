package com.coffeesaerosmp.auth.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * When to warn before a ground-item clear, and what to say. Pure — no {@code net.minecraft}
 * imports — so the countdown logic is unit-testable without a server.
 *
 * <p>The schedule is expressed as "seconds before the clear". Crossing a threshold fires once and
 * only once: the driver passes the previous and current remaining-seconds, and a warning is due
 * when a threshold lies in {@code (current, previous]}. Testing the boundary that way means a tick
 * that skips a second — which happens constantly on a lagging server, and this server lags — still
 * fires the warning instead of silently missing it.</p>
 */
public final class ClearSchedule {

    private ClearSchedule() {}

    /** Parses "300,120,60,30" into a descending, de-duplicated list. Bad entries are dropped. */
    public static List<Integer> parse(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            try {
                int v = Integer.parseInt(part.trim());
                if (v > 0 && !out.contains(v)) out.add(v);
            } catch (NumberFormatException ignored) {
                // a typo in config must not stop the clear from running
            }
        }
        out.sort((a, b) -> b - a);
        return out;
    }

    /**
     * The threshold crossed between {@code previousRemaining} and {@code currentRemaining}, or
     * {@code -1} if none. Half-open on the low side so each threshold fires exactly once even when
     * the server skips seconds under load.
     */
    public static int crossed(List<Integer> thresholds, int previousRemaining, int currentRemaining) {
        if (thresholds == null || previousRemaining <= currentRemaining) return -1;
        // "Was strictly above the threshold, is now at or below it." Firing at t <= previous and
        // t > current instead would never fire at all on an exact landing (301 -> 300), and firing
        // on t >= current would fire again on the next second.
        for (int t : thresholds) {
            if (previousRemaining > t && currentRemaining <= t) return t;
        }
        return -1;
    }

    /** "5 minutes", "2 minutes", "1 minute", "30 seconds" — never "0.5 minutes". */
    public static String humanTime(int seconds) {
        if (seconds >= 60 && seconds % 60 == 0) {
            int m = seconds / 60;
            return m + (m == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    /** The warning line players see. */
    public static String warning(int secondsRemaining) {
        String colour = secondsRemaining <= 30 ? "§c" : secondsRemaining <= 60 ? "§e" : "§7";
        return colour + "⚠ Dropped items on the ground will be cleared in "
             + colour + "§l" + humanTime(secondsRemaining) + colour
             + ". §7Pick up anything you want to keep.";
    }

    /** The line after a clear. Singular/plural matters when it says 1. */
    public static String cleared(int count) {
        if (count <= 0) return "§7No dropped items needed clearing.";
        return String.format(Locale.ROOT,
            "§7Cleared §f%d§7 dropped item%s from the ground.", count, count == 1 ? "" : "s");
    }
}
