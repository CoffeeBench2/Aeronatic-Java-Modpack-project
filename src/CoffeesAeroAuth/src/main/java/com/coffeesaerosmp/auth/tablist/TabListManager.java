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

    private static final String[] TIPS = {
        "Lay Create track — it auto-claims to your team",
        "Type §e/spawn§7 to leave your hangar",
        "Verified pilots fly with their real skin",
        "Build airships with Create: Aeronautics",
        "Your private hangar is grief-proof",
    };

    /** Call every server tick; throttles internally to ~2 updates/sec. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++ticks % 10 != 0) return;
        frame++;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        ClientboundTabListPacket packet = new ClientboundTabListPacket(header(), footer(server));
        for (ServerPlayer p : players) p.connection.send(packet);
        sendAdminNameOverlay(server, players);
    }

    /**
     * Admin-only tab overlay: ops see "DisplayName §8(RealName)" for every masked player
     * (regular players just see the display name from the NameMask profile swap). Sent on the same
     * 2/s cadence as the header — UPDATE_DISPLAY_NAME entries are idempotent.
     */
    private static void sendAdminNameOverlay(MinecraftServer server,
                                             java.util.List<ServerPlayer> players) {
        if (com.coffeesaerosmp.auth.CoffeesAeroAuth.AUTH_MANAGER == null) return;
        var store = com.coffeesaerosmp.auth.CoffeesAeroAuth.AUTH_MANAGER.getStore();

        java.util.List<net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry> entries =
            new java.util.ArrayList<>();
        for (ServerPlayer p : players) {
            var profile = store.get(p.getUUID());
            if (profile == null || profile.username == null) continue;
            String display = p.getGameProfile().getName();
            if (profile.username.equals(display)) continue;   // not masked — nothing to reveal
            boolean premium = profile.getAccountType()
                == com.coffeesaerosmp.auth.db.PlayerProfile.AccountType.PREMIUM;
            Component c = Component.literal(
                (premium ? "§6✈ §f" : "§8◈ §7") + display + " §8(" + profile.username + ")");
            entries.add(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(
                p.getUUID(), null, true, p.connection.latency(), p.gameMode.getGameModeForPlayer(), c, null));
        }
        if (entries.isEmpty()) return;

        var pkt = new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket(
            java.util.EnumSet.of(net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
            java.util.List.of());
        ((com.coffeesaerosmp.auth.mixin.PlayerInfoPacketAccessor) (Object) pkt).aeroauth$setEntries(entries);
        for (ServerPlayer viewer : players) {
            if (viewer.hasPermissions(2)) viewer.connection.send(pkt);
        }
    }

    private static Component header() {
        int span = 26;
        int pos = frame % (span + 4);                  // airship drifts across, then re-enters
        StringBuilder sky = new StringBuilder("§3");
        for (int i = 0; i < span; i++) {
            if (i == pos)                  sky.append("§6✈§3");
            else if ((i + frame) % 9 == 0) sky.append("§f☁§3");
            else                           sky.append(' ');
        }
        return Component.literal(
              "\n" + sky
            + "\n§6§lCOFFEE'S CREATE §e§lAERONAUTICS §8SMP"
            + "\n§5✦ §7§oskies brewed daily §5✦"
            + "\n ");
    }

    private static Component footer(MinecraftServer server) {
        int n = server.getPlayerList().getPlayerCount();
        String tip = TIPS[(frame / 6) % TIPS.length];  // rotate roughly every 3s
        return Component.literal(
              "\n§8§m                                          §r"
            + "\n§6⚙ §e" + n + " §6pilot" + (n == 1 ? "" : "s") + " aloft §8• §7" + tip
            + "\n ");
    }
}
