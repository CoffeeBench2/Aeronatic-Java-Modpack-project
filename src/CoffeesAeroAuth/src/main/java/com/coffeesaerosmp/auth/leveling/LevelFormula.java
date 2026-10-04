package com.coffeesaerosmp.auth.leveling;

/**
 * Season 3 level maths (owner decision 2026-10-04): mostly ACHIEVEMENT-driven, playtime a nudge.
 *
 * <h2>The shape</h2>
 * <pre>
 *   xp    = realAdvancements × xpPerAdvancement  +  seasonHours × xpPerHour
 *   level = 1 + floor( sqrt( xp / curve ) )
 * </pre>
 * Defaults 10 / 6 / 5. At the pace measured on S2 (a 100-hour veteran holds roughly 250 real
 * advancements) that is 2,500 xp from advancements and 600 from playtime: <b>80/20</b>, the split the
 * owner picked. AFK seconds never reach {@code seasonHours} (AfkTracker rolls the session clock), so
 * idling earns nothing; a player who does things levels several times faster than one who only stays.
 *
 * <p>"Real" advancements only — see {@code SidebarManager.isRealAdvancement}. 86% of the pack's
 * advancements are recipe unlocks that complete when an ingredient is picked up; counting them is what
 * made the S2 formula meaningless, and is why S2 went playtime-only on 08-19.
 *
 * <h2>Claims</h2>
 * {@code claims = min(max, base + perLevel × (level − 1))}: 5 at Lv 1, +2 a level, capped at 150
 * (reached at Lv 74; owner set the cap 2026-10-04, first 50 then 150). Teams SUM their members. FTB Chunks' own {@code max_claimed_chunks} must equal {@code base}; we set the
 * per-player EXTRA, so the two add up to this number.
 *
 * <p>Pure: no game state, so it is unit-tested and the sidebar, claims and level-up mail can never
 * disagree about what level someone is.
 */
public final class LevelFormula {

    public record Params(int xpPerAdvancement, int xpPerHour, int curve,
                         int claimsBase, int claimsPerLevel, int claimsMax) {
        public static final Params DEFAULT = new Params(10, 6, 5, 5, 2, 150);
    }

    private LevelFormula() {}

    public static long xp(int realAdvancements, long seasonSeconds, Params p) {
        long adv = Math.max(0, realAdvancements) * (long) p.xpPerAdvancement();
        long hours = Math.max(0L, seasonSeconds) / 3600L;
        return adv + hours * p.xpPerHour();
    }

    public static int level(long xp, Params p) {
        if (xp <= 0) return 1;
        return 1 + (int) Math.floor(Math.sqrt((double) xp / Math.max(1, p.curve())));
    }

    /** Least xp at which {@code level} is reached. Inverse of {@link #level}. */
    public static long xpForLevel(int level, Params p) {
        if (level <= 1) return 0;
        long k = level - 1L;
        return k * k * Math.max(1, p.curve());
    }

    /** 0..1 progress from this level to the next, for the sidebar bar. */
    public static double progress(long xp, Params p) {
        int lv = level(xp, p);
        long base = xpForLevel(lv, p), next = xpForLevel(lv + 1, p);
        return next <= base ? 1.0 : Math.max(0.0, Math.min(1.0, (double) (xp - base) / (next - base)));
    }

    /** Total chunks a player may claim at {@code level}. */
    public static int claims(int level, Params p) {
        long c = p.claimsBase() + (long) p.claimsPerLevel() * Math.max(0, level - 1);
        return (int) Math.max(p.claimsBase(), Math.min(p.claimsMax(), c));
    }

    /** What to pass to FTB Chunks' setExtraClaimChunks: everything above its own base. */
    public static int extraClaims(int level, Params p) {
        return claims(level, p) - p.claimsBase();
    }
}
