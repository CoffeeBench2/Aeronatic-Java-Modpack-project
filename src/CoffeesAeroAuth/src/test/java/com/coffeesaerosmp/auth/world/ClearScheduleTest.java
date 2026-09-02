package com.coffeesaerosmp.auth.world;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClearScheduleTest {

    private static final List<Integer> S = ClearSchedule.parse("300,120,60,30");

    @Test
    void parseSortsDescendingAndDeduplicates() {
        assertEquals(List.of(300, 120, 60, 30), S);
        assertEquals(List.of(300, 60), ClearSchedule.parse("60,300,60"));
    }

    @Test
    void parseSurvivesGarbageWithoutLosingGoodEntries() {
        assertEquals(List.of(120, 30), ClearSchedule.parse("120, ,abc,30,-5,0"));
        assertEquals(List.of(), ClearSchedule.parse(""));
        assertEquals(List.of(), ClearSchedule.parse(null));
    }

    @Test
    void firesExactlyOnceWhenATresholdIsPassed() {
        assertEquals(300, ClearSchedule.crossed(S, 301, 300));
        assertEquals(-1,  ClearSchedule.crossed(S, 300, 299), "must not fire twice for one threshold");
        assertEquals(30,  ClearSchedule.crossed(S, 31, 30));
    }

    /**
     * The case that matters on THIS server: a lagging tick loop skips seconds. If the check were
     * equality-based, a jump from 65 to 55 would silently swallow the 60-second warning.
     */
    @Test
    void stillFiresWhenTheServerSkipsSeconds() {
        assertEquals(60, ClearSchedule.crossed(S, 65, 55));
        assertEquals(300, ClearSchedule.crossed(S, 400, 250), "biggest crossed threshold wins");
    }

    @Test
    void noWarningWhenNothingIsCrossed() {
        assertEquals(-1, ClearSchedule.crossed(S, 200, 190));
        assertEquals(-1, ClearSchedule.crossed(S, 100, 100), "no time passed");
        assertEquals(-1, ClearSchedule.crossed(S, 50, 90), "clock went backwards");
    }

    @Test
    void humanTimeReadsNaturally() {
        assertEquals("5 minutes", ClearSchedule.humanTime(300));
        assertEquals("2 minutes", ClearSchedule.humanTime(120));
        assertEquals("1 minute",  ClearSchedule.humanTime(60));
        assertEquals("30 seconds", ClearSchedule.humanTime(30));
        assertEquals("1 second",  ClearSchedule.humanTime(1));
        assertEquals("90 seconds", ClearSchedule.humanTime(90), "not '1.5 minutes'");
    }

    @Test
    void clearedLineHandlesZeroOneAndMany() {
        assertEquals("§7No dropped items needed clearing.", ClearSchedule.cleared(0));
        assertTrue(ClearSchedule.cleared(1).contains("1§7 dropped item "), ClearSchedule.cleared(1));
        assertFalse(ClearSchedule.cleared(1).contains("items"), "must not pluralise a single item");
        assertTrue(ClearSchedule.cleared(42).contains("42§7 dropped items "), ClearSchedule.cleared(42));
    }

    @Test
    void warningGetsMoreUrgentAsTimeRunsOut() {
        assertTrue(ClearSchedule.warning(300).startsWith("§7"));
        assertTrue(ClearSchedule.warning(60).startsWith("§e"));
        assertTrue(ClearSchedule.warning(30).startsWith("§c"));
        assertTrue(ClearSchedule.warning(300).contains("5 minutes"));
    }
}
