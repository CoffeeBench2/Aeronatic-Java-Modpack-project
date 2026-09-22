package com.coffeesaerosmp.auth.tracking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatSheetTest {

    // ── duration ────────────────────────────────────────────────────────────

    @Test
    void durationRendersAsHoursAndMinutes() {
        assertEquals("0m",      StatSheet.duration(0));
        assertEquals("59m",     StatSheet.duration(59 * 60));
        assertEquals("1h 0m",   StatSheet.duration(3600));
        assertEquals("2h 30m",  StatSheet.duration(2 * 3600 + 30 * 60));
        assertEquals("100h 1m", StatSheet.duration(100 * 3600 + 60));
    }

    @Test
    void durationClampsNegativesRatherThanRenderingNonsense() {
        assertEquals("0m", StatSheet.duration(-1));
        assertEquals("0m", StatSheet.duration(Long.MIN_VALUE));
    }

    @Test
    void durationDropsSecondsRatherThanRounding() {
        assertEquals("0m", StatSheet.duration(59), "59s is not a minute yet");
        assertEquals("1m", StatSheet.duration(119), "119s is one minute, not two");
    }

    // ── distance ────────────────────────────────────────────────────────────

    @Test
    void distanceRendersCentimetresAsKilometres() {
        assertEquals("0.00 km",  StatSheet.distance(0));
        assertEquals("0.01 km",  StatSheet.distance(1_000));
        assertEquals("1.00 km",  StatSheet.distance(100_000));
        assertEquals("12.34 km", StatSheet.distance(1_234_000));
    }

    @Test
    void distanceClampsNegatives() {
        assertEquals("0.00 km", StatSheet.distance(-500));
    }

    // ── level ───────────────────────────────────────────────────────────────

    /** Lv = 1 + floor(2 * sqrt(hours)). Owner decision 2026-08-19; playtime is the ONLY input. */
    @Test
    void levelIsPlaytimeOnly() {
        assertEquals(1, StatSheet.level(0));
        assertEquals(3, StatSheet.level(3600));            // 1h  -> 1 + 2*1
        assertEquals(5, StatSheet.level(4 * 3600));        // 4h  -> 1 + 2*2
        assertEquals(7, StatSheet.level(9 * 3600));        // 9h  -> 1 + 2*3
        assertEquals(9, StatSheet.level(16 * 3600));       // 16h -> 1 + 2*4
    }

    @Test
    void levelNeverGoesBelowOne() {
        assertEquals(1, StatSheet.level(-5));
        assertEquals(1, StatSheet.level(1));               // a single second is still Lv 1
    }

    /**
     * The curve is steep early — Lv 2 arrives at fifteen minutes, not an hour
     * ({@code 1 + floor(2*sqrt(0.25)) == 2}). What matters is that the boundary is exact and does
     * not round up: a player one second short of an hour is still Lv 2.
     */
    @Test
    void levelBoundariesAreExactAndDoNotRoundUp() {
        assertEquals(1, StatSheet.level(899),  "just under 15m");
        assertEquals(2, StatSheet.level(900),  "15m is Lv 2");
        assertEquals(2, StatSheet.level(3599), "one second short of an hour is still Lv 2");
        assertEquals(3, StatSheet.level(3600), "a full hour is Lv 3");
    }

    // ── count ───────────────────────────────────────────────────────────────

    @Test
    void countsGetThousandsSeparators() {
        assertEquals("0",         StatSheet.count(0));
        assertEquals("999",       StatSheet.count(999));
        assertEquals("1,000",     StatSheet.count(1000));
        assertEquals("1,234,567", StatSheet.count(1234567));
    }

    /**
     * Grouping must not follow the host locale. A server running under a European locale would
     * otherwise render 1.234.567, which reads as a decimal to everyone else.
     */
    @Test
    void countGroupingIsLocaleIndependent() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            assertEquals("1,234,567", StatSheet.count(1234567));
            assertEquals("12.34 km",  StatSheet.distance(1_234_000));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
