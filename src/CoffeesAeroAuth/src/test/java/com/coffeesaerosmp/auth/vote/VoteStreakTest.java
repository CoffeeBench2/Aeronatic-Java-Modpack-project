package com.coffeesaerosmp.auth.vote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The admins' report (2026-10-04): a lapsed voter kept the top tier; a loyal one was capped to nothing. */
class VoteStreakTest {

    private static final long H = 3_600_000L;
    private static final long T0 = 1_000_000_000_000L;

    @Test
    @DisplayName("first ever vote starts at day 1")
    void first() {
        assertEquals(1, VoteStreak.next(0, 0, T0, 24));
    }

    @Test
    @DisplayName("voting on several sites the same day does not inflate the streak")
    void sameDay() {
        assertEquals(3, VoteStreak.next(3, T0, T0 + 2 * H, 24));
        assertEquals(3, VoteStreak.next(3, T0, T0 + 19 * H, 24));
    }

    @Test
    @DisplayName("next day (20h-48h later) moves it up")
    void nextDay() {
        assertEquals(4, VoteStreak.next(3, T0, T0 + 20 * H, 24));
        assertEquals(4, VoteStreak.next(3, T0, T0 + 24 * H, 24));
        assertEquals(4, VoteStreak.next(3, T0, T0 + 48 * H, 24));
    }

    @Test
    @DisplayName("a lapsed voter starts again — no keeping the top tier after a break")
    void lapseResets() {
        assertEquals(1, VoteStreak.next(30, T0, T0 + 49 * H, 24));
        assertEquals(1, VoteStreak.next(30, T0, T0 + 30 * 24 * H, 24));
    }

    @Test
    @DisplayName("no lifetime cap: a loyal voter keeps climbing (the reward itself is clamped elsewhere)")
    void noCap() {
        assertEquals(31, VoteStreak.next(30, T0, T0 + 24 * H, 24));
        assertEquals(366, VoteStreak.next(365, T0, T0 + 24 * H, 24));
    }
}
