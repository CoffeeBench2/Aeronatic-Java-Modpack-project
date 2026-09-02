package com.coffeesaerosmp.auth.world;

import com.coffeesaerosmp.auth.world.ShipCensus.LevelCensus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShipCensusTest {

    private static ShipCensus of(int claimed, LevelCensus... levels) {
        return new ShipCensus(List.of(levels), claimed);
    }

    @Test
    void sumsAcrossDimensions() {
        ShipCensus c = of(10,
            new LevelCensus("minecraft:overworld", 40, 5, 30, 6),
            new LevelCensus("minecraft:the_nether", 2, 0, 2, 1));
        assertEquals(42, c.totalLive());
        assertEquals(5,  c.totalForceLoaded());
        assertEquals(32, c.totalUntracked());
        assertEquals(7,  c.totalNamed());
    }

    @Test
    void unclaimedIsLiveMinusClaimed() {
        assertEquals(35, of(5, new LevelCensus("d", 40, 0, 0, 0)).unclaimed());
    }

    @Test
    void moreClaimsThanLiveIsNotNegative() {
        // Legitimate: a player can hold a claim on a ship that is currently unloaded.
        assertEquals(0, of(50, new LevelCensus("d", 40, 0, 0, 0)).unclaimed());
    }

    @Test
    void unknownClaimCountPropagates() {
        ShipCensus c = of(-1, new LevelCensus("d", 40, 0, 0, 0));
        assertEquals(-1, c.unclaimed());
        assertTrue(c.verdict().contains("Claim data unavailable"));
    }

    @Test
    void majorityUnclaimedIsFlaggedRed() {
        // The case actually suspected on Lagless: a handful of claims, hundreds of live hulls.
        String v = of(5, new LevelCensus("minecraft:overworld", 500, 0, 0, 0)).verdict();
        assertTrue(v.startsWith("§c"), v);
        assertTrue(v.contains("495 of 500"), v);
        assertTrue(v.contains("99%"), v);
    }

    @Test
    void minorityUnclaimedIsAWarningNotAnAlarm() {
        String v = of(90, new LevelCensus("d", 100, 0, 0, 0)).verdict();
        assertTrue(v.startsWith("§e"), v);
        assertTrue(v.contains("10%"), v);
    }

    @Test
    void fullyClaimedIsGreen() {
        assertTrue(of(40, new LevelCensus("d", 40, 0, 0, 0)).verdict().startsWith("§a"));
    }

    @Test
    void noLiveSubLevelsShortCircuitsBeforeClaimComparison() {
        // live == 0 must win over claimed == -1, otherwise an empty server reports "unavailable"
        // and looks like a broken command rather than a clean result.
        assertTrue(of(-1).verdict().contains("not your cost"));
    }

    @Test
    void reportSkipsEmptyDimensions() {
        List<String> lines = of(1,
            new LevelCensus("minecraft:overworld", 3, 0, 0, 0),
            new LevelCensus("minecraft:the_end", 0, 0, 0, 0)).report();
        assertTrue(lines.stream().anyMatch(l -> l.contains("overworld")));
        assertFalse(lines.stream().anyMatch(l -> l.contains("the_end")));
    }

    @Test
    void reportWithNoContainersExplainsItself() {
        List<String> lines = of(-1).report();
        // Must not silently print a bare "Total live: 0" — that reads as a healthy server when it
        // may actually mean the Sable API moved under us after an upgrade.
        assertTrue(lines.stream().anyMatch(l -> l.contains("Sable is absent")), lines.toString());
    }

    @Test
    void defaultNameCoversBlankNullAndSablesPlaceholder() {
        assertTrue(ShipCensus.isDefaultName(null));
        assertTrue(ShipCensus.isDefaultName(""));
        assertTrue(ShipCensus.isDefaultName("   "));
        assertTrue(ShipCensus.isDefaultName("ship"));
        assertTrue(ShipCensus.isDefaultName(" Ship "));
        assertFalse(ShipCensus.isDefaultName("Airship One"));
        assertFalse(ShipCensus.isDefaultName("ships"));
    }
}
