package com.coffeesaerosmp.auth.tablist;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Animated tab-list header/footer for Coffees Aero SMP — an airship drifts across a sky line above
 * a gold/amber title, with a live pilot count and a rotating tip in the footer. Player names carry
 * their team badge (✈ verified / ◈ guest) automatically. Updated ~twice a second.
 */
public final class TabListManager {

    private TabListManager() {}

    private static int ticks = 0;
    private static int frame = 0;

    /**
     * World tips — shown to everyone who is OUT of the auth lobby. Nothing here may mention the
     * hangar or /spawn: those read as stale nonsense once a player is in the world, which is exactly
     * what the single shared tip pool used to do (2026-08-19).
     *
     * <p>"Lay Create track" is accurate — {@code protect/RailAutoClaim} is registered and live,
     * gated by {@code railAutoclaimEnabled}. If that config is ever turned off, drop the tip.
     */
    private static final String[] TIPS_WORLD = {
        "Lay Create track — it auto-claims to your team",
        "Verified pilots fly with their real skin",
        "Build airships with Create: Aeronautics",
        "§e/sethome§7 and §e/home§7 save you a walk",
        "§e/vote§7 pays spurs and diamonds",
    };

    /** Lobby tips — the player is in the SHARED lobby, so every line points at the way out.
     *  🔑 Do NOT reintroduce "private hangar" here. The lobby is public and shared; the old copy
     *  told players their space was private and grief-proof, which was simply untrue. */
    private static final String[] TIPS_LOBBY = {
        "Type §a/spawn§7 to enter the world",
        "Right-click the greeter to fly out",
        "Your inventory is safe — it comes back on §a/spawn",
        "§e/skin§7 sets how you look before you fly",
    };

    /** Call every server tick; throttles internally to ~2 updates/sec. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++ticks % 10 != 0) return;
        frame++;
        var players = server.getPlayerList().getPlayers();

        // Push the live count to the bot presence every cycle (coalesced + rate-limited inside the
        // gateway, so this is nearly free). Event-driven pushes missed offline-auth joins and any
        // change while the gateway was reconnecting — tick-driven self-heals all drift.
        if (com.coffeesaerosmp.auth.CoffeesAeroAuth.DISCORD_BRIDGE != null)
            com.coffeesaerosmp.auth.CoffeesAeroAuth.DISCORD_BRIDGE.updatePlayerCount(players.size());

        if (players.isEmpty()) return;
        // Two styles: the lobby is an auth waiting room and needs "here is how you leave", the world
        // needs gameplay. Both packets are built once per cycle and picked per player — the packet
        // was already being sent per-connection, so this costs one extra Component build, not one
        // per player. Row counts are identical in both so the player list doesn't jump on /spawn.
        int total = players.size();
        int visible = total - com.coffeesaerosmp.auth.display.HiddenOps.hiddenCount(players);
        ClientboundTabListPacket worldOp    = new ClientboundTabListPacket(header(false), footer(server, false, total));
        ClientboundTabListPacket worldPlain = new ClientboundTabListPacket(header(false), footer(server, false, visible));
        ClientboundTabListPacket lobbyOp    = new ClientboundTabListPacket(header(true),  footer(server, true,  total));
        ClientboundTabListPacket lobbyPlain = new ClientboundTabListPacket(header(true),  footer(server, true,  visible));
        for (ServerPlayer p : players) {
            boolean op = p.hasPermissions(2);
            p.connection.send(inLobby(p) ? (op ? lobbyOp : lobbyPlain) : (op ? worldOp : worldPlain));
        }
        // Refresh RGB names AND staff badges from config every ~5s so edits apply live.
        if (frame % 10 == 0) {
            com.coffeesaerosmp.auth.util.RainbowText.setEnabledNames(
                com.coffeesaerosmp.auth.config.AuthConfig.DISPLAY_RGB_NAMES.get());
            com.coffeesaerosmp.auth.display.DisplayAdapter.refreshStaff();
        }
        sendTabNames(server, players);
    }

    /**
     * One per-viewer TAB name send. Replaces the two methods that used to race each other on
     * UPDATE_DISPLAY_NAME every ~500ms — neither carried the clan tag, so the scoreboard team
     * prefix that DID carry it was overwritten twice a second. Ops get the real-name reveal;
     * everyone else does not, which is why this must be built per viewer.
     */
    private static void sendTabNames(MinecraftServer server, java.util.List<ServerPlayer> players) {
        java.util.List<net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry> plain =
            new java.util.ArrayList<>();
        java.util.List<net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry> opView =
            new java.util.ArrayList<>();

        for (ServerPlayer p : players) {
            var parts = com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(p);

            // NameStyles keys its lookups (owner seed, legacy rainbow config list) off the RAW
            // account username and renders onto the RAW display text — never parts.name(), which
            // is that same text with a "§f"/"§7" colour code already baked onto the front (see
            // DisplayAdapter#partsFor). Feeding the coloured string in would (a) break the
            // username-equality / config-list lookups outright, since neither compares against a
            // string starting with a section sign, and (b) re-embed that colour code as a literal
            // inside the rendered Component — the client applies embedded §-codes over whatever
            // Style NameStyles.render() set, silently cancelling a custom /namecolor color from
            // partway through the name onward. So the real profile is read here, exactly like the
            // deleted sendStyledNames used to, purely to get the two PLAIN strings NameStyles needs.
            var profile = com.coffeesaerosmp.auth.CoffeesAeroAuth.PROFILE_STORE != null
                ? com.coffeesaerosmp.auth.CoffeesAeroAuth.PROFILE_STORE.get(p.getUUID()) : null;
            String rawUsername = profile != null && profile.username != null
                ? profile.username : p.getGameProfile().getName();
            String rawDisplay = profile != null && profile.displayName != null
                ? profile.displayName : rawUsername;

            // Animated styles (rainbow, §k) are per-character and cannot live in a plain string,
            // so NameStyles paints the NAME and PlayerDisplay supplies the surrounding decoration.
            //
            // Use segments(), NEVER String.replace to subtract the name. The display name is
            // routinely a SUBSTRING of the account name — "Coffee" inside "MrCoffeeBench" — so
            // replace() corrupts the op reveal to "(MrBench)", and a player whose name matches
            // their own clan tag guts the tag entirely. Verified, not theoretical.
            var segPlain = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                parts, com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.TAB, false);
            var segOp = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                parts, com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.TAB, true);

            Component styled = com.coffeesaerosmp.auth.util.NameStyles.nameComponent(
                p.getUUID(), rawUsername, rawDisplay);

            Component plainName = Component.literal(segPlain.prefix())
                .append(styled != null ? styled : Component.literal(segPlain.name()))
                .append(Component.literal(segPlain.suffix()));
            Component opName = Component.literal(segOp.prefix())
                .append(styled != null ? styled : Component.literal(segOp.name()))
                .append(Component.literal(segOp.suffix()));

            if (!com.coffeesaerosmp.auth.display.HiddenOps.isHidden(p.getUUID())) {
                plain.add(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                    p.getUUID(), null, true, p.connection.latency(), p.gameMode.getGameModeForPlayer(), plainName, null));
            }
            opView.add(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                p.getUUID(), null, true, p.connection.latency(), p.gameMode.getGameModeForPlayer(), opName, null));
        }
        if (opView.isEmpty()) return;

        var pkt = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
            java.util.EnumSet.of(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
            java.util.List.of());
        ((com.coffeesaerosmp.auth.mixin.PlayerInfoPacketAccessor) (Object) pkt).aeroauth$setEntries(plain);
        var opPkt = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
            java.util.EnumSet.of(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
            java.util.List.of());
        ((com.coffeesaerosmp.auth.mixin.PlayerInfoPacketAccessor) (Object) opPkt).aeroauth$setEntries(opView);

        for (ServerPlayer viewer : players)
            viewer.connection.send(viewer.hasPermissions(2) ? opPkt : pkt);

        syncHiddenOps(players);
    }

    /** UUIDs we have removed from non-op tab lists, so we know who to put back. */
    private static final java.util.Set<java.util.UUID> REMOVED = new java.util.HashSet<>();

    /**
     * Actually hide hidden ops from the tab list.
     *
     * <p>🔑 Leaving a player out of an {@code UPDATE_DISPLAY_NAME} packet does NOT hide them — that
     * action only edits an entry that already exists. Vanilla added every player to every client's
     * list with {@code ADD_PLAYER} when they joined, and the entry survives until something sends
     * {@link net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket}. Omission alone
     * just means their row keeps its vanilla styling, which is exactly what it looked like.</p>
     *
     * <p>Re-adding needs the full initialising packet, not an update — the entry no longer exists on
     * the client, so there is nothing for an update to edit.</p>
     */
    private static void syncHiddenOps(java.util.List<ServerPlayer> players) {
        java.util.List<java.util.UUID> toRemove = new java.util.ArrayList<>();
        java.util.List<ServerPlayer>   toRestore = new java.util.ArrayList<>();

        for (ServerPlayer p : players) {
            boolean hidden = com.coffeesaerosmp.auth.display.HiddenOps.isHidden(p.getUUID());
            if (hidden) toRemove.add(p.getUUID());
            else if (REMOVED.contains(p.getUUID())) toRestore.add(p);
        }
        // Anyone we had removed who is no longer online needs forgetting, or REMOVED grows forever.
        REMOVED.removeIf(id -> players.stream().noneMatch(p -> p.getUUID().equals(id)));

        if (!toRemove.isEmpty()) {
            var removePkt = new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(toRemove);
            for (ServerPlayer viewer : players) {
                if (!viewer.hasPermissions(2)) viewer.connection.send(removePkt);
            }
            REMOVED.addAll(toRemove);
        }
        if (!toRestore.isEmpty()) {
            var addPkt = net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
                .createPlayerInitializing(toRestore);
            for (ServerPlayer viewer : players) {
                if (!viewer.hasPermissions(2)) viewer.connection.send(addPkt);
            }
            toRestore.forEach(p -> REMOVED.remove(p.getUUID()));
        }
    }

    /** True while the player is in the auth lobby dimension. */
    private static boolean inLobby(ServerPlayer player) {
        return player.level().dimension()
            == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION;
    }

    private static Component header(boolean lobby) {
        int span = 26;
        int pos = frame % (span + 4);                  // airship drifts across, then re-enters
        StringBuilder sky = new StringBuilder("§3");
        for (int i = 0; i < span; i++) {
            if (i == pos)                  sky.append("§6✈§3");
            else if ((i + frame) % 9 == 0) sky.append("§f☁§3");
            else                           sky.append(' ');
        }
        // Precedence: testing phase > lobby > normal. Each REPLACES the tagline rather than adding a
        // line — the header is a fixed shape and an extra row pushes the player list around on every
        // toggle, and the lobby/world swap happens mid-session on /spawn.
        String tagline;
        if (com.coffeesaerosmp.auth.util.TestingMode.isActive()) {
            tagline = "\n§e§l⚙ TESTING PHASE §8— §7restarts expected";
        } else if (lobby) {
            tagline = "\n§b⌂ §7§othe hangar §b⌂";
        } else {
            tagline = "\n§5✦ §7§oskies brewed daily §5✦";
        }
        return Component.literal(
              "\n" + sky
            + "\n§6§lCOFFEE'S CREATE §e§lAERONAUTICS §8SMP"
            + tagline
            + "\n ");
    }

    private static Component footer(MinecraftServer server, boolean lobby, int count) {
        String[] tips = lobby ? TIPS_LOBBY : TIPS_WORLD;
        String tip = tips[(frame / 6) % tips.length];  // rotate roughly every 3s
        // In the lobby a pilot count is noise — the player can't see anyone and isn't in the world
        // yet. Lead with the exit instead.
        String line = lobby
            ? "§a✦ §e/spawn §6to enter the world §8• §7" + tip
            : "§6⚙ §e" + count + " §6pilot"
              + (count == 1 ? "" : "s") + " aloft §8• §7" + tip;
        return Component.literal(
              "\n§8§m                                          §r"
            + "\n" + line
            + "\n ");
    }
}
