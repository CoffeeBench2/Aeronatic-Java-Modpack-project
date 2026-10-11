package com.coffeesaerosmp.auth.watchdog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StallMathTest {

    @Test
    void aWellSampledStallIsWork() {
        // 3 s stall, sampling every 50 ms after 150 ms: ~57 expected, 55 taken.
        assertTrue(StallMath.cause(StallMath.Kind.IN_TICK, 3000, 55, 50, 150).startsWith("busy inside"));
        assertTrue(StallMath.cause(StallMath.Kind.BETWEEN_TICKS, 3000, 55, 50, 150).startsWith("busy between"));
    }

    @Test
    void aStallTheSamplerSleptThroughIsAWholeServerPause() {
        assertTrue(StallMath.cause(StallMath.Kind.BETWEEN_TICKS, 10_000, 3, 50, 150).startsWith("WHOLE-SERVER PAUSE"));
        assertTrue(StallMath.cause(StallMath.Kind.IN_TICK, 10_000, 0, 50, 150).startsWith("WHOLE-SERVER PAUSE"));
    }

    @Test
    void tooShortToJudgeIsNeverCalledAPause() {
        assertFalse(StallMath.cause(StallMath.Kind.IN_TICK, 300, 0, 50, 150).startsWith("WHOLE"));
    }

    @Test
    void healthyServerIs20Tps() {
        double[] sums = new double[60];
        int[] counts = new int[60];
        for (int i = 0; i < 10; i++) { sums[i] = 1000; counts[i] = 20; }
        assertEquals(20.0, StallMath.tpsOverWindow(sums, counts, 10, 9, 10_000), 0.01);
    }

    @Test
    void aFreezeCountsAtItsTrueWeight() {
        // 4 healthy seconds then a 6 s single-tick freeze in the newest bucket.
        double[] sums = new double[60];
        int[] counts = new int[60];
        double[] maxes = new double[60];
        for (int i = 0; i < 4; i++) { sums[i] = 1000; counts[i] = 20; maxes[i] = 50; }
        sums[4] = 6000; counts[4] = 1; maxes[4] = 6000;
        double tps = StallMath.tpsOverWindow(sums, counts, 5, 4, 10_000);
        assertEquals(81 / 10.0, tps, 0.01);                       // 81 ticks in 10 s of wall time
        assertEquals(6000, StallMath.worstOverWindow(sums, maxes, 5, 4, 10_000), 0.01);
        // The OLD rule — mean of deltas = 10000/81 = 123 ms, under its 250 ms bar — would have stayed silent.
        assertTrue(10_000.0 / 81 < 250);
    }

    @Test
    void windowWrapsAroundTheRing() {
        double[] sums = new double[5];
        int[] counts = new int[5];
        for (int i = 0; i < 5; i++) { sums[i] = 1000; counts[i] = 10; }
        assertEquals(10.0, StallMath.tpsOverWindow(sums, counts, 5, 1, 3_000), 0.01);
    }

    @Test
    void noDataIsHealthy() {
        assertEquals(20.0, StallMath.tpsOverWindow(new double[60], new int[60], 0, 0, 10_000), 0.01);
    }
}
