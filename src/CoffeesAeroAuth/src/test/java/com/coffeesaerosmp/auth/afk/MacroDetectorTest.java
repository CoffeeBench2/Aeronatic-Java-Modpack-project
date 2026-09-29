package com.coffeesaerosmp.auth.afk;

import com.coffeesaerosmp.auth.afk.MacroDetector.Kind;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What counts as a human at the keyboard. The detector must refuse clockwork input (auto-clickers,
 * a weight on the mouse, a jiggler) and must NOT refuse the irregular input of a real player.
 */
class MacroDetectorTest {

    /** Feeds n events at a fixed period plus server-tick quantisation jitter of ±1 tick. */
    private static int humanCount(MacroDetector d, Kind k, long period, int n, long jitter, long seed) {
        Random r = new Random(seed);
        long t = 1_000_000;
        int counted = 0;
        for (int i = 0; i < n; i++) {
            if (d.event(k, t)) counted++;
            t += period + (jitter == 0 ? 0 : (r.nextInt(3) - 1) * jitter);
        }
        return counted;
    }

    // ── timing: clockwork is refused ────────────────────────────────────────

    @Test
    void anAutoClickerStopsCountingOnceItsRhythmIsEstablished() {
        MacroDetector d = new MacroDetector();
        int counted = humanCount(d, Kind.ATTACK, 1000, 60, 50, 1);   // 1 s ± one tick, like a sword cooldown clicker
        assertTrue(counted <= MacroDetector.MIN_INTERVALS + 1, "counted " + counted);
        assertTrue(d.isClockwork(Kind.ATTACK));
    }

    @Test
    void aHeldRightClickRepeatIsClockwork() {
        MacroDetector d = new MacroDetector();
        humanCount(d, Kind.USE, 200, 40, 0, 2);                        // vanilla hold-use repeat, every 4 ticks
        assertTrue(d.isClockwork(Kind.USE));
    }

    @Test
    void aSlowAntiAfkMacroIsCaughtToo() {
        MacroDetector d = new MacroDetector();
        humanCount(d, Kind.HOTBAR, 30_000, 30, 50, 3);                 // swap slot every 30 s
        assertTrue(d.isClockwork(Kind.HOTBAR));
    }

    // ── timing: humans are not ───────────────────────────────────────────────

    @Test
    void irregularHumanClickingAlwaysCounts() {
        MacroDetector d = new MacroDetector();
        Random r = new Random(4);
        long t = 0;
        for (int i = 0; i < 200; i++) {
            assertTrue(d.event(Kind.ATTACK, t), "human click " + i + " refused");
            t += 150 + r.nextInt(450);                                  // 150-600 ms, a real player
        }
        assertFalse(d.isClockwork(Kind.ATTACK));
    }

    @Test
    void theFirstEventsAlwaysCountBecauseThereIsNoRhythmYet() {
        MacroDetector d = new MacroDetector();
        for (int i = 0; i < MacroDetector.MIN_INTERVALS; i++) assertTrue(d.event(Kind.SWING, i * 1000L));
    }

    @Test
    void breakingTheRhythmCountsAgainImmediately() {
        MacroDetector d = new MacroDetector();
        long t = humanCountEnd(d, Kind.USE, 200, 40);
        assertTrue(d.isClockwork(Kind.USE));
        assertTrue(d.event(Kind.USE, t + 2_700), "an off-beat press is a person");
    }

    private static long humanCountEnd(MacroDetector d, Kind k, long period, int n) {
        long t = 0;
        for (int i = 0; i < n; i++) { d.event(k, t); t += period; }
        return t - period;
    }

    @Test
    void kindsAreIndependent() {
        MacroDetector d = new MacroDetector();
        humanCount(d, Kind.ATTACK, 1000, 60, 0, 5);
        assertTrue(d.isClockwork(Kind.ATTACK));
        assertTrue(d.event(Kind.CHAT, 9_999_999), "a clicker must not mute the player's chat");
    }

    /** Both hands fire RightClickItem in the same tick; that is one press, not an interval of zero. */
    @Test
    void sameTickDuplicatesAreOnePress() {
        MacroDetector d = new MacroDetector();
        Random r = new Random(6);
        long t = 0;
        for (int i = 0; i < 100; i++) {
            d.event(Kind.USE, t);
            d.event(Kind.USE, t + 1);
            t += 300 + r.nextInt(900);
        }
        assertFalse(d.isClockwork(Kind.USE));
    }

    // ── value patterns: jigglers ─────────────────────────────────────────────

    @Test
    void aMouseJigglerFlickingBetweenTwoAnglesIsRefused() {
        MacroDetector d = new MacroDetector();
        int counted = 0;
        for (int i = 0; i < 60; i++) {
            if (d.look(i % 2 == 0 ? 10f : 12f, 5f)) counted++;
        }
        assertTrue(counted <= MacroDetector.VALUE_WINDOW, "counted " + counted);
        assertTrue(d.isJiggling());
    }

    @Test
    void lookingAroundCounts() {
        MacroDetector d = new MacroDetector();
        Random r = new Random(7);
        for (int i = 0; i < 200; i++) {
            assertTrue(d.look(r.nextFloat() * 360f - 180f, r.nextFloat() * 90f - 45f), "look " + i);
        }
    }

    @Test
    void bouncingBetweenTwoBlocksIsRefused() {
        MacroDetector d = new MacroDetector();
        int counted = 0;
        for (int i = 0; i < 60; i++) {
            if (d.move(i % 2 == 0 ? 100.3 : 101.2, 64, 50.5)) counted++;
        }
        assertTrue(counted <= MacroDetector.VALUE_WINDOW, "counted " + counted);
    }

    @Test
    void walkingAroundCounts() {
        MacroDetector d = new MacroDetector();
        double x = 0;
        for (int i = 0; i < 200; i++) {
            x += 3.1;
            assertTrue(d.move(x, 64, (i % 7) * 2.0), "step " + i);
        }
    }

    // ── what staff are told ─────────────────────────────────────────────────

    @Test
    void refusedInputIsTalliedForTheAlertAndClearedByRealActivity() {
        MacroDetector d = new MacroDetector();
        humanCount(d, Kind.ATTACK, 1000, 60, 0, 8);
        assertTrue(d.refusedSinceHuman() >= 60 - MacroDetector.MIN_INTERVALS - 1);
        assertTrue(d.summary().contains("ATTACK"));
        d.event(Kind.CHAT, 50_000_000);
        assertEquals(0, d.refusedSinceHuman());
    }
}
