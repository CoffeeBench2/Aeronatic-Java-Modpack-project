package com.coffeesaerosmp.auth.vote;

/**
 * The vote-streak rule, pure (no game classes) so it is unit-tested. See {@link VoteRewards}.
 *
 * <ul>
 *   <li>Sooner than {@link #NEXT_DAY_HOURS} after the vote that last moved the streak = same day (another
 *       site): unchanged.</li>
 *   <li>Within {@code 2 × cooldownHours} = the next day: +1.</li>
 *   <li>Later = the streak broke: back to 1.</li>
 * </ul>
 */
public final class VoteStreak {

    /** A vote this long after the last streak-moving vote is "the next day". */
    public static final int NEXT_DAY_HOURS = 20;

    private VoteStreak() {}

    public static int next(int streak, long lastStreakMs, long now, int cooldownHours) {
        if (streak <= 0 || lastStreakMs <= 0) return 1;
        long since = now - lastStreakMs;
        if (since < NEXT_DAY_HOURS * 3_600_000L) return streak;
        if (since <= 2L * Math.max(1, cooldownHours) * 3_600_000L) return streak + 1;
        return 1;
    }
}
