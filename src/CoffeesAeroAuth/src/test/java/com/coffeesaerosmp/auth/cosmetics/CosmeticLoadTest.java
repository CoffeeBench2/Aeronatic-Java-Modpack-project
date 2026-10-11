package com.coffeesaerosmp.auth.cosmetics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticLoadTest {

    private static boolean feed(CosmeticLoad l, double tps, long t) {
        return l.update(tps, 50, t, 15, 17, 10_000);
    }

    @Test
    void healthyServerRunsCosmetics() {
        CosmeticLoad l = new CosmeticLoad();
        assertFalse(feed(l, 20, 0));
        assertFalse(feed(l, 15.5, 1000), "15.5 is above the pause line");
    }

    @Test
    void pausesBelowFifteenAndWaitsTenGoodSecondsAtSeventeen() {
        CosmeticLoad l = new CosmeticLoad();
        assertTrue(feed(l, 14, 0), "14 TPS pauses");
        assertTrue(feed(l, 16, 1000), "16 is not enough to resume");
        assertTrue(feed(l, 17, 2000), "17 starts the clock");
        assertTrue(feed(l, 18, 11_000), "9 s at >=17 is not yet 10");
        assertFalse(feed(l, 18, 12_000), "10 s at >=17 resumes");
    }

    @Test
    void aDipDuringRecoveryRestartsTheClock() {
        CosmeticLoad l = new CosmeticLoad();
        feed(l, 10, 0);
        feed(l, 18, 1000);
        feed(l, 16, 6000);                       // dips below resume line
        assertTrue(feed(l, 18, 12_000), "clock restarted at the dip");
        assertFalse(feed(l, 18, 22_000));
    }

    @Test
    void aFreezePausesEvenWhenTheAverageLooksFine() {
        CosmeticLoad l = new CosmeticLoad();
        assertTrue(l.update(19, 2500, 0, 15, 17, 10_000));
    }

    @Test
    void forcedPauseOverridesEverything() {
        CosmeticLoad l = new CosmeticLoad();
        l.force(true);
        assertTrue(feed(l, 20, 0));
        l.force(false);
        assertFalse(feed(l, 20, 1000));
    }
}
