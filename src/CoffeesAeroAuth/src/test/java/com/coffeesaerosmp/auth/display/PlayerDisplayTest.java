package com.coffeesaerosmp.auth.display;

import org.junit.jupiter.api.Test;

import static com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDisplayTest {

    private static PlayerDisplay.Parts full() {
        return new PlayerDisplay.Parts("§6✈ ", "§c[ADMIN] ", "§7[§9AERO§7] ", "Coffee", "MrCoffeeBench");
    }

    private static PlayerDisplay.Parts plain() {
        return new PlayerDisplay.Parts("§8◈ ", "", "", "Steve", null);
    }

    @Test
    void tabIncludesClanTag() {
        assertTrue(PlayerDisplay.compose(full(), TAB, false).contains("[§9AERO§7]"));
    }

    @Test
    void nameplateIncludesClanTag() {
        assertTrue(PlayerDisplay.compose(full(), NAMEPLATE, false).contains("[§9AERO§7]"));
    }

    @Test
    void tabShowsRealNameToOpViewerOnly() {
        assertTrue(PlayerDisplay.compose(full(), TAB, true).contains("MrCoffeeBench"));
        assertFalse(PlayerDisplay.compose(full(), TAB, false).contains("MrCoffeeBench"));
    }

    /** A scoreboard team prefix is GLOBAL — it cannot vary per viewer, so it must never leak
     *  the real name even when the viewer is an op. */
    @Test
    void nameplateNeverShowsRealNameEvenForOps() {
        assertFalse(PlayerDisplay.compose(full(), NAMEPLATE, true).contains("MrCoffeeBench"));
    }

    @Test
    void discordStripsFormattingCodes() {
        String out = PlayerDisplay.compose(full(), DISCORD, false);
        assertFalse(out.contains("§"), "Discord output still has § codes: " + out);
        assertTrue(out.contains("AERO"));
        assertTrue(out.contains("Coffee"));
    }

    @Test
    void emptyPartsDoNotProduceStrayWhitespace() {
        String out = PlayerDisplay.compose(plain(), TAB, false);
        assertEquals("§8◈ Steve", out);
    }

    @Test
    void unmaskedPlayerGetsNoRealNameSuffixEvenForOps() {
        PlayerDisplay.Parts p = new PlayerDisplay.Parts("§6✈ ", "", "", "Coffee", null);
        assertEquals("§6✈ Coffee", PlayerDisplay.compose(p, TAB, true));
    }

    @Test
    void staffBadgeSitsBeforeClanTag() {
        String out = PlayerDisplay.compose(full(), TAB, false);
        assertTrue(out.indexOf("[ADMIN]") < out.indexOf("AERO"), "wrong order: " + out);
    }

    /** A scoreboard team PREFIX must not contain the name — the client appends the scoreboard
     *  name itself, so including it would render the name twice. */
    @Test
    void prefixExcludesTheName() {
        String out = PlayerDisplay.composePrefix(full());
        assertFalse(out.contains("Coffee"), "prefix must not contain the name: " + out);
        assertTrue(out.contains("[§9AERO§7]"));
        assertTrue(out.contains("[ADMIN]"));
        assertTrue(out.endsWith(" "), "prefix must end with a separator space: '" + out + "'");
    }

    @Test
    void prefixIsEmptyWhenNothingDecoratesTheName() {
        PlayerDisplay.Parts bare = new PlayerDisplay.Parts("", "", "", "Steve", null);
        assertEquals("", PlayerDisplay.composePrefix(bare));
    }
}
