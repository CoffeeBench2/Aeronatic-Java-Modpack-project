package com.coffeesaerosmp.auth.cosmetics;

/**
 * Which ticks an emitter fires on. The engine only runs on EVEN ticks, so the stagger has to be counted in emit
 * ticks, not raw ticks.
 *
 * <p>🔴 1.13.30 bug caught by GameTest before it ever shipped: the first version tested
 * {@code (tick + hash) % everyTicks == 0} on even ticks only — for an even {@code everyTicks} a player whose hash was
 * ODD could never satisfy it, so about half of all players would never have shown their trail. Pure, so every hash
 * is unit-tested.
 */
public final class Stagger {

    private Stagger() {}

    /**
     * @param tick       the server tick (the engine calls this on even ticks only)
     * @param key        a stable per-emitter number (uuid hash) that spreads emitters across ticks
     * @param everyTicks the cosmetic's interval in ticks (>= 2)
     */
    public static boolean due(long tick, int key, int everyTicks) {
        long emitTick = tick / 2;                          // the engine's own clock
        int period = Math.max(1, everyTicks / 2);          // interval in emit ticks
        return Math.floorMod(emitTick + (key & 0x7fff), period) == 0;
    }
}
