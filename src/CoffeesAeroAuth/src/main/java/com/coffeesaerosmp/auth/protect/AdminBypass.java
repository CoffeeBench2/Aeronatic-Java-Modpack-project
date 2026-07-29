package com.coffeesaerosmp.auth.protect;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Admin protection bypass ("god mode" for claim protection). aeroclaims (and other claim mods) block
 * interaction by cancelling the interact/break/place events — and aeroclaims has NO admin bypass of
 * its own. When an op toggles bypass on via {@code /aerobypass}, these handlers run at LOWEST priority
 * AND receive already-cancelled events (registered that way in {@link com.coffeesaerosmp.auth.CoffeesAeroAuth}),
 * so they run after the protection mod's {@code setCanceled(true)} and REVERSE it for that admin —
 * letting them open/break/place on any claimed ship without owning the claim. Nothing is written to the
 * claim; ownership is unchanged. Off by default, per-session, cleared on logout. Requires perm level 2.
 */
public final class AdminBypass {

    private AdminBypass() {}

    private static final Set<UUID> BYPASS = ConcurrentHashMap.newKeySet();

    /** Toggle bypass for this player; returns the new state (true = now ON). */
    public static boolean toggle(UUID uuid) {
        if (!BYPASS.add(uuid)) { BYPASS.remove(uuid); return false; }
        return true;
    }

    public static boolean isEnabled(UUID uuid) { return BYPASS.contains(uuid); }

    public static void clear(UUID uuid) { BYPASS.remove(uuid); }

    private static boolean bypassing(Entity e) {
        return e instanceof ServerPlayer sp && sp.hasPermissions(2) && BYPASS.contains(sp.getUUID());
    }

    // ── Handlers (LOWEST priority + receiveCancelled=true) — reverse the protection cancel ──

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (bypassing(event.getEntity())) {
            event.setCanceled(false);
            event.setUseBlock(TriState.DEFAULT);   // let vanilla open the container again
            event.setUseItem(TriState.DEFAULT);
        }
    }

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (bypassing(event.getEntity())) event.setCanceled(false);
    }

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (bypassing(event.getPlayer())) event.setCanceled(false);
    }

    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (bypassing(event.getEntity())) event.setCanceled(false);
    }
}
