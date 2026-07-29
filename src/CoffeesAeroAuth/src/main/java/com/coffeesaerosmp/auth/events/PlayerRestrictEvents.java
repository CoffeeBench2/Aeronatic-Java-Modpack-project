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

        // Lobby container lockdown: the inventory stash only clears the VANILLA inventory
        // (main+armor+offhand), so an equipped Sophisticated Backpack — which lives in an
        // Accessories slot, not a vanilla slot — rides into the lobby and its contents stay
        // reachable. Slam shut any menu that isn't the player's own (empty) inventory: backpacks,
        // the accessories screen, chests, anything opened via openMenu. Done on the tick (not in
        // the open event) to avoid mid-openMenu reentrancy; the menu lives at most one tick.
        // NO items are moved — zero data-loss risk (unlike serializing/clearing the accessories
        // capability, which could eat a backpack on a bad restore). Ops (perm 4) are exempt.
        if (player.containerMenu != player.inventoryMenu && lobbyLocked(player)) {
            player.closeContainer();
        }
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
        // Spawn greeter: right-clicking a "aero_spawn_greeter"-tagged entity (a dressed armor stand
        // placed by an admin) runs /spawn — works even inside the locked lobby, for everyone.
        if (event.getTarget().getTags().contains("aero_spawn_greeter")) {
            event.setCanceled(true);
            if (event.getEntity() instanceof ServerPlayer sp && CoffeesAeroAuth.AUTH_MANAGER != null) {
                CoffeesAeroAuth.AUTH_MANAGER.handleSpawn(sp);
            }
            return;
        }
        // Let players interact with Easy NPC entities in the lobby (dialogs / spawn actions). Easy NPC
        // has its own owner/op edit-protection, so this exposes only dialogs, never editing.
        net.minecraft.resources.ResourceLocation npcKey =
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(event.getTarget().getType());
        if (npcKey != null && "easy_npc".equals(npcKey.getNamespace())) return;
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

    /** No damage of any kind in the lobby (fall/PvP/drown/mob). Covers the fall-catch window and the
     *  "can't hit each other" rule for melee AND projectiles. Ops included — the lobby is a safe zone. */
    public static void onIncomingDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp
                && sp.level().dimension() == PrivateRoomManager.LOBBY_DIMENSION) {
            event.setCanceled(true);
        }
    }

    /** No item pickup in the lobby (belt-and-braces; there should be no ground items anyway). */
    public static void onItemPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer sp
                && sp.level().dimension() == PrivateRoomManager.LOBBY_DIMENSION) {
            event.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE);
        }
    }

    /** Ban ALL mobs from the lobby dimension — natural spawns, modded critters (crows/hamsters), even
     *  ones saved in the pasted map. Only {@link net.minecraft.world.entity.Mob}s are removed, so
     *  players, armor-stand greeters, item frames, paintings and dropped items are untouched. */
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || event.getLevel().dimension() != PrivateRoomManager.LOBBY_DIMENSION
                || !(event.getEntity() instanceof net.minecraft.world.entity.Mob)) {
            return;
        }
        // Easy NPC entities (namespace "easy_npc") ARE the lobby greeters/NPCs — never remove them.
        net.minecraft.resources.ResourceLocation key =
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType());
        if (key != null && "easy_npc".equals(key.getNamespace())) return;
        event.setCanceled(true);
    }

    // ── Die-in-lobby safety net (lobby damage is off, but /kill or a void fall could still kill) ──
    private static final java.util.Set<java.util.UUID> diedInLobby = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Remember a lobby death so we can send them back to the lobby (not their overworld bed). */
    public static void onLobbyDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp
                && sp.level().dimension() == PrivateRoomManager.LOBBY_DIMENSION) {
            diedInLobby.add(sp.getUUID());
        }
    }

    /** On respawn after a lobby death: back to the lobby spawn, with the spawn paper (real inventory
     *  stays safely stashed in the DB — it was never in their hands in the lobby). */
    public static void onLobbyRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!diedInLobby.remove(sp.getUUID())) return;
        net.minecraft.server.level.ServerLevel lobby = sp.getServer() != null
            ? sp.getServer().getLevel(PrivateRoomManager.LOBBY_DIMENSION) : null;
        double[] pad = PrivateRoomManager.spawnPad();
        if (lobby != null) sp.teleportTo(lobby, pad[0], pad[1], pad[2], java.util.Set.of(), 180.0f, 0.0f);
        boolean hasPaper = false;
        for (net.minecraft.world.item.ItemStack s : sp.getInventory().items) {
            if (com.coffeesaerosmp.auth.lobby.LobbyInventoryStash.isLobbyPaper(s)) { hasPaper = true; break; }
        }
        if (!hasPaper) sp.getInventory().add(com.coffeesaerosmp.auth.lobby.LobbyInventoryStash.makeLobbyPaper());
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
