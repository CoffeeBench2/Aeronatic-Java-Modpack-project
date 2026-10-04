package com.coffeesaerosmp.guard.protect;

import com.coffeesaerosmp.guard.CoffeesAeroGuard;
import com.coffeesaerosmp.guard.config.GuardConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * End lock (2026-07-12): while {@code lockEndDimension} is on, players cannot enter
 * {@code minecraft:the_end} by ANY route — End portals, waystones, grave recalls, /tpa,
 * FTB teleports — they all funnel through the same dimension-travel event. Ops (permission 2+)
 * bypass so admins can inspect. Sable ship sub-levels and the auth lobby dimension are separate
 * dimensions and stay untouched. Config is hot-reloadable, so unlocking is a TOML edit — no restart,
 * no new jar.
 *
 * <p>Nether lock (2026-10-04, Season 3): {@code netherOpensAt} keeps the Nether shut until a set moment
 * — "the Nether opens three days after launch" — with the same routes and bypass. A time rather than a
 * switch, so it opens on schedule without anyone online to flip it.
 */
public final class DimensionLock {

    /** Standing in a portal re-fires the event every tick — message at most once per 3s. */
    private static final long MESSAGE_COOLDOWN_MS = 3_000;
    private static final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();

    /** Last config string we warned about, so a bad value is logged once, not every portal tick. */
    private static volatile String warnedValue;

    private DimensionLock() {}

    public static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.hasPermissions(2)) return;   // admins may inspect

        Component refusal = null;
        if (event.getDimension().equals(Level.END) && GuardConfig.LOCK_END_DIMENSION.get()) {
            refusal = Component.literal(
                "§5§l✦ §dThe End is still under construction §7— flight routes there open soon!");
        } else if (event.getDimension().equals(Level.NETHER)) {
            Duration left = netherTimeLeft(GuardConfig.NETHER_OPENS_AT.get(), OffsetDateTime.now());
            if (left != null) {
                refusal = Component.literal("§c§l🔥 §6The Nether is sealed §7— it opens in §f"
                    + humanize(left) + "§7.");
            }
        }
        if (refusal == null) return;

        event.setCanceled(true);
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUUID());
        if (last == null || now - last > MESSAGE_COOLDOWN_MS) {
            lastMessage.put(player.getUUID(), now);
            player.sendSystemMessage(refusal);
        }
    }

    /**
     * Time until the Nether opens, or null if it is open now (blank, past, or unparseable value).
     * Package-visible and free of game state for the unit test.
     */
    static Duration netherTimeLeft(String opensAt, OffsetDateTime now) {
        if (opensAt == null || opensAt.isBlank()) return null;
        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(opensAt.trim());
        } catch (DateTimeParseException e) {
            if (!opensAt.equals(warnedValue)) {
                warnedValue = opensAt;
                CoffeesAeroGuard.LOGGER.warn("[DimensionLock] netherOpensAt '{}' is not ISO-8601 with an offset "
                    + "(e.g. 2026-10-10T18:00:00+05:30) — the Nether stays OPEN.", opensAt);
            }
            return null;
        }
        Duration left = Duration.between(now, at);
        return left.isNegative() || left.isZero() ? null : left;
    }

    /** "2d 4h", "5h 12m", "3m" — coarse on purpose; this is a portal message, not a timer. */
    static String humanize(Duration d) {
        long mins = Math.max(1, (d.getSeconds() + 59) / 60);
        long days = mins / 1440, hours = (mins % 1440) / 60, m = mins % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + m + "m";
        return m + "m";
    }
}
