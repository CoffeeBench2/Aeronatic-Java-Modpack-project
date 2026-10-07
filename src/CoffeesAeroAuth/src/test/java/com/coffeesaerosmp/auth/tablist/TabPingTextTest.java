package com.coffeesaerosmp.auth.tablist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TabPingTextTest {

    @Test
    void showsNothingBeforeTheFirstMeasurement() {
        assertEquals("", PingText.of(0));
        assertEquals("", PingText.of(-1));
    }

    @Test
    void coloursByQuality() {
        assertEquals(" §a42ms", PingText.of(42));
        assertEquals(" §e80ms", PingText.of(80));
        assertEquals(" §6150ms", PingText.of(150));
        assertEquals(" §c300ms", PingText.of(300));
        assertEquals(" §c1200ms", PingText.of(1200));
    }
}
