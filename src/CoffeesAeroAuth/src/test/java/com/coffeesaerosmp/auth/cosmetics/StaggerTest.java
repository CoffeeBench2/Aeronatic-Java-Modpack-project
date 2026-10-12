package com.coffeesaerosmp.auth.cosmetics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StaggerTest {

    /** Count how often an emitter fires over 400 even ticks (the engine never runs on odd ones). */
    private static int fires(int key, int every) {
        int n = 0;
        for (long t = 0; t < 400; t += 2) if (Stagger.due(t, key, every)) n++;
        return n;
    }

    @Test
    void everyKeyFiresAtTheRightRate() {
        // The 1.13.30 bug: odd keys never fired when everyTicks was even. Check every key parity and interval.
        for (int every : new int[]{2, 4, 6, 8}) {
            for (int key = -50; key <= 50; key++) {
                // 200 emit ticks / (every/2): exact when it divides, otherwise floor or ceil depending on phase.
                double exact = 200.0 / (every / 2);
                int got = fires(key, every);
                assertTrue(got == (int) Math.floor(exact) || got == (int) Math.ceil(exact),
                    "key " + key + " every " + every + " fired " + got + ", expected ~" + exact);
            }
        }
    }

    @Test
    void emittersAreSpreadAcrossTicks() {
        int[] perTick = new int[2];
        for (int key = 0; key < 100; key++) {
            for (long t = 0; t < 4; t += 2) if (Stagger.due(t, key, 4)) perTick[(int) (t / 2)]++;
        }
        assertEquals(50, perTick[0]);
        assertEquals(50, perTick[1], "half the emitters on each emit tick, not all on one");
    }

    @Test
    void negativeHashesWork() {
        assertTrue(fires(Integer.MIN_VALUE, 4) > 0);
        assertTrue(fires(-1, 4) > 0);
    }
}
