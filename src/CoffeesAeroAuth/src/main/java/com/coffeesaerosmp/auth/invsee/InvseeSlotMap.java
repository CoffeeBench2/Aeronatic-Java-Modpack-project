package com.coffeesaerosmp.auth.invsee;

/**
 * Maps the 45 slots of a five-row chest view onto the 41 slots of a player {@code Inventory}.
 *
 * <h2>Why a mapping is needed at all</h2>
 * {@code Inventory} is a {@code Container} of 41, which is not a vanilla menu size — every stock
 * chest menu is a multiple of 9. Rather than invent a custom screen (which would need a client mod,
 * and this is a server-only jar), the view is a standard five-row chest and the last four slots are
 * inert padding.
 *
 * <h2>Why the mapping is the identity</h2>
 * Verified against vanilla {@code net/minecraft/world/entity/player/Inventory.java} (NeoForge
 * 21.1.230 sources), not assumed:
 * <pre>
 *   compartments = ImmutableList.of(items(36), armor(4), offhand(1))   // 41
 * </pre>
 * and both {@code getItem} and {@code setItem} walk that list in the same order, subtracting each
 * compartment's size as they go. So inventory index 0–35 is the main inventory, 36–39 armour and 40
 * the offhand — already exactly the order a flat view wants. Inventing a translation would only
 * create an opportunity to get it wrong.
 *
 * <h2>Scope</h2>
 * Vanilla inventory only. Curios and Accessories slots are a different container entirely and are
 * NOT covered here — see {@code /invsee_curios}.
 *
 * <p>Pure integer arithmetic, no Minecraft imports, so the mapping is unit-testable.
 */
public final class InvseeSlotMap {

    /** Five rows of nine — {@code MenuType.GENERIC_9x5}. */
    public static final int VIEW_SIZE = 45;

    /** Slots backed by the real inventory: 0–40. The remainder is padding. */
    public static final int BACKED_SIZE = 41;

    private InvseeSlotMap() {}

    /**
     * The {@code Inventory} index behind a view slot, or {@code -1} if the slot is padding or out of
     * range. Never throws — this is called from menu click handling, where an exception would
     * propagate into a player's session.
     */
    public static int toInventoryIndex(int viewSlot) {
        if (viewSlot < 0 || viewSlot >= BACKED_SIZE) return -1;
        return viewSlot;
    }

    /** True for slots that must render as a barrier and swallow clicks. */
    public static boolean isInert(int viewSlot) {
        return toInventoryIndex(viewSlot) < 0;
    }
}
