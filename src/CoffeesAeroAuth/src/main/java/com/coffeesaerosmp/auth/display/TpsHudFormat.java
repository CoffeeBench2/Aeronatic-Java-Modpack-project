package com.coffeesaerosmp.auth.display;

import java.util.Locale;

/**
 * Formatting and severity for the ops TPS/MSPT bar. Deliberately pure — no {@code net.minecraft}
 * imports — so the thresholds and wording are unit-testable without a running server, the same
 * split {@link PlayerDisplay} uses.
 */
public final class TpsHudFormat {

    private TpsHudFormat() {}

    /** How the bar reads at a glance. Mapped to a boss-bar colour by the caller. */
    public enum Severity { GOOD, WARN, BAD }

    /** Vanilla's per-tick budget. Above this the server cannot hold 20 TPS. */
    public static final double BUDGET_MS = 50.0;

    /**
     * Severity is decided by MSPT, not TPS.
     *
     * <p>TPS saturates: it reads 20.0 whether ticks cost 5 ms or 49 ms, because a server that
     * finishes early sleeps. MSPT keeps moving across that whole range, so it warns while there is
     * still headroom left to lose. A bar that only turned yellow once TPS dropped would tell you
     * after it already mattered.</p>
     */
    public static Severity severity(double msptMean) {
        if (msptMean >= BUDGET_MS) return Severity.BAD;      // cannot sustain 20 TPS
        if (msptMean >= BUDGET_MS * 0.6) return Severity.WARN; // 30ms+: under half the headroom left
        return Severity.GOOD;
    }

    /** Bar fill: how much of the 50 ms budget is spent, so a fuller bar means a worse tick. */
    public static float progress(double msptMean) {
        if (msptMean <= 0) return 0f;
        double frac = msptMean / BUDGET_MS;
        return (float) Math.max(0.0, Math.min(1.0, frac));
    }

    /**
     * The bar title. Shows TPS, mean MSPT and worst MSPT, because the mean alone hides the stalls
     * and the worst alone hides sustained overload — the two failure shapes look nothing alike.
     */
    public static String title(double msptMean, double msptWorst, double tps, int seconds) {
        if (seconds <= 0) return "§7⚡ measuring…";
        String colour = switch (severity(msptMean)) {
            case GOOD -> "§a";
            case WARN -> "§e";
            case BAD  -> "§c";
        };
        return String.format(Locale.ROOT,
            "%s⚡ %.1f TPS §8• %s%.0f ms §7avg §8• §f%.0f ms §7peak §8• §7%ds",
            colour, tps, colour, msptMean, msptWorst, seconds);
    }
}
