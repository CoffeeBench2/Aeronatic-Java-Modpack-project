package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import com.coffeesaerosmp.auth.store.StoreState.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The expiry semantics, which are the whole reason no scheduled task is needed to end a subscription.
 * Pure — {@link Snapshot} touches nothing but the wall clock.
 */
class StoreStateSnapshotTest {

    private static final long HOUR = 3_600_000L;

    private static Snapshot sub(Rank rank, long expiresAt, Set<String> owned, Selection sel) {
        return new Snapshot(rank, expiresAt, owned, sel);
    }

    @Test
    void anActiveSubscriptionGrantsItsRank() {
        Snapshot s = sub(Rank.ADMIRAL, System.currentTimeMillis() + HOUR, Set.of(), Selection.DEFAULT);
        assertEquals(Rank.ADMIRAL, s.effectiveRank());
        assertFalse(s.lapsed());
    }

    /** 🔑 No poller, no DB write, no restart: expiry is decided by comparing to the clock on read. */
    @Test
    void anExpiredSubscriptionGrantsNothing() {
        Snapshot s = sub(Rank.ADMIRAL, System.currentTimeMillis() - 1, Set.of(), Selection.DEFAULT);
        assertEquals(Rank.NONE, s.effectiveRank());
        assertTrue(s.lapsed());
    }

    @Test
    void noSubscriptionIsNotALapse() {
        assertEquals(Rank.NONE, Snapshot.EMPTY.effectiveRank());
        assertFalse(Snapshot.EMPTY.lapsed(), "never having subscribed is not the same as lapsing");
    }

    @Test
    void expiryExactlyNowCountsAsExpired() {
        // Boundary: expiresAt must be strictly in the future to still grant. An inclusive comparison
        // would keep a subscription alive for one extra millisecond, which is harmless, but pinning it
        // means the behaviour is a decision rather than an accident.
        Snapshot s = sub(Rank.CAPTAIN, System.currentTimeMillis(), Set.of(), Selection.DEFAULT);
        assertEquals(Rank.NONE, s.effectiveRank());
    }

    /** The end-to-end promise: a lapse strips the rented cosmetics and leaves the bought one. */
    @Test
    void liveSelectionStripsRankCosmeticsOnceExpiredButKeepsOwnedOnes() {
        Snapshot active = sub(Rank.ADMIRAL, System.currentTimeMillis() + HOUR,
                              Set.of("colour:blue"),
                              new Selection("gradient:horizon", true, "Skyfarer", "wheels up"));
        Selection live = active.liveSelection();
        assertEquals("gradient:horizon", live.nameStyle());
        assertEquals("Skyfarer", live.chatTitle());

        Snapshot expired = sub(Rank.ADMIRAL, System.currentTimeMillis() - HOUR,
                               Set.of("colour:blue"),
                               new Selection("gradient:horizon", true, "Skyfarer", "wheels up"));
        Selection after = expired.liveSelection();
        assertEquals("colour:blue", after.nameStyle(), "must fall back to the Bean-bought colour");
        assertFalse(after.bold());
        assertNull(after.chatTitle());
        assertNull(after.joinMessage());
    }

    @Test
    void liveSelectionIsSafeOnTheEmptySnapshot() {
        assertEquals(Selection.DEFAULT, Snapshot.EMPTY.liveSelection());
    }

    @Test
    void emptySnapshotOwnsNothingAndIsImmutable() {
        assertTrue(Snapshot.EMPTY.owned().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                     () -> Snapshot.EMPTY.owned().add("colour:gold"));
    }
}
