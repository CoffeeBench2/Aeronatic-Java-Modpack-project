package com.coffeesaerosmp.auth.moderation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConfiscationTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void reset() {
        Confiscation.clearAll();
    }

    @Test
    void nobodyIsHeldByDefault() {
        assertFalse(Confiscation.isHeld(A));
        assertNull(Confiscation.get(A));
        assertTrue(Confiscation.all().isEmpty());
    }

    @Test
    void holdMakesThePlayerHeldAndRecordsWhoAndWhy() {
        Confiscation.hold(new Confiscation.Hold(A, "griefing spawn", "MrCoffeeBench", 1000L));
        assertTrue(Confiscation.isHeld(A));
        assertFalse(Confiscation.isHeld(B), "holding one player must not hold another");

        Confiscation.Hold h = Confiscation.get(A);
        assertNotNull(h);
        assertEquals("griefing spawn", h.reason());
        assertEquals("MrCoffeeBench", h.actor());
        assertEquals(1000L, h.startedEpoch());
    }

    @Test
    void releaseReturnsTheHoldAndClearsIt() {
        Confiscation.hold(new Confiscation.Hold(A, "r", "op", 1L));
        Confiscation.Hold released = Confiscation.release(A);

        assertNotNull(released);
        assertEquals("r", released.reason());
        assertFalse(Confiscation.isHeld(A));
    }

    @Test
    void releasingSomeoneNotHeldIsNullAndNotAnError() {
        assertNull(Confiscation.release(A));
    }

    @Test
    void holdingTwiceOverwritesRatherThanDuplicating() {
        Confiscation.hold(new Confiscation.Hold(A, "first", "op1", 1L));
        Confiscation.hold(new Confiscation.Hold(A, "second", "op2", 2L));

        assertEquals(1, Confiscation.all().size());
        assertEquals("second", Confiscation.get(A).reason());
        assertEquals("op2", Confiscation.get(A).actor());
    }

    /**
     * The predicate runs every tick for every player. A null uuid must be cheap and false rather
     * than an exception that would take the tick loop down.
     */
    @Test
    void nullUuidIsNotHeldRatherThanThrowing() {
        assertFalse(Confiscation.isHeld(null));
        assertNull(Confiscation.get(null));
    }

    @Test
    void loadAllReplacesTheWholeSetSoABootIsAuthoritative() {
        Confiscation.hold(new Confiscation.Hold(A, "stale", "op", 1L));
        Confiscation.loadAll(java.util.List.of(new Confiscation.Hold(B, "fresh", "op", 2L)));

        assertFalse(Confiscation.isHeld(A), "loadAll must not merge with pre-existing state");
        assertTrue(Confiscation.isHeld(B));
        assertEquals(1, Confiscation.all().size());
    }

    @Test
    void allReturnsASnapshotThatDoesNotBreakWhenTheMapChanges() {
        Confiscation.hold(new Confiscation.Hold(A, "a", "op", 1L));
        var snapshot = Confiscation.all();
        Confiscation.hold(new Confiscation.Hold(B, "b", "op", 2L));
        assertEquals(1, snapshot.size(), "an already-taken snapshot must not see later writes");
        assertEquals(2, Confiscation.all().size());
    }

    @Test
    void releaseAlsoClearsTheNullCaseSafely() {
        assertNull(Confiscation.release(null));
    }

    /** The javadoc says reason may be null — a system-initiated hold has no human explanation. */
    @Test
    void aHoldWithNoReasonIsStillAValidHold() {
        Confiscation.hold(new Confiscation.Hold(A, null, "system", 5L));
        assertTrue(Confiscation.isHeld(A));
        assertNotNull(Confiscation.get(A));
        assertNull(Confiscation.get(A).reason());
        assertEquals("system", Confiscation.get(A).actor());
        assertEquals(1, Confiscation.all().size());
    }
}
