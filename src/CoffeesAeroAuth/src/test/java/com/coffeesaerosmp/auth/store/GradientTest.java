package com.coffeesaerosmp.auth.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GradientTest {

    @Test
    void endpointsLandExactlyOnTheDeclaredStops() {
        int[] out = Gradient.forLength("sunrise", 10);
        assertNotNull(out);
        assertEquals(Palette.rgb("gold"),   out[0],  "first char must be the first stop");
        assertEquals(Palette.rgb("yellow"), out[9],  "last char must be the last stop");
    }

    @Test
    void threeStopGradientHitsTheMiddleStopInTheMiddle() {
        // horizon = gold -> yellow -> white, 9 chars so index 4 is the exact midpoint
        int[] out = Gradient.forLength("horizon", 9);
        assertNotNull(out);
        assertEquals(Palette.rgb("gold"),   out[0]);
        assertEquals(Palette.rgb("yellow"), out[4], "middle stop must land on the middle character");
        assertEquals(Palette.rgb("white"),  out[8]);
    }

    /** A one-character name must not divide by zero. */
    @Test
    void singleCharacterNameTakesTheFirstStop() {
        int[] out = Gradient.forLength("sunrise", 1);
        assertNotNull(out);
        assertEquals(1, out.length);
        assertEquals(Palette.rgb("gold"), out[0]);
    }

    @Test
    void zeroLengthIsEmptyNotNull() {
        int[] out = Gradient.forLength("sunrise", 0);
        assertNotNull(out);
        assertEquals(0, out.length);
    }

    @Test
    void unknownGradientIsRefused() {
        assertNull(Gradient.forLength("nope", 8));
        assertNull(Gradient.forLength(null, 8));
    }

    @Test
    void lengthAlwaysMatchesTheRequest() {
        for (String g : Palette.allGradients()) {
            for (int len : new int[]{1, 2, 3, 5, 16, 20}) {
                int[] out = Gradient.forLength(g, len);
                assertNotNull(out, g);
                assertEquals(len, out.length, g + " at length " + len);
            }
        }
    }

    /** Every produced colour must be a legal 24-bit value — a wrapped channel renders as noise. */
    @Test
    void everyColourStaysInsideTwentyFourBits() {
        for (String g : Palette.allGradients()) {
            for (int c : Gradient.forLength(g, 16)) {
                assertTrue(c >= 0 && c <= 0xFFFFFF, g + " produced out-of-range " + Integer.toHexString(c));
            }
        }
    }

    @Test
    void lerpClampsAndHitsBothEnds() {
        assertEquals(0x000000, Gradient.lerp(0x000000, 0xFFFFFF, 0.0));
        assertEquals(0xFFFFFF, Gradient.lerp(0x000000, 0xFFFFFF, 1.0));
        assertEquals(0x808080, Gradient.lerp(0x000000, 0xFFFFFF, 0.5));
        // out-of-range t must clamp, not wrap
        assertEquals(0x000000, Gradient.lerp(0x000000, 0xFFFFFF, -5.0));
        assertEquals(0xFFFFFF, Gradient.lerp(0x000000, 0xFFFFFF, 5.0));
    }

    @Test
    void lerpBlendsChannelsIndependently() {
        // red -> blue at the midpoint is dark magenta, never green
        int mid = Gradient.lerp(0xFF0000, 0x0000FF, 0.5);
        assertEquals(0x80, (mid >> 16) & 0xFF);
        assertEquals(0x00, (mid >> 8) & 0xFF);
        assertEquals(0x80, mid & 0xFF);
    }

    /**
     * altitude is aqua (0x55FFFF) → blue (0x5555FF). Only the GREEN channel moves — red is 0x55 and
     * blue is 0xFF at both ends. Asserting on red would pass trivially and prove nothing, so this
     * pins the channel that actually carries the gradient.
     */
    @Test
    void aTwoStopGradientIsMonotonicInTheChannelThatMoves() {
        int[] out = Gradient.forLength("altitude", 12);
        assertEquals(0xFF, (out[0] >> 8) & 0xFF, "starts on aqua's green");
        assertEquals(0x55, (out[11] >> 8) & 0xFF, "ends on blue's green");
        for (int i = 1; i < out.length; i++) {
            int prev = (out[i - 1] >> 8) & 0xFF;
            int cur  = (out[i] >> 8) & 0xFF;
            assertTrue(cur <= prev, "green channel jumped upward at " + i);
        }
        // The two static channels must stay put rather than drifting through rounding.
        for (int c : out) {
            assertEquals(0x55, (c >> 16) & 0xFF, "red drifted");
            assertEquals(0xFF, c & 0xFF,         "blue drifted");
        }
    }

    @Test
    void interpolateWithASingleStopIsSolid() {
        int[] out = Gradient.interpolate(new int[]{0x123456}, 5);
        for (int c : out) assertEquals(0x123456, c);
    }
}
