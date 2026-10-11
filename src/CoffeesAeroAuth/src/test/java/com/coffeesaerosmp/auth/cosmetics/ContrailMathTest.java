package com.coffeesaerosmp.auth.cosmetics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContrailMathTest {

    private static final double[] C = {100, 80, 200};
    private static final double[] MIN = {90, 75, 196};   // 20 x 10 x 8 ship centred on C
    private static final double[] MAX = {110, 85, 204};

    @Test
    void flyingEastTheTailIsWestOfTheHull() {
        double[] p = ContrailMath.tail(C, new double[]{0.5, 0, 0}, MIN, MAX);
        assertArrayEquals(new double[]{100 - 10 - 1.5, 80, 200}, p, 1e-9);
    }

    @Test
    void flyingNorthUsesTheShortSide() {
        double[] p = ContrailMath.tail(C, new double[]{0, 0, -0.5}, MIN, MAX);
        assertArrayEquals(new double[]{100, 80, 200 + 4 + 1.5}, p, 1e-9);
    }

    @Test
    void diagonalUsesTheSupportFunction() {
        double s = Math.sqrt(0.5);
        double[] p = ContrailMath.tail(C, new double[]{0.4, 0, 0.4}, MIN, MAX);
        double h = s * 10 + s * 4;                        // Σ |d_i| * halfSize_i
        assertEquals(100 - s * (h + 1.5), p[0], 1e-9);
        assertEquals(200 - s * (h + 1.5), p[2], 1e-9);
    }

    @Test
    void aHoveringShipHasNoContrail() {
        assertNull(ContrailMath.tail(C, new double[]{0.1, 0.05, 0}, MIN, MAX));
        assertNull(ContrailMath.tail(C, new double[]{0, 0, 0}, MIN, MAX));
    }

    @Test
    void boundsThatDoNotContainTheShipFallBackToSixBlocks() {
        double[] plotMin = {5000, 0, 5000}, plotMax = {5020, 10, 5008};
        double[] p = ContrailMath.tail(C, new double[]{1, 0, 0}, plotMin, plotMax);
        assertArrayEquals(new double[]{100 - 6 - 1.5, 80, 200}, p, 1e-9);
        assertArrayEquals(p, ContrailMath.tail(C, new double[]{1, 0, 0}, null, null), 1e-9);
    }
}
