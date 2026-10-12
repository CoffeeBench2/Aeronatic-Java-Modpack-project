package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The host-throttle alert reaches the watchdog path on a real server without throwing. */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class ThrottleAlertGameTests {

    @GameTest(template = "empty", batch = "throttle_alert", timeoutTicks = 20)
    public static void aThrottleAlertIsRaisedThroughTheWatchdog(GameTestHelper h) {
        if (CoffeesAeroAuth.WATCHDOG == null) h.fail("watchdog not running, the alert would only reach the log");
        int before = RuntimePressure.alertsRaised;
        RuntimePressure.raiseAlert(4200, "GameTest — safe to ignore");
        if (RuntimePressure.alertsRaised != before + 1) h.fail("alert not raised");
        h.succeed();
    }
}
