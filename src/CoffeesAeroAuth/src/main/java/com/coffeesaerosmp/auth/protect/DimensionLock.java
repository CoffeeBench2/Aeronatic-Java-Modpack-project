package com.coffeesaerosmp.auth.protect;

import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * End lock (2026-07-12): while {@code lockEndDimension} is on, players cannot enter
 * {@code minecraft:the_end} by ANY route — End portals, waystones, grave recalls, /tpa,
 * FTB teleports — they all funnel through the same dimension-travel event. Ops (permission 2+)
 * bypass so admins can inspect. Targets ONLY the_end: Sable ship sub-levels, the auth lobby
 * dimension and the nether are separate dimensions and stay untouched. Config is hot-reloadable,
 * so unlocking is a TOML edit — no restart, no new jar.
 */
public final class DimensionLock {

    /** Standing in a portal re-fires the event every tick — message at most once per 3s. */
    private static final long MESSAGE_COOLDOWN_MS = 3_000;
    private static final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    private DimensionLock() {}

    public static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!AuthConfig.LOCK_END_DIMENSION.get()) return;
        if (!event.getDimension().equals(Level.END)) return;
        if (player.hasPermissions(2)) return;   // admins may inspect

        event.setCanceled(true);
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUUID());
        if (last == null || now - last > MESSAGE_COOLDOWN_MS) {
            lastMessage.put(player.getUUID(), now);
            player.sendSystemMessage(Component.literal(
                "§5§l✦ §dThe End is still under construction §7— flight routes there open soon!"));
        }
    }
}
