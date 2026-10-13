package com.coffeesaerosmp.auth.cosmetics;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CosmeticSyncDiffTest {

    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), C = new UUID(0, 3);

    /** Stand-in for the payload entry (a record, so equality is by value just like it). */
    private record Entry(UUID player, String trail, String contrail, String wings, String aura, boolean hidden, boolean self) {}

    private static Entry e(UUID u, String wings) {
        return new Entry(u, "trail:petal", null, wings, null, false, false);
    }

    @Test
    void onlyChangesAreSent() {
        var before = Map.of(A, e(A, null), B, e(B, "wings:seraph"));
        var after = Map.of(A, e(A, null), B, e(B, "wings:wyvern"), C, e(C, null));
        var d = MapDiff.of(before, after);
        assertEquals(2, d.upserts().size(), "B changed, C is new; A is unchanged");
        assertTrue(d.upserts().stream().noneMatch(x -> x.player().equals(A)));
        assertTrue(d.removed().isEmpty());
    }

    @Test
    void leaversAreRemovedAndNothingChangedIsEmpty() {
        var before = Map.of(A, e(A, null), B, e(B, null));
        var d = MapDiff.of(before, Map.of(A, e(A, null)));
        assertEquals(java.util.List.of(B), d.removed());
        assertTrue(d.upserts().isEmpty());
        assertTrue(MapDiff.of(before, before).isEmpty());
    }

    @Test
    void hidingIsAChange() {
        var shown = Map.of(A, e(A, "wings:seraph"));
        var hidden = Map.of(A, new Entry(A, "trail:petal", null, "wings:seraph", null, true, false));
        assertEquals(1, MapDiff.of(shown, hidden).upserts().size());
    }
}
