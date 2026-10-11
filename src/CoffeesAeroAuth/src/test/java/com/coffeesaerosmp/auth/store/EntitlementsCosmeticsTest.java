package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The 1.13.30 cosmetics on the rank ladder, Beans ownership, and the ops preview rule. */
class EntitlementsCosmeticsTest {

    private static final Set<String> NOTHING = Set.of();

    @Test
    void eachRankAddsOneVisibleThing() {
        assertFalse(Entitlements.mayUse("trail:cloudwalk", Rank.NONE, NOTHING));
        assertTrue(Entitlements.mayUse("trail:cloudwalk", Rank.DECKHAND, NOTHING));
        assertFalse(Entitlements.mayUse("trail:brass", Rank.DECKHAND, NOTHING));
        assertTrue(Entitlements.mayUse("trail:brass", Rank.NAVIGATOR, NOTHING));
        assertTrue(Entitlements.mayUse("trail:cloudwalk", Rank.NAVIGATOR, NOTHING), "tiers are cumulative");
        assertFalse(Entitlements.mayUse("contrail:vapour", Rank.NAVIGATOR, NOTHING));
        assertTrue(Entitlements.mayUse("contrail:vapour", Rank.CAPTAIN, NOTHING));
        assertFalse(Entitlements.mayUse("contrail:aurora", Rank.CAPTAIN, NOTHING));
        assertTrue(Entitlements.mayUse("contrail:aurora", Rank.ADMIRAL, NOTHING));
    }

    @Test
    void beansAndEventItemsAreInNoRank() {
        for (Rank r : Rank.values()) assertFalse(Entitlements.mayUse("trail:petal", r, NOTHING), r.name());
        assertTrue(Entitlements.mayUse("trail:petal", Rank.NONE, Set.of("trail:petal")), "owned outright");
        assertFalse(Entitlements.mayUse("contrail:espresso", Rank.ADMIRAL, NOTHING), "Beans-only even for Admiral");
    }

    @Test
    void previewUnlocksEverythingKnownButNothingElse() {
        assertTrue(Entitlements.mayUse("contrail:aurora", Rank.NONE, NOTHING, true));
        assertTrue(Entitlements.mayUse("trail:petal", Rank.NONE, NOTHING, true));
        assertTrue(Entitlements.mayUse("leave_message", Rank.NONE, NOTHING, true));
        assertFalse(Entitlements.mayUse("trail:does_not_exist", Rank.NONE, NOTHING, true), "allowlist still applies");
        assertFalse(Entitlements.mayUse("contrail:nope", Rank.ADMIRAL, NOTHING, true));
        assertFalse(Entitlements.mayUse("totally:made_up", Rank.NONE, NOTHING, true));
        for (String c : Palette.allColours()) {
            if (Palette.isStaffReserved(c)) {
                assertFalse(Entitlements.mayUse("colour:" + c, Rank.NONE, NOTHING, true), "staff colour " + c + " via preview");
            }
        }
    }

    @Test
    void leaveMessageFollowsTheJoinMessageRule() {
        assertFalse(Entitlements.mayUse("leave_message", Rank.NAVIGATOR, NOTHING));
        assertTrue(Entitlements.mayUse("leave_message", Rank.CAPTAIN, NOTHING));
        assertTrue(Entitlements.mayUse("leave_message", Rank.NONE, Set.of("join_message")));
    }

    @Test
    void aLapseRemovesRentedCosmeticsButKeepsOwnedOnes() {
        Selection had = new Selection(null, false, null, null, "trail:brass", "contrail:vapour", "gone ashore");
        var out = Entitlements.sanitise(had, Rank.NONE, Set.of("trail:brass"));
        assertEquals("trail:brass", out.selection().trail(), "bought outright, kept");
        assertNull(out.selection().contrail(), "rank-rented, removed");
        assertNull(out.selection().leaveMessage());
        assertTrue(out.changed());
        assertTrue(out.removed().contains("contrail:vapour"));
    }

    @Test
    void previewKeepsAnOpsSelectionAndDeoppingStripsIt() {
        Selection op = new Selection("gradient:horizon", true, "Tester", "hi", "trail:petal", "contrail:aurora", "bye");
        var kept = Entitlements.sanitise(op, Rank.NONE, NOTHING, true);
        assertEquals(op, kept.selection());
        assertFalse(kept.changed());
        var deopped = Entitlements.sanitise(op, Rank.NONE, NOTHING, false);
        assertEquals(Selection.DEFAULT, deopped.selection());
    }

    @Test
    void aTrailIdCannotSitInTheContrailSlotOrViceVersa() {
        Selection swapped = new Selection(null, false, null, null, "contrail:vapour", "trail:cloudwalk", null);
        var out = Entitlements.sanitise(swapped, Rank.ADMIRAL, NOTHING);
        assertNull(out.selection().trail());
        assertNull(out.selection().contrail());
    }

    @Test
    void theOldFourFieldShapeStillWorks() {
        Selection old = new Selection("colour:gold", false, null, null);
        assertNull(old.trail());
        assertNull(old.contrail());
        assertNull(old.leaveMessage());
    }
}
