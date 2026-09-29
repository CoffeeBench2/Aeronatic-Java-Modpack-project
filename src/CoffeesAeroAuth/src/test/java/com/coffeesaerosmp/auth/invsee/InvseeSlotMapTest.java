package com.coffeesaerosmp.auth.invsee;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The numbers here are not guesses — they were read out of vanilla
 * {@code net/minecraft/world/entity/player/Inventory.java} (NeoForge 21.1.230 sources):
 * {@code compartments = ImmutableList.of(items(36), armor(4), offhand(1))}, and both
 * {@code getItem} and {@code setItem} walk that list in the same order.
 */
class InvseeSlotMapTest {

    @Test
    void theViewIsFortyFiveSlots() {
        assertEquals(45, InvseeSlotMap.VIEW_SIZE, "GENERIC_9x5 — five rows of nine");
    }

    @Test
    void theBackedRegionIsTheFortyOneInventorySlots() {
        assertEquals(41, InvseeSlotMap.BACKED_SIZE, "36 main + 4 armor + 1 offhand");
    }

    @Test
    void mainInventorySlotsMapStraightThrough() {
        for (int i = 0; i <= 35; i++) {
            assertEquals(i, InvseeSlotMap.toInventoryIndex(i), "main slot " + i);
        }
    }

    @Test
    void armourAndOffhandMapToTheirVanillaIndices() {
        assertEquals(36, InvseeSlotMap.toInventoryIndex(36));
        assertEquals(37, InvseeSlotMap.toInventoryIndex(37));
        assertEquals(38, InvseeSlotMap.toInventoryIndex(38));
        assertEquals(39, InvseeSlotMap.toInventoryIndex(39));
        assertEquals(40, InvseeSlotMap.toInventoryIndex(40), "offhand");
    }

    @Test
    void thePaddingSlotsMapToNothing() {
        for (int i = 41; i <= 44; i++) {
            assertEquals(-1, InvseeSlotMap.toInventoryIndex(i), "padding slot " + i);
        }
    }

    @Test
    void outOfRangeSlotsMapToNothingRatherThanThrowing() {
        assertEquals(-1, InvseeSlotMap.toInventoryIndex(-1));
        assertEquals(-1, InvseeSlotMap.toInventoryIndex(45));
        assertEquals(-1, InvseeSlotMap.toInventoryIndex(Integer.MAX_VALUE));
        assertEquals(-1, InvseeSlotMap.toInventoryIndex(Integer.MIN_VALUE));
    }

    @Test
    void inertSlotsAreExactlyTheUnmappedOnes() {
        assertFalse(InvseeSlotMap.isInert(0));
        assertFalse(InvseeSlotMap.isInert(40));
        assertTrue(InvseeSlotMap.isInert(41));
        assertTrue(InvseeSlotMap.isInert(44));
        assertTrue(InvseeSlotMap.isInert(-5));
        assertTrue(InvseeSlotMap.isInert(45));
    }

    /**
     * The property that actually matters: the mapping is a bijection over the backed region.
     *
     * <p>If two view slots ever resolved to one inventory index, an admin moving an item in one
     * would silently move it in the other. If an index were unreachable, part of the player's
     * inventory would be invisible — and invisible is worse than absent, because the admin would
     * conclude the item is not there.
     */
    @Test
    void everyBackedIndexIsReachableExactlyOnce() {
        boolean[] seen = new boolean[InvseeSlotMap.BACKED_SIZE];
        for (int v = 0; v < InvseeSlotMap.VIEW_SIZE; v++) {
            int idx = InvseeSlotMap.toInventoryIndex(v);
            if (idx < 0) continue;
            assertTrue(idx < InvseeSlotMap.BACKED_SIZE, "index " + idx + " is out of the backed range");
            assertFalse(seen[idx], "inventory index " + idx + " is reachable from two view slots");
            seen[idx] = true;
        }
        for (int i = 0; i < InvseeSlotMap.BACKED_SIZE; i++) {
            assertTrue(seen[i], "inventory index " + i + " is unreachable from the view");
        }
    }

    /** Padding must never be so large that it hides real slots, nor negative. */
    @Test
    void paddingIsExactlyTheDifferenceBetweenViewAndBacked() {
        int padding = 0;
        for (int v = 0; v < InvseeSlotMap.VIEW_SIZE; v++) {
            if (InvseeSlotMap.isInert(v)) padding++;
        }
        assertEquals(InvseeSlotMap.VIEW_SIZE - InvseeSlotMap.BACKED_SIZE, padding);
    }
}
