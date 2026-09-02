package com.coffeesaerosmp.auth.world;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.List;

/**
 * Periodic ground-item clear with a countdown, so players are never surprised by it.
 *
 * <h2>What it does and does not touch</h2>
 * Only {@link ItemEntity} — items lying loose in the world. 🔑 <b>Create machinery is unaffected:</b>
 * belts, chutes, depots, funnels and vaults hold their contents in BLOCK ENTITIES, not as dropped
 * entities, so a clear cannot eat a running factory. Chests, backpacks and player inventories are
 * likewise untouched.
 *
 * <p>Items with a custom name are skipped when {@code itemClearKeepNamed} is on (the default) —
 * a renamed item is almost always someone's deliberate keepsake rather than litter.</p>
 *
 * <p>The countdown schedule lives in {@link ClearSchedule}, which is pure and unit-tested. It fires
 * on threshold CROSSING rather than equality, because this server's tick loop skips seconds under
 * load and an equality check would silently swallow warnings exactly when the server is busiest.</p>
 */
public final class ItemClearer {

    private ItemClearer() {}

    private static int  ticks;
    private static long nextClearAtMs;
    private static int  previousRemaining = Integer.MAX_VALUE;

    /** Resets the countdown. Call at server start. */
    public static void initialize() {
        ticks = 0;
        previousRemaining = Integer.MAX_VALUE;
        nextClearAtMs = System.currentTimeMillis()
            + AuthConfig.ITEM_CLEAR_INTERVAL_MINUTES.get() * 60_000L;
    }

    /** Call every tick. Throttled to once per second — the common path is an increment. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++ticks % 20 != 0) return;
        if (!AuthConfig.ITEM_CLEAR_ENABLED.get()) return;

        try {
            final long now = System.currentTimeMillis();
            if (nextClearAtMs == 0) { initialize(); return; }

            int remaining = (int) Math.max(0, (nextClearAtMs - now) / 1000L);

            int warnAt = ClearSchedule.crossed(
                ClearSchedule.parse(AuthConfig.ITEM_CLEAR_WARN_SECONDS.get()),
                previousRemaining, remaining);
            if (warnAt > 0) broadcast(server, ClearSchedule.warning(warnAt));

            previousRemaining = remaining;

            if (remaining <= 0) {
                int cleared = clearNow(server);
                broadcast(server, ClearSchedule.cleared(cleared));
                CoffeesAeroAuth.LOGGER.info("[ItemClear] Removed {} ground item(s).", cleared);
                nextClearAtMs = now + AuthConfig.ITEM_CLEAR_INTERVAL_MINUTES.get() * 60_000L;
                previousRemaining = Integer.MAX_VALUE;
            }
        } catch (Exception e) {
            // A janitor must never be able to take the tick loop down.
            CoffeesAeroAuth.LOGGER.warn("[ItemClear] skipped: {}", e.toString());
        }
    }

    /** Clears ground items in every loaded level. Returns how many entities were removed. */
    public static int clearNow(MinecraftServer server) {
        final boolean keepNamed = AuthConfig.ITEM_CLEAR_KEEP_NAMED.get();
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            // Snapshot first: discarding while iterating the live entity view is how you get a
            // ConcurrentModificationException on the tick loop.
            List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class,
                net.minecraft.world.phys.AABB.INFINITE, e -> e.isAlive()
                    && !(keepNamed && e.getItem().has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)));
            for (ItemEntity e : items) {
                e.discard();
                removed++;
            }
        }
        return removed;
    }

    /** Seconds until the next clear, for status commands. -1 when disabled. */
    public static int secondsUntilClear() {
        if (!AuthConfig.ITEM_CLEAR_ENABLED.get() || nextClearAtMs == 0) return -1;
        return (int) Math.max(0, (nextClearAtMs - System.currentTimeMillis()) / 1000L);
    }

    private static void broadcast(MinecraftServer server, String line) {
        Component c = Component.literal(TextUtil.PREFIX + line);
        server.getPlayerList().broadcastSystemMessage(c, false);
    }
}
