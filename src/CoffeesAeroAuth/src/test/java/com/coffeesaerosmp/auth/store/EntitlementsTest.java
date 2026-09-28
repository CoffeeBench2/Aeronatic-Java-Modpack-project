package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.store.Entitlements.Capability;
import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EntitlementsTest {

    private static final Set<String> NOTHING = Set.of();

    // ── tiers are cumulative ──────────────────────────────────────────────────

    @Test
    void eachTierIncludesEverythingBelowIt() {
        assertTrue(Rank.ADMIRAL.includes(Rank.DECKHAND));
        assertTrue(Rank.CAPTAIN.includes(Rank.NAVIGATOR));
        assertTrue(Rank.DECKHAND.includes(Rank.DECKHAND), "includes must be reflexive");
        assertFalse(Rank.DECKHAND.includes(Rank.CAPTAIN));
        assertFalse(Rank.NONE.includes(Rank.DECKHAND));
    }

    @Test
    void capabilitiesMatchTheSpecTable() {
        assertEquals(Set.of(), Entitlements.of(Rank.NONE));
        assertEquals(Set.of(Capability.SOLID_BASIC), Entitlements.of(Rank.DECKHAND));
        assertEquals(Set.of(Capability.SOLID_BASIC, Capability.SOLID_FULL, Capability.BOLD),
                     Entitlements.of(Rank.NAVIGATOR));
        assertTrue(Entitlements.of(Rank.CAPTAIN).containsAll(
                   Set.of(Capability.GRADIENT_2, Capability.JOIN_MESSAGE, Capability.BOLD)));
        assertFalse(Entitlements.of(Rank.CAPTAIN).contains(Capability.GRADIENT_3));
        assertTrue(Entitlements.of(Rank.ADMIRAL).containsAll(
                   Set.of(Capability.GRADIENT_3, Capability.CHAT_TITLE, Capability.GRADIENT_2)));
    }

    @Test
    void deckhandGetsOnlyTheEightBasicColours() {
        assertTrue(Entitlements.mayUse("colour:gold", Rank.DECKHAND, NOTHING));
        // dark_purple is in the full palette but not the basic eight
        assertFalse(Entitlements.mayUse("colour:dark_purple", Rank.DECKHAND, NOTHING));
        assertTrue(Entitlements.mayUse("colour:dark_purple", Rank.NAVIGATOR, NOTHING));
    }

    @Test
    void gradientStopCountGatesTheTier() {
        assertEquals(2, Palette.gradientStops("sunrise"));
        assertEquals(3, Palette.gradientStops("horizon"));
        assertTrue(Entitlements.mayUse("gradient:sunrise", Rank.CAPTAIN, NOTHING));
        assertFalse(Entitlements.mayUse("gradient:horizon", Rank.CAPTAIN, NOTHING),
                    "a 3-stop gradient must need Admiral");
        assertTrue(Entitlements.mayUse("gradient:horizon", Rank.ADMIRAL, NOTHING));
    }

    // ── the anti-spoof rule ───────────────────────────────────────────────────

    /** 🔴 Staff colours must be unwearable at every rank. */
    @Test
    void staffColoursAreNeverSelectableAtAnyRank() {
        for (Rank r : Rank.values()) {
            assertFalse(Entitlements.mayUse("colour:red", r, NOTHING), "red leaked at " + r);
            assertFalse(Entitlements.mayUse("colour:dark_red", r, NOTHING), "dark_red leaked at " + r);
        }
    }

    /**
     * 🔴 And owning one must not buy past the rule. A bad Tebex package, a hand-run grant or a
     * restored backup could all put such a row in {@code cosmetics_owned}; ownership must still lose.
     */
    @Test
    void owningAStaffColourStillDoesNotAllowIt() {
        Set<String> owned = Set.of("colour:red", "colour:dark_red");
        assertFalse(Entitlements.mayUse("colour:red", Rank.ADMIRAL, owned));
        assertFalse(Entitlements.mayUse("colour:dark_red", Rank.NONE, owned));
    }

    @Test
    void staffColoursAreAbsentFromEverySelectableSet() {
        for (String reserved : Palette.STAFF_RESERVED) {
            assertFalse(Palette.allColours().contains(reserved), reserved + " is in the full palette");
            assertFalse(Palette.basicColours().contains(reserved), reserved + " is in the basic eight");
        }
    }

    @Test
    void noCuratedGradientContainsAStaffColour() {
        for (String g : Palette.allGradients()) {
            for (String c : Palette.gradient(g)) {
                assertFalse(Palette.isStaffReserved(c), "gradient " + g + " uses staff colour " + c);
            }
        }
    }

    // ── Beans are permanent, rank is rented ───────────────────────────────────

    @Test
    void beanOwnedCosmeticsWorkWithNoRankAtAll() {
        Set<String> owned = Set.of("colour:dark_purple", "bold", "gradient:sunrise", "join_message");
        assertTrue(Entitlements.mayUse("colour:dark_purple", Rank.NONE, owned));
        assertTrue(Entitlements.mayUse("bold", Rank.NONE, owned));
        assertTrue(Entitlements.mayUse("gradient:sunrise", Rank.NONE, owned));
        assertTrue(Entitlements.mayUse("join_message", Rank.NONE, owned));
    }

    @Test
    void unknownIdsAreRefused() {
        assertFalse(Entitlements.mayUse("colour:neon", Rank.ADMIRAL, NOTHING));
        assertFalse(Entitlements.mayUse("gradient:nope", Rank.ADMIRAL, NOTHING));
        assertFalse(Entitlements.mayUse("fly", Rank.ADMIRAL, NOTHING));
        assertFalse(Entitlements.mayUse("", Rank.ADMIRAL, NOTHING));
        assertFalse(Entitlements.mayUse(null, Rank.ADMIRAL, NOTHING));
    }

    // ── lapse behaviour: the part that touches real money ─────────────────────

    /** The spec's promise: a lapse takes the rank cosmetics and leaves the Bean-bought ones. */
    @Test
    void lapsedAdmiralKeepsTheBeanBoughtColourAndLosesTheGradient() {
        Set<String> owned = Set.of("colour:blue");
        Selection had = new Selection("gradient:horizon", true, "Skyfarer", "took the long way round");

        var out = Entitlements.sanitise(had, Rank.NONE, owned);

        assertTrue(out.changed());
        assertEquals("colour:blue", out.selection().nameStyle(), "must fall back to the owned colour");
        assertFalse(out.selection().bold(),        "bold was rank-granted, not owned");
        assertNull(out.selection().chatTitle(),    "chat title is Admiral-only");
        assertNull(out.selection().joinMessage(),  "join message was rank-granted");
        assertTrue(out.removed().contains("gradient:horizon"));
    }

    @Test
    void lapsedPlayerWithNothingOwnedFallsBackToTheDefault() {
        var out = Entitlements.sanitise(new Selection("colour:gold", false, null, null), Rank.NONE, NOTHING);
        assertTrue(out.changed());
        assertNull(out.selection().nameStyle());
    }

    @Test
    void aStillValidSelectionIsLeftCompletelyAlone() {
        Selection had = new Selection("gradient:sunrise", true, null, "wheels up");
        var out = Entitlements.sanitise(had, Rank.CAPTAIN, NOTHING);
        assertFalse(out.changed(), "nothing should have been removed: " + out.removed());
        assertEquals(had, out.selection());
    }

    @Test
    void beanOwnedBoldSurvivesALapseButRankBoldDoesNot() {
        var owned = Entitlements.sanitise(new Selection(null, true, null, null), Rank.NONE, Set.of("bold"));
        assertTrue(owned.selection().bold());
        assertFalse(owned.changed());

        var rented = Entitlements.sanitise(new Selection(null, true, null, null), Rank.NONE, NOTHING);
        assertFalse(rented.selection().bold());
        assertTrue(rented.changed());
    }

    /** A downgrade is a lapse of the difference, not of everything. */
    @Test
    void admiralDowngradingToCaptainLosesOnlyTheThreeStopGradient() {
        var out = Entitlements.sanitise(
            new Selection("gradient:horizon", true, "Skyfarer", "wheels up"), Rank.CAPTAIN, NOTHING);
        assertTrue(out.changed());
        assertNull(out.selection().nameStyle());
        assertTrue(out.selection().bold(),               "Captain still grants bold");
        assertEquals("wheels up", out.selection().joinMessage(), "Captain still grants join messages");
        assertNull(out.selection().chatTitle(),          "chat title is Admiral-only");
    }

    /** Deterministic fallback: same inputs, same colour, every restart. */
    @Test
    void fallbackIsDeterministicAndFollowsPaletteOrder() {
        Set<String> owned = Set.of("colour:blue", "colour:gold", "colour:aqua");
        String first = null;
        for (int i = 0; i < 20; i++) {
            var out = Entitlements.sanitise(new Selection("gradient:horizon", false, null, null),
                                            Rank.NONE, owned);
            if (first == null) first = out.selection().nameStyle();
            assertEquals(first, out.selection().nameStyle(), "fallback changed between runs");
        }
        // gold precedes aqua and blue in the palette's declared order
        assertEquals("colour:gold", first);
    }

    @Test
    void sanitiseHandlesNullsWithoutThrowing() {
        var out = Entitlements.sanitise(null, null, null);
        assertFalse(out.changed());
        assertEquals(Selection.DEFAULT, out.selection());
    }

    // ── tab list sort order ───────────────────────────────────────────────────

    /** Vanilla sorts by team name ascending, so the top tier needs the LOWEST key. */
    @Test
    void sortKeyPutsAdmiralFirstAndUnrankedLast() {
        assertTrue(Rank.ADMIRAL.sortKey().compareTo(Rank.CAPTAIN.sortKey()) < 0);
        assertTrue(Rank.CAPTAIN.sortKey().compareTo(Rank.NAVIGATOR.sortKey()) < 0);
        assertTrue(Rank.NAVIGATOR.sortKey().compareTo(Rank.DECKHAND.sortKey()) < 0);
        assertTrue(Rank.DECKHAND.sortKey().compareTo(Rank.NONE.sortKey()) < 0);
    }

    @Test
    void sortKeysAreAllSingleCharacterSoTeamNamesStayShort() {
        for (Rank r : Rank.values()) assertEquals(1, r.sortKey().length(), r.name());
    }

    // ── badges ────────────────────────────────────────────────────────────────

    @Test
    void unrankedBadgeIsEmptySoNoGapIsRendered() {
        assertEquals("", Rank.NONE.badge());
    }

    @Test
    void everyRankBadgeCarriesItsTrailingSpace() {
        for (Rank r : Rank.values()) {
            if (r == Rank.NONE) continue;
            assertTrue(r.badge().endsWith(" "), r + " badge lacks the trailing space: '" + r.badge() + "'");
            assertTrue(r.badge().startsWith("§"), r + " badge is not coloured");
        }
    }

    @Test
    void rankBadgesAreAllDistinct() {
        long distinct = java.util.Arrays.stream(Rank.values())
            .filter(r -> r != Rank.NONE).map(Rank::symbol).distinct().count();
        assertEquals(Rank.values().length - 1, distinct);
    }

    @Test
    void parseIsCaseInsensitiveAndFailsSafeToNone() {
        assertEquals(Rank.ADMIRAL, Rank.parse("admiral"));
        assertEquals(Rank.CAPTAIN, Rank.parse("  CAPTAIN "));
        assertEquals(Rank.NONE, Rank.parse("emperor"));
        assertEquals(Rank.NONE, Rank.parse(null));
    }
}
