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
