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
        // Spawn greeter: right-clicking an "aero_spawn_greeter"-tagged entity (an armor stand or an
        // Easy NPC placed by an admin) runs /spawn — works even inside the locked lobby, for everyone.
        if (event.getTarget().getTags().contains("aero_spawn_greeter")) {
            if (event.getEntity() instanceof ServerPlayer sp && CoffeesAeroAuth.AUTH_MANAGER != null) {
                // Dual-mode greeter. An Easy NPC ALSO tagged "aero_greeter_dialog" shows its own
                // welcome dialog to a player who has never entered the world, and teleports everyone
                // else instantly. Falling through — NOT cancelling — is what hands the click to Easy
                // NPC: NeoForge fires this event before Entity.interact() -> HumanoidRaw.mobInteract(),
                // so cancelling means Easy NPC never sees the click at all.
                // Authenticated-only, so an unapproved player still gets handleSpawn's explicit
                // "name must be approved" refusal instead of a dialog whose button just fails.
                if (CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(sp.getUUID())
                        && isFirstWorldEntry(sp)
                        && wantsDialogGreeting(event.getTarget())) {
                    return;
                }
                event.setCanceled(true);
                CoffeesAeroAuth.AUTH_MANAGER.handleSpawn(sp);
                return;
            }
            // Not a player, or the manager isn't up yet: consume the click rather than letting the
            // raw interaction (armor-stand equip screen, Easy NPC edit menu) through.
            event.setCanceled(true);
            return;
        }
        // Let players interact with Easy NPC entities in the lobby (dialogs / spawn actions). Easy NPC
        // has its own owner/op edit-protection, so this exposes only dialogs, never editing.
        if (isEasyNpc(event.getTarget())) return;
        // Lobby decor (item frames, armor stands, etc.) is untouchable for everyone but ops.
        if (shouldBlock(event.getEntity()) || lobbyLocked(event.getEntity())) event.setCanceled(true);
    }

    /** True for any entity from the Easy NPC mod, whatever NPC variant it is. */
    public static boolean isEasyNpc(net.minecraft.world.entity.Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null && "easy_npc".equals(key.getNamespace());
    }

    /**
     * True while the player has never completed a world entry — the same condition
     * {@link com.coffeesaerosmp.auth.auth.AuthManager#handleSpawn} calls {@code firstWorldEntry}.
     *
     * <p>Deliberately NOT {@code firstJoinComplete}: {@code completeApproval} sets that one while the
     * player is still standing in the lobby, so it is already true on the first-ever greeter click and
     * would send every brand-new player down the "returning player" path — the exact opposite of what
     * the dialog greeting is for.
     *
     * <p>⚠ A Season rollover that re-arms the starter bonus ({@code seasonGrantRewards}) clears
     * {@code startupBonusGiven}, so returning veterans see the welcome dialog again on their first
     * entry of the new season. That is intended — it is a season welcome — but it IS a behaviour
     * change at every rollover, not only for genuinely new players.
     */
    private static boolean isFirstWorldEntry(ServerPlayer player) {
        if (CoffeesAeroAuth.PROFILE_STORE == null) return false;
        com.coffeesaerosmp.auth.db.PlayerProfile profile =
            CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
        return profile != null && !profile.startupBonusGiven;
    }

    /**
     * True for an Easy NPC an admin opted into the dialog greeting with {@code aero_greeter_dialog}.
     *
     * <p>Restricted to Easy NPC on purpose. Falling through on an armor stand would open its equipment
     * screen instead of doing nothing, and falling through on an NPC with no dialog configured would
     * swallow the click and leave a new player with no way out of the lobby but the paper. Requiring a
     * second, explicit tag means the fall-through only ever happens where a dialog is known to exist.
     */
    private static boolean wantsDialogGreeting(net.minecraft.world.entity.Entity target) {
        return target.getTags().contains("aero_greeter_dialog") && isEasyNpc(target);
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
        // Easy NPC entities ARE the lobby greeters/NPCs — never remove them. They extend PathfinderMob,
        // so without this exemption the purge below would delete the greeter on every chunk load.
        if (isEasyNpc(event.getEntity())) return;
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

    // ── Lobby command whitelist ────────────────────────────────────────────────
    // The lobby has exactly ONE legitimate exit: /spawn (or the paper / the greeter, which both call
    // handleSpawn). That path restores the lobby stash AND pays the first-world-entry rewards — the
    // starter spurs and the Season veteran reward. ANY other teleport out of the lobby skips them
    // silently: /home restored the stash but never granted the rewards, and a third-party teleport
    // (grand-teleport, a waystone, a future mod) would skip the stash restore too, walking the player
    // into the world holding nothing but the spawn paper.
    //
    // Closed by default: a blocklist would have to name every teleport command in a 250-mod pack, and
    // would silently re-open the hole the next time one is added. Ops (perm 4) are exempt.

    private static volatile String   allowedRaw   = null;
    private static volatile java.util.Set<String> allowedCache = java.util.Set.of();

    public static void onLobbyCommand(net.neoforged.neoforge.event.CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!lobbyLocked(player)) return;                    // not in the lobby, or an op
        String root = commandRoot(event.getParseResults().getReader().getString());
        if (root.isEmpty() || allowedLobbyCommands().contains(root)) return;
        event.setCanceled(true);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            com.coffeesaerosmp.auth.util.TextUtil.PREFIX
            + "§7§o/" + root + "§7 doesn't work in the lobby. Type §a/spawn§7 to enter the world —"));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            com.coffeesaerosmp.auth.util.TextUtil.PREFIX
            + "§7that's what hands your inventory back and pays your arrival rewards."));
    }

    /** Root literal of a raw command string, lowercased, without the leading slash or a namespace. */
    private static String commandRoot(String raw) {
        String input = raw.trim();
        if (input.startsWith("/")) input = input.substring(1);
        int sp = input.indexOf(' ');
        String root = (sp < 0 ? input : input.substring(0, sp)).toLowerCase(java.util.Locale.ROOT);
        int colon = root.indexOf(':');
        return colon >= 0 ? root.substring(colon + 1) : root;
    }

    /** Parsed view of {@code lobbyAllowedCommands}, rebuilt only when the config string changes. */
    private static java.util.Set<String> allowedLobbyCommands() {
        String raw = com.coffeesaerosmp.auth.config.AuthConfig.LOBBY_ALLOWED_COMMANDS.get();
        if (!raw.equals(allowedRaw)) {
            java.util.Set<String> parsed = new java.util.HashSet<>();
            for (String part : raw.split(",")) {
                String s = part.trim().toLowerCase(java.util.Locale.ROOT);
                if (s.startsWith("/")) s = s.substring(1);
                if (!s.isEmpty()) parsed.add(s);
            }
            parsed.add("spawn");            // never removable — it is the only way out of the lobby
            allowedCache = java.util.Set.copyOf(parsed);
            allowedRaw   = raw;
        }
        return allowedCache;
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
