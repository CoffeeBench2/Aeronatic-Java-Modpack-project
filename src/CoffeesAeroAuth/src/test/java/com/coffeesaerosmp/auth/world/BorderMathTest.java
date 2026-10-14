package com.coffeesaerosmp.auth.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BorderMathTest {

    // Border [-100, 100], a ship 10 wide (half 5): the centre may range over [-95, 95].

    @Test
    void clearOfTheWallIsUntouched() {
        assertEquals(8.0, BorderMath.axis(0, 5, 8, -100, 100, 0.3));
        assertEquals(-8.0, BorderMath.axis(94, 5, -8, -100, 100, 0.3));
        assertEquals(8.0, BorderMath.axis(94.9, 5, 8, -100, 100, 0.3), "hull not touching yet");
    }

    @Test
    void outwardMotionAtTheWallIsReflected() {
        double v = BorderMath.axis(95.5, 5, 10, -100, 100, 0.3);
        assertTrue(v < 0, "must point back in, was " + v);
        assertEquals(-3.0, v, 1e-9);                       // 10 * 0.3 bounce beats the 1 b/s push
        double w = BorderMath.axis(-95.5, 5, -10, -100, 100, 0.3);
        assertEquals(3.0, w, 1e-9);                        // the low side mirrors it
    }

    @Test
    void deepPenetrationPushesHarderThanTheBounce() {
        // 10 blocks through with only 1 b/s outward: the push (20 b/s) wins over the bounce (0.3).
        assertEquals(-20.0, BorderMath.axis(105, 5, 1, -100, 100, 0.3), 1e-9);
    }

    @Test
    void inwardMotionFromOutsideIsAlwaysAllowed() {
        // An old ship 500 blocks outside, flying home: never blocked.
        assertEquals(-12.0, BorderMath.axis(600, 5, -12, -100, 100, 0.3));
        assertEquals(0.0, BorderMath.axis(600, 5, 0, -100, 100, 0.3), "parked outside: left alone");
    }

    @Test
    void zeroBounceIsADeadStop() {
        assertEquals(-1.0, BorderMath.axis(95.5, 5, 10, -100, 100, 0.0), 1e-9);  // only the gentle push remains
    }

    @Test
    void shipWiderThanTheBorderIsPinnedToTheCentre() {
        assertTrue(BorderMath.axis(10, 150, 5, -100, 100, 0.3) < 0);
        assertTrue(BorderMath.axis(-10, 150, -5, -100, 100, 0.3) > 0);
    }
}
