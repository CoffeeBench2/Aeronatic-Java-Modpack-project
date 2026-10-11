package com.coffeesaerosmp.auth.world;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Removes stale gun projectiles ({@link ProjectileJanitorRules} has the why). Two parts:
 * <ul>
 *   <li>a bullet LOADED FROM DISK is refused on join — it can only be a leftover;</li>
 *   <li>every {@value #SWEEP_TICKS} ticks, bullets that have existed longer than
 *       {@code projectileMaxAgeSeconds} are discarded — counted from first sight, so frozen ones age.</li>
 * </ul>
 * Live gunfire is never touched. Every 5 minutes one INFO line reports how many were alive and removed.
 */
public final class ProjectileJanitor {

    static final int SWEEP_TICKS = 400;                  // 20 s
    private static final int REPORT_TICKS = 6000;        // 5 min

    /** Server tick at which each tracked projectile was first seen. Pruned every sweep. */
    private static final Map<UUID, Long> FIRST_SEEN = new HashMap<>();
    private static long now;
    private static long lastReport;
    private static int removedSinceReport, droppedOnLoadSinceReport, peakAlive;

    private ProjectileJanitor() {}

    public static void register(IEventBus bus) {
        bus.addListener(ProjectileJanitor::onJoin);
        bus.addListener((ServerTickEvent.Post e) -> onTick(e.getServer()));
    }

    private static boolean enabled() {
        try { return AuthConfig.PROJECTILE_CLEANUP_ENABLED.get(); } catch (Exception e) { return false; }
    }

    private static Set<String> types() {
        try { return ProjectileJanitorRules.types(AuthConfig.PROJECTILE_CLEANUP_TYPES.get()); }
        catch (Exception e) { return Set.of(); }
    }

    private static long maxTicks() {
        try { return AuthConfig.PROJECTILE_MAX_AGE_SECONDS.get() * 20L; } catch (Exception e) { return 60 * 20L; }
    }

    static String id(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
    }

    private static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        if (ProjectileJanitorRules.dropOnLoad(enabled(), types().contains(id(e)), event.loadedFromDisk())) {
            event.setCanceled(true);
            droppedOnLoadSinceReport++;
        }
    }

    private static void onTick(MinecraftServer server) {
        now++;
        if (now % SWEEP_TICKS != 0 || !enabled()) return;
        int alive = sweep(server, types(), maxTicks(), now, true);
        peakAlive = Math.max(peakAlive, alive);
        if (now - lastReport >= REPORT_TICKS) {
            lastReport = now;
            if (removedSinceReport + droppedOnLoadSinceReport > 0 || peakAlive > 0) {
                CoffeesAeroAuth.LOGGER.info("[ProjectileJanitor] last 5 min: peak {} live projectiles, removed {} stale, refused {} saved ones on chunk load",
                    peakAlive, removedSinceReport, droppedOnLoadSinceReport);
            }
            removedSinceReport = droppedOnLoadSinceReport = peakAlive = 0;
        }
    }

    /**
     * One pass over every level. Returns how many listed projectiles were still alive afterwards.
     * Package-private with explicit inputs so a GameTest can drive it with any entity type and clock.
     */
    static int sweep(MinecraftServer server, Set<String> types, long maxTicks, long nowTick, boolean count) {
        if (types.isEmpty()) return 0;
        Set<UUID> present = new HashSet<>();
        Map<String, Integer> removedByType = new TreeMap<>();
        int alive = 0;
        for (ServerLevel level : server.getAllLevels()) {
            // Snapshot first: discarding while iterating the live entity view is unsafe (see ItemClearer).
            List<Entity> snapshot = new ArrayList<>();
            for (Entity e : level.getAllEntities()) snapshot.add(e);
            for (Entity e : snapshot) {
                if (e.isRemoved()) continue;
                String type = id(e);
                if (!types.contains(type)) continue;
                long firstSeen = FIRST_SEEN.computeIfAbsent(e.getUUID(), u -> nowTick);
                if (ProjectileJanitorRules.stale(nowTick, firstSeen, e.tickCount, maxTicks)) {
                    e.discard();
                    removedByType.merge(type, 1, Integer::sum);
                } else {
                    present.add(e.getUUID());
                    alive++;
                }
            }
        }
        FIRST_SEEN.keySet().retainAll(present);
        if (count) for (int n : removedByType.values()) removedSinceReport += n;
        return alive;
    }

    /** Test hook: forget everything tracked. */
    static void reset() {
        FIRST_SEEN.clear();
    }
}
