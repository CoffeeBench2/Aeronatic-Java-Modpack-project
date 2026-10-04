package com.coffeesaerosmp.auth.leveling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LevelFormulaTest {

    private static final LevelFormula.Params P = LevelFormula.Params.DEFAULT;

    @Test
    @DisplayName("a brand-new player is Lv 1 with 5 claims")
    void newPlayer() {
        assertEquals(1, LevelFormula.level(LevelFormula.xp(0, 0, P), P));
        assertEquals(5, LevelFormula.claims(1, P));
        assertEquals(0, LevelFormula.extraClaims(1, P));
    }

    @Test
    @DisplayName("the S2-measured veteran pace splits about 80/20 advancements/playtime")
    void eightyTwenty() {
        long adv = 250L * P.xpPerAdvancement();
        long play = 100L * P.xpPerHour();
        double share = (double) adv / (adv + play);
        assertTrue(share > 0.78 && share < 0.82, "advancement share was " + share);
    }

    @Test
    @DisplayName("idling earns slowly; achieving earns fast")
    void doingBeatsStaying() {
        int idle100h = LevelFormula.level(LevelFormula.xp(10, 100 * 3600L, P), P);
        int active10h = LevelFormula.level(LevelFormula.xp(150, 10 * 3600L, P), P);
        assertTrue(active10h > idle100h, "10h with 150 advancements must outrank 100h with 10");
    }

    @Test
    @DisplayName("level and xpForLevel are exact inverses at every boundary")
    void inverse() {
        for (int lv = 1; lv <= 80; lv++) {
            long at = LevelFormula.xpForLevel(lv, P);
            assertEquals(lv, LevelFormula.level(at, P), "at boundary of Lv " + lv);
            if (at > 0) assertEquals(lv - 1, LevelFormula.level(at - 1, P), "just below Lv " + lv);
        }
    }

    @Test
    @DisplayName("claims: +2 a level, capped at 50 (reached at Lv 24), never below 5")
    void claimsCap() {
        assertEquals(7, LevelFormula.claims(2, P));
        assertEquals(23, LevelFormula.claims(10, P));
        assertEquals(49, LevelFormula.claims(23, P));
        assertEquals(50, LevelFormula.claims(24, P));
        assertEquals(50, LevelFormula.claims(500, P));
        assertEquals(45, LevelFormula.extraClaims(500, P));
        assertEquals(5, LevelFormula.claims(-3, P));
    }

    @Test
    @DisplayName("progress stays in [0,1] and resets at each level")
    void progress() {
        long base = LevelFormula.xpForLevel(10, P);
        assertEquals(0.0, LevelFormula.progress(base, P), 1e-9);
        assertTrue(LevelFormula.progress(base + 1, P) > 0.0);
        assertTrue(LevelFormula.progress(LevelFormula.xpForLevel(11, P) - 1, P) < 1.0);
    }

    @Test
    @DisplayName("an op with every real advancement (~1,275 in the pack) is a high level, not overflowed")
    void opsWithEverything() {
        int lv = LevelFormula.level(LevelFormula.xp(1275, 0, P), P);
        assertTrue(lv >= 45 && lv <= 60, "was Lv " + lv);
        assertEquals(50, LevelFormula.claims(lv, P));
    }
}
