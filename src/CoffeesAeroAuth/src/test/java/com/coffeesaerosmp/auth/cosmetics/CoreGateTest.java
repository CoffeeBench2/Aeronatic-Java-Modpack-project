package com.coffeesaerosmp.auth.cosmetics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreGateTest {
    // Exercises CoreVersions (pure) through the same name the gate uses.
    private static final class CoreGate { static boolean atLeast(String a, String b) { return CoreVersions.atLeast(a, b); } }


    @Test
    void versionsCompareAsNumbersNotText() {
        assertTrue(CoreGate.atLeast("1.4.0", "1.4.0"));
        assertTrue(CoreGate.atLeast("1.10.0", "1.4.0"), "10 > 4, not '1' < '4'");
        assertTrue(CoreGate.atLeast("1.4", "1.4.0"), "missing parts are 0");
        assertTrue(CoreGate.atLeast("2.0.0", "1.99.99"));
        assertFalse(CoreGate.atLeast("1.3.57", "1.4.0"));
        assertFalse(CoreGate.atLeast("1.4.0", "1.4.1"));
    }

    @Test
    void suffixesAreIgnoredAndJunkIsNeverEnough() {
        assertTrue(CoreGate.atLeast("1.4.0-cf", "1.4.0"));
        assertTrue(CoreGate.atLeast("1.4.2+potato", "1.4.0"));
        assertFalse(CoreGate.atLeast("", "1.4.0"));
        assertFalse(CoreGate.atLeast(null, "1.4.0"));
        assertFalse(CoreGate.atLeast("${version}", "1.4.0"), "an unfilled dev placeholder is not a version");
        assertTrue(CoreGate.atLeast("1.0.0", ""), "no minimum = anything goes");
    }
}
