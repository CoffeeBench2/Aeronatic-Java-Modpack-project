package com.coffeesaerosmp.auth.display;

import org.junit.jupiter.api.Test;

import static com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDisplayTest {

    private static PlayerDisplay.Parts parts() {
        return new PlayerDisplay.Parts("§6✈ ", "§c[ADMIN] ", "§7[§9AERO§7] ", "Coffee", "MrCoffeeBench");
    }

    /** THE REGRESSION THIS WHOLE PROJECT EXISTS FOR: the clan tag must survive into TAB. */
    @Test
    void tabIncludesClanTag() {
        String out = PlayerDisplay.compose(parts(), TAB, false);
        assertTrue(out.contains("[§9AERO§7]"), "clan tag missing from TAB: " + out);
    }

    @Test
    void nameplateIncludesClanTag() {
        String out = PlayerDisplay.compose(parts(), NAMEPLATE, false);
        assertTrue(out.contains("[§9AERO§7]"), "clan tag missing from nameplate: " + out);
    }
}
