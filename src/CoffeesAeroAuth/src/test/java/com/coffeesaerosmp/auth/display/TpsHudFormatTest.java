package com.coffeesaerosmp.auth.display;

import org.junit.jupiter.api.Test;

import static com.coffeesaerosmp.auth.display.TpsHudFormat.Severity.*;
import static org.junit.jupiter.api.Assertions.*;

class TpsHudFormatTest {

    @Test
    void severityIsDrivenByMsptNotTps() {
        assertEquals(GOOD, TpsHudFormat.severity(16.0));   // spark's healthy baseline
        assertEquals(WARN, TpsHudFormat.severity(30.0));   // 60% of budget
        assertEquals(WARN, TpsHudFormat.severity(49.9));   // still under budget
        assertEquals(BAD,  TpsHudFormat.severity(50.0));   // at budget: cannot hold 20 TPS
        assertEquals(BAD,  TpsHudFormat.severity(68.4));   // the 09-02 measured median
    }

    /** The whole point of keying off MSPT: TPS reads 20.0 across this entire range. */
    @Test
    void warnsWhileTpsStillReadsTwenty() {
        assertEquals(GOOD, TpsHudFormat.severity(10.0));
        assertEquals(WARN, TpsHudFormat.severity(45.0));
        // Both of the above are 20.0 TPS. A TPS-driven bar would show green for both.
    }

    @Test
    void progressIsFractionOfBudgetAndClamped() {
        assertEquals(0.0f, TpsHudFormat.progress(0), 0.001f);
        assertEquals(0.5f, TpsHudFormat.progress(25.0), 0.001f);
        assertEquals(1.0f, TpsHudFormat.progress(50.0), 0.001f);
        assertEquals(1.0f, TpsHudFormat.progress(500.0), 0.001f, "must clamp, not overflow the bar");
        assertEquals(0.0f, TpsHudFormat.progress(-5.0), 0.001f);
    }

    @Test
    void titleShowsMeasuringUntilThereIsHistory() {
        assertEquals("§7⚡ measuring…", TpsHudFormat.title(0, 0, 20.0, 0));
    }

    @Test
    void titleCarriesTpsMeanPeakAndWindow() {
        String t = TpsHudFormat.title(16.0, 59.0, 20.0, 60);
        assertTrue(t.contains("20.0 TPS"), t);
        assertTrue(t.contains("16 ms"), t);
        assertTrue(t.contains("59 ms"), t);
        assertTrue(t.contains("60s"), t);
    }

    @Test
    void titleColourTracksSeverity() {
        assertTrue(TpsHudFormat.title(16.0, 20.0, 20.0, 60).startsWith("§a"));
        assertTrue(TpsHudFormat.title(35.0, 60.0, 20.0, 60).startsWith("§e"));
        assertTrue(TpsHudFormat.title(68.0, 595.0, 13.5, 60).startsWith("§c"));
    }
}
