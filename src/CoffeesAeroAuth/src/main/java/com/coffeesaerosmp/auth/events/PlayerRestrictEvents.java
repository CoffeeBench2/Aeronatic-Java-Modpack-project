package com.coffeesaerosmp.auth.events;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.lobby.LobbyInventoryStash;
import com.coffeesaerosmp.auth.lobby.PrivateRoomManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

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
        if (shouldBlock(event.getEntity())) { event.setCanceled(true); return; }
        // In the lobby everything is locked: the ONLY blocks anyone may right-click are the vendor
        // (which dispenses meat) and levers. Ops are exempt so they can still build/manage the lobby.
        if (lobbyLocked(event.getEntity())) {
            BlockState clicked = event.getLevel().getBlockState(event.getPos());
            if (!isLobbyInteractable(clicked)) event.setCanceled(true);
        }
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
        // Lobby decor (item frames, armor stands, etc.) is untouchable for everyone but ops.
        if (shouldBlock(event.getEntity()) || lobbyLocked(event.getEntity())) event.setCanceled(true);
    }

    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (shouldBlock(event.getEntity()) || lobbyLocked(event.getEntity())) event.setCanceled(true);
    }

    public static void onAttackEntity(AttackEntityEvent event) {
        if (shouldBlock(event.getEntity()) || lobbyLocked(event.getEntity())) event.setCanceled(true);
    }

    /** The only blocks anyone may interact with in the locked lobby: the meat vendor and levers. */
    private static boolean isLobbyInteractable(BlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.LEVER) return true;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
        return id != null && "numismatics".equals(id.getNamespace()) && "vendor".equals(id.getPath());
    }

    // ── Lobby grief protection: NOBODY (cracked OR premium) may break/place in the auth lobby ──
    // (Operators are exempt so admins can design the lobby via /lobby.)

    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (lobbyLocked(event.getPlayer())) event.setCanceled(true);
    }

    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && lobbyLocked(p)) event.setCanceled(true);
    }

    /** No Q-dropping in the lobby (or while unauthenticated): a tossed spawn-paper would strand the
     *  player, and loose items would litter a room that may later be recycled to someone else. The
     *  toss event fires AFTER the stack left the inventory, so on cancel we must hand it back. */
    public static void onItemToss(net.neoforged.neoforge.event.entity.item.ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer sp)) return;
        if (shouldBlock(sp) || lobbyLocked(sp)) {
            event.setCanceled(true);
            sp.getInventory().add(event.getEntity().getItem());
        }
    }

    private static boolean lobbyLocked(net.minecraft.world.entity.player.Player player) {
        return player instanceof ServerPlayer sp
            && sp.level().dimension() == PrivateRoomManager.LOBBY_DIMENSION
            && !sp.hasPermissions(4);
    }

    private static boolean shouldBlock(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        return CoffeesAeroAuth.AUTH_MANAGER != null
            && !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID());
    }
}
