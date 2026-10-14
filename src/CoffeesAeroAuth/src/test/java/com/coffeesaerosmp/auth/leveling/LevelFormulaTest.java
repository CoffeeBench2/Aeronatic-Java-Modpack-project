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
    @DisplayName("claims: +2 a level, capped at 150 (reached at Lv 74), never below 5")
    void claimsCap() {
        assertEquals(7, LevelFormula.claims(2, P));
        assertEquals(23, LevelFormula.claims(10, P));
        assertEquals(149, LevelFormula.claims(73, P));
        assertEquals(150, LevelFormula.claims(74, P));
        assertEquals(150, LevelFormula.claims(500, P));
        assertEquals(145, LevelFormula.extraClaims(500, P));
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
        assertEquals(LevelFormula.claims(lv, P), Math.min(150, 5 + 2 * (lv - 1)));
    }

    @Test
    void shipClaimsBasePlusOneEveryFiveLevels() {
        assertEquals(2, LevelFormula.shipClaims(1, 2, 5));
        assertEquals(2, LevelFormula.shipClaims(5, 2, 5));
        assertEquals(3, LevelFormula.shipClaims(6, 2, 5));     // +1 at Lv 6
        assertEquals(3, LevelFormula.shipClaims(10, 2, 5));
        assertEquals(4, LevelFormula.shipClaims(11, 2, 5));
        assertEquals(2, LevelFormula.shipClaims(50, 2, 0));    // no growth
        assertEquals(0, LevelFormula.shipClaims(50, 0, 0));    // off
        assertEquals(1, LevelFormula.shipClaims(6, 0, 5));     // growth only
    }

    @Test
    void adminAddedClaimsSurviveTheLevelSystem() {
        // The owner's real file: extra 556, Lv 29 formula 56 (+2 ship = 58) → 498 hand-added... first sight.
        assertEquals(500, LevelFormula.reconcileBonus(null, 556, 56, 0));
        assertEquals(0, LevelFormula.reconcileBonus(null, 56, 56, 0), "normal player: no bonus");
        assertEquals(0, LevelFormula.reconcileBonus(null, 40, 56, 0), "below formula is not a negative bonus");
        // We applied 58; nobody touched it → bonus unchanged.
        assertEquals(500, LevelFormula.reconcileBonus(558, 558, 58, 500));
        // Admin ran `extra_claim_chunks add 100` → kept.
        assertEquals(600, LevelFormula.reconcileBonus(558, 658, 58, 500));
        // Admin removed 50 → the bonus shrinks, never below 0.
        assertEquals(450, LevelFormula.reconcileBonus(558, 508, 58, 500));
        assertEquals(0, LevelFormula.reconcileBonus(58, 0, 58, 10));
        // Level-up: formula grows from 58 to 60 but FTB still shows our last 558 → bonus untouched.
        assertEquals(500, LevelFormula.reconcileBonus(558, 558, 60, 500));
    }

    @Test
    void shipClaimsNeverChangeLand() {
        // The FTB extra carries ship claims ON TOP of the land extra; AeroClaims then subtracts the
        // migrated ship slots, so the land a player can use is exactly the level formula's.
        for (int lv : new int[]{1, 6, 30, 73}) {
            int ship = LevelFormula.shipClaims(lv, 2, 5);
            int ftbMax = P.claimsBase() + LevelFormula.extraClaims(lv, P) + ship;
            int landUsable = ftbMax - ship;
            assertEquals(LevelFormula.claims(lv, P), landUsable, "Lv " + lv);
        }
    }
}
