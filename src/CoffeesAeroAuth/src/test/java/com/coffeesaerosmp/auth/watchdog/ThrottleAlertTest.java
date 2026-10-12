package com.coffeesaerosmp.auth.watchdog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThrottleAlertTest {

    private static final long MIN = 60_000L;

    @Test
    void firesAtTheThreshold() {
        assertTrue(RuntimePressure.shouldAlert(3000, 3000, 10 * MIN, Long.MAX_VALUE, 15 * MIN));
        assertFalse(RuntimePressure.shouldAlert(2999, 3000, 10 * MIN, Long.MAX_VALUE, 15 * MIN));
    }

    @Test
    void quietDuringBoot() {
        assertFalse(RuntimePressure.shouldAlert(20_000, 3000, RuntimePressure.ALERT_GRACE_MS - 1, Long.MAX_VALUE, 15 * MIN));
    }

    @Test
    void respectsCooldownAndDisable() {
        assertFalse(RuntimePressure.shouldAlert(20_000, 3000, 60 * MIN, 14 * MIN, 15 * MIN));
        assertTrue(RuntimePressure.shouldAlert(20_000, 3000, 60 * MIN, 15 * MIN, 15 * MIN));
        assertFalse(RuntimePressure.shouldAlert(60_000, 0, 60 * MIN, Long.MAX_VALUE, 15 * MIN), "0 disables");
    }
}
