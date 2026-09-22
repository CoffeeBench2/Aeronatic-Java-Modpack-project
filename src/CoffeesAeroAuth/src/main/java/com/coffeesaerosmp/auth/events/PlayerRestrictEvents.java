package com.coffeesaerosmp.auth.events;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.lobby.LobbyInventoryStash;
import com.coffeesaerosmp.auth.lobby.LobbyManager;
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

    /** Where each held player was pinned, so they cannot drift. Cleared the moment the hold lifts. */
    private static final java.util.Map<java.util.UUID, double[]> HELD_POS =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Fires every tick for every ticking entity; we act only on players.
     * For unauthenticated players: freezes position and checks auth timeout.
     *
     * <h3>🔴 THIS HANDLER TELEPORTS, SO IT MUST NOT RUN ON {@code PlayerTickEvent}</h3>
     * It was moved to {@code PlayerTickEvent.Pre} on 2026-09-07 to kill a whole-world entity scan,
     * and that <b>broke movement for everyone in the lobby</b> — reverted the same day. The reason is
     * in {@code ServerGamePacketListenerImpl.tick()}:
     *
     * <pre>
     *   this.resetPosition();                                  // firstGood = current position
     *   this.player.doTick();                                  // &lt;- PlayerTickEvent fires in here
     *   this.player.absMoveTo(this.firstGoodX, firstGoodY, firstGoodZ, ...);   // &lt;- reverts it
     * </pre>
     *
     * A {@code teleportTo} performed inside {@code doTick()} is undone by {@code absMoveTo} on the
     * very next line — but {@code connection.teleport()} has already armed
     * {@code awaitingPositionFromClient}, and while that field is set {@code handleMovePlayer}
     * DISCARDS every movement packet the client sends. Re-arming it every tick means the client can
     * never acknowledge, so the player is permanently unable to move. 🔑 **A position change is only
     * safe outside the connection tick** — which is where {@code EntityTickEvent} (level ticking) and
     * ordinary commands both run.
     *
     * <p>The entity-scan cost that prompted the move is real (this event is posted for every ticking
     * entity in every level) but it is not worth breaking movement for. {@code WatchdogEvents} stays
     * on {@code PlayerTickEvent} — it only zeroes VELOCITY, which {@code absMoveTo} does not touch —
     * so half the saving is kept. If the scan is ever worth removing properly, the correct home is
     * {@code ServerTickEvent.Post} iterating {@code getPlayerList().getPlayers()}: player-scoped AND
     * outside the connection tick.
     *
     * <p>⚠️ Verifying this by watching for "frozen in AWAITING_TYPE" in the log is NOT enough — that
     * only proves the freeze engaged. It has to be checked by MOVING as an authenticated player.
     */
    public static void onPlayerTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (CoffeesAeroAuth.AUTH_MANAGER == null) return;
        CoffeesAeroAuth.AUTH_MANAGER.onTick(player);

        // Confiscation freeze — same mechanism AuthManager already uses for unauthenticated
        // players: pin the position and zero the velocity every tick.
        //
        // 🔴 THIS IS WHY THIS HANDLER MUST STAY ON EntityTickEvent (see the class javadoc above).
        // A teleport inside PlayerTickEvent is reverted by absMoveTo on the next line of
        // ServerGamePacketListenerImpl.tick() while leaving awaitingPositionFromClient armed,
        // which permanently breaks movement. That froze the whole lobby on 2026-09-07. The
        // unauthenticated freeze and this one now BOTH depend on it.
        com.coffeesaerosmp.auth.moderation.Confiscation.Hold held =
            com.coffeesaerosmp.auth.moderation.Confiscation.get(player.getUUID());
        if (held != null) {
            double[] at = HELD_POS.computeIfAbsent(player.getUUID(),
                k -> new double[]{player.getX(), player.getY(), player.getZ()});
            player.teleportTo(at[0], at[1], at[2]);
            player.setDeltaMovement(0, 0, 0);
            player.fallDistance = 0;
            if (player.tickCount % 200 == 0) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§c§lCONFISCATED §7— you cannot act until an admin releases you."
                    + (held.reason() == null || held.reason().isBlank()
                        ? "" : "\n§7Reason: §f" + held.reason())
                    + "\n§7You can still talk in chat."));
            }
        } else {
            HELD_POS.remove(player.getUUID());
        }

        // Lobby container lockdown: the inventory stash only clears the VANILLA inventory
        // (main+armor+offhand), so an equipped Sophisticated Backpack — which lives in an
        // Accessories slot, not a vanilla slot — rides into the lobby and its contents stay
        // reachable. Slam shut any menu that isn't the player's own (empty) inventory: backpacks,
        // the accessories screen, chests, anything opened via openMenu. Done on the tick (not in
        // the open event) to avoid mid-openMenu reentrancy; the menu lives at most one tick.
        // NO items are moved — zero data-loss risk (unlike serializing/clearing the accessories
        // capability, which could eat a backpack on a bad restore). Ops (perm 4) are exempt.
        //
        // 🔴 EXCEPT Easy NPC. Its dialog AND config screens are container menus (EasyNPCMenu), so
        // this slammed them shut on the very next tick for every non-op — which silently made the
        // greeter's dialog mode (aero_greeter_dialog, 1.7.33) IMPOSSIBLE: the one audience it exists
        // for, brand-new players in the lobby, are exactly the people this closed it on. Found
        // 2026-08-19 when an NPC could not be opened in the lobby. An Easy NPC menu holds no player
        // items, so exempting it does not re-open the backpack hole this guard was built for.
        if (player.containerMenu != player.inventoryMenu
                && (lobbyLocked(player)
                    || com.coffeesaerosmp.auth.moderation.Confiscation.isHeld(player.getUUID()))
                && !isEasyNpcMenu(player.containerMenu)) {
            player.closeContainer();
        }
    }

    /**
     * True for any Easy NPC screen (dialog, config, trading).
     *
     * <p>Identified by the menu type's registry namespace, falling back to the class package for
     * menus registered without a type. Deliberately no compile-time dependency on Easy NPC — the
     * mod is a soft dependency everywhere else in this file too.
     */
    private static boolean isEasyNpcMenu(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        if (menu == null) return false;
        try {
            ResourceLocation key = BuiltInRegistries.MENU.getKey(menu.getType());
            if (key != null) return "easy_npc".equals(key.getNamespace());
        } catch (UnsupportedOperationException ignored) {
            // getType() throws for menus built without a registered MenuType — fall through.
        }
        return menu.getClass().getName().startsWith("de.markusbordihn.easynpc.");
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
                && player.level().dimension() == LobbyManager.LOBBY_DIMENSION) {
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

    /**
     * Standalone lobby: nothing leaves this server through a portal.
     *
     * <p>Nether and End portals — and any modded dimension a portal block might reach — are not a
     * route out of a login front door. The only sanctioned exit is the paper, which hands the player
     * to the SMP.
     *
     * <h3>🔑 Filtered by DESTINATION, not by blanket cancellation</h3>
     * This event also fires for our OWN cross-dimension teleport that puts a player into
     * {@code coffees_aero_auth:auth_lobby}. Cancelling every travel event would therefore block the
     * lobby routing itself and strand players in the void overworld — the exact bug 1.8.1 fixed.
     * Allowing the lobby dimension as a destination keeps our routing working while closing every
     * other door. The SMP handoff is unaffected either way: a transfer packet is a reconnect, not a
     * dimension change, so it never reaches this event.
     *
     * <p>Ops (perm 4) are exempt, matching {@link #lobbyLocked} everywhere else — an admin has to be
     * able to go look at something.
     */
    public static void onTravelToDimension(
            net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent event) {
        if (!com.coffeesaerosmp.auth.lobby.LobbyHandoff.isLobbyRole()) return;
        if (event.getDimension() == LobbyManager.LOBBY_DIMENSION) return;   // our own routing
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            event.setCanceled(true);        // mobs/items have no business changing dimension here
            return;
        }
        // 🔑 A portal in the lobby is a SECOND DOOR TO THE SMP, not a dead end.
        //
        // Vanilla travel is always cancelled — the lobby has no Nether and no End, and letting the
        // engine try would drop the player into an ungenerated dimension on a server that exists to
        // hold them for three seconds. Instead an authenticated player gets the same handoff the
        // paper performs, so walking into a portal simply means "take me to the survival server".
        //
        // ⚠️ The transfer is deferred to the next tick with server.execute(). We are inside the
        // engine's portal handling right now; sending a Transfer packet here means the client
        // disconnects mid-dimension-change, and the tick continues operating on a player who has
        // left. One tick later that work is finished and the player object is stable.
        event.setCanceled(true);

        // ⚠️ NO OP EXEMPTION HERE, deliberately — and this was wrong in 1.8.5/1.8.8.
        // Ops used to return early after the cancel, which meant an admin standing in the portal got
        // neither the Nether nor the handoff: the portal simply did nothing, which is indistinguishable
        // from "the feature is broken" and is exactly how it was reported. The portal is a PLAYER exit
        // and the only people who ever test it are ops. An admin who does not want to travel just
        // doesn't stand in the portal.

        if (CoffeesAeroAuth.AUTH_MANAGER == null
                || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                com.coffeesaerosmp.auth.util.TextUtil.PREFIX
                + "§7Finish logging in first — then this will take you to the server."));
            return;
        }

        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            com.coffeesaerosmp.auth.util.TextUtil.PREFIX + "§aTaking you to the survival server…"));
        net.minecraft.server.MinecraftServer server = player.getServer();
        if (server != null) {
            server.execute(() -> com.coffeesaerosmp.auth.lobby.LobbyHandoff.tryTransfer(player));
        }
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
        // shouldBlock covers confiscated AND unauthenticated players; lobbyLocked covers the
        // lobby-grief rule for everyone else. A confiscated player is held out in the world,
        // where lobbyLocked is false — without shouldBlock here they could keep mining.
        // (This also now blocks an unauthenticated player from mining outside the lobby, which
        // they could technically do before — intentional and strictly more correct: a frozen,
        // unauthenticated player has no business editing the world.)
        if (shouldBlock(event.getPlayer()) || lobbyLocked(event.getPlayer())) event.setCanceled(true);
    }

    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        // Same reasoning as onBlockBreak above — shouldBlock closes the confiscated/unauthenticated
        // hole, lobbyLocked keeps the existing lobby-grief rule for everyone else.
        if (event.getEntity() instanceof ServerPlayer p
                && (shouldBlock(p) || lobbyLocked(p))) event.setCanceled(true);
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
                && sp.level().dimension() == LobbyManager.LOBBY_DIMENSION) {
            event.setCanceled(true);
        }
    }

    /** No item pickup in the lobby (belt-and-braces; there should be no ground items anyway). */
    public static void onItemPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event) {
        if (event.getPlayer() instanceof ServerPlayer sp
                && sp.level().dimension() == LobbyManager.LOBBY_DIMENSION
                // Ops exempt (perm 4), matching lobbyLocked() everywhere else. An admin building the
                // lobby needs to be able to pick their own blocks back up — without this, anything
                // dropped or broken while building is unrecoverable and just despawns.
                && !sp.hasPermissions(4)) {
            event.setCanPickup(net.neoforged.neoforge.common.util.TriState.FALSE);
        }
    }

    /** Ban ALL mobs from the lobby dimension — natural spawns, modded critters (crows/hamsters), even
     *  ones saved in the pasted map. Only {@link net.minecraft.world.entity.Mob}s are removed, so
     *  players, armor-stand greeters, item frames, paintings and dropped items are untouched. */
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
                || event.getLevel().dimension() != LobbyManager.LOBBY_DIMENSION
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
                && sp.level().dimension() == LobbyManager.LOBBY_DIMENSION) {
            diedInLobby.add(sp.getUUID());
        }
    }

    /** On respawn after a lobby death: back to the lobby spawn, with the spawn paper (real inventory
     *  stays safely stashed in the DB — it was never in their hands in the lobby). */
    public static void onLobbyRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!diedInLobby.remove(sp.getUUID())) return;
        net.minecraft.server.level.ServerLevel lobby = sp.getServer() != null
            ? sp.getServer().getLevel(LobbyManager.LOBBY_DIMENSION) : null;
        double[] pad = LobbyManager.spawnPad();
        // Same facing as a normal lobby arrival — a respawn that drops you in staring at a different
        // wall than everyone else reads as a bug.
        if (lobby != null) sp.teleportTo(lobby, pad[0], pad[1], pad[2], java.util.Set.of(),
            LobbyManager.spawnYaw(), LobbyManager.spawnPitch());
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

    /**
     * Drops the pinned position the moment a held player disconnects.
     *
     * <p>Without this, a player who is confiscated and then logs out (or is kicked) leaves their
     * {@code HELD_POS} entry behind forever: {@link #onPlayerTick}'s own cleanup branch only runs
     * while the player is still ticking, so it never fires again for someone who is offline. If an
     * admin then releases them while they're offline, nothing ever removes the stale entry — a
     * permanent, if tiny, per-ever-held-player leak. Unconditional removal here is safe either way:
     * a released player has no hold to resume, and a still-held player gets a fresh pin (their
     * position on reconnect) from {@link #onPlayerTick} on their very first tick back.
     */
    public static void onPlayerLoggedOut(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        HELD_POS.remove(event.getEntity().getUUID());
    }

    private static boolean lobbyLocked(net.minecraft.world.entity.player.Player player) {
        return player instanceof ServerPlayer sp
            && sp.level().dimension() == LobbyManager.LOBBY_DIMENSION
            && !sp.hasPermissions(4);
    }

    private static boolean shouldBlock(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof ServerPlayer player)) return false;
        // A confiscated player is blocked by every handler already registered against this
        // predicate — block break/place, right-click block/item, attack, pickup, drop, container
        // open. One line here rather than a second "may not act" implementation that would drift
        // out of sync with this one.
        if (com.coffeesaerosmp.auth.moderation.Confiscation.isHeld(player.getUUID())) return true;
        return CoffeesAeroAuth.AUTH_MANAGER != null
            && !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID());
    }
}
