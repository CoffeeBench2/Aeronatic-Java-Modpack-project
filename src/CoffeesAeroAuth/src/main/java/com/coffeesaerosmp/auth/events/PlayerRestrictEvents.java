package com.coffeesaerosmp.auth.events;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.lobby.LobbyInventoryStash;
import com.coffeesaerosmp.auth.lobby.PrivateRoomManager;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class PlayerRestrictEvents {

    /**
     * Fires every tick for every living entity.
     * For unauthenticated players: freezes position and checks auth timeout.
     */
    public static void onLivingTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (CoffeesAeroAuth.AUTH_MANAGER == null) return;
        CoffeesAeroAuth.AUTH_MANAGER.onTick(player);
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (shouldBlock(event.getEntity())) event.setCanceled(true);
    }

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // "Teleport to Spawn" lobby paper: an authenticated player in the lobby uses it to enter the
        // world (same as /spawn — which restores their stashed inventory). Always consume the click so
        // the paper itself never does anything else.
        if (event.getEntity() instanceof ServerPlayer player
                && LobbyInventoryStash.isLobbyPaper(event.getItemStack())
                && player.level().dimension() == PrivateRoomManager.LOBBY_DIMENSION) {
            event.setCanceled(true);
            if (CoffeesAeroAuth.AUTH_MANAGER != null
                    && CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) {
                CoffeesAeroAuth.AUTH_MANAGER.handleSpawn(player);
            }
            return;
        }
        if (shouldBlock(event.getEntity())) event.setCanceled(true);
    }

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (shouldBlock(event.getEntity())) event.setCanceled(true);
    }

    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (shouldBlock(event.getEntity())) event.setCanceled(true);
    }

    public static void onAttackEntity(AttackEntityEvent event) {
        if (shouldBlock(event.getEntity())) event.setCanceled(true);
    }

    private static boolean shouldBlock(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        return CoffeesAeroAuth.AUTH_MANAGER != null
            && !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID());
    }
}
