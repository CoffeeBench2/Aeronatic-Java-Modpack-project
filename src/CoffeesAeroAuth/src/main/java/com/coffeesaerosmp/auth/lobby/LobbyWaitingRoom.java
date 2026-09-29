package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the lobby tells people while the SMP is away, and what it does when it returns.
 *
 * <p>The lobby stops being a corridor and becomes a waiting room for the length of a restart. Players
 * are not kicked and are never asked to leave and come back — that is the whole point of having a
 * lobby at all.
 *
 * <h2>Everything here hops onto the server thread</h2>
 * {@link SmpLiveness} calls in from its own polling thread. Touching the player list or sending chat
 * from that thread is a data race, so every entry point trampolines through
 * {@code server.execute(...)}. Same rule the cookie mixin follows.
 */
public final class LobbyWaitingRoom {

    private LobbyWaitingRoom() {}

    private static volatile MinecraftServer server;

    public static void attach(MinecraftServer s) { server = s; }
    public static void detach()                  { server = null; }

    /** The SMP went away. Tell everyone standing in the lobby, once. */
    public static void onSmpDown() {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                send(p, "§e⏳ The survival server is restarting.");
                send(p, "§7Stay here — you'll be let straight back in when it's ready. No need to reconnect.");
            }
        });
    }

    /**
     * The SMP is back.
     *
     * <p>Announces, and optionally sends people through automatically. Auto-readmit is OFF by
     * default and staggered when on: a restart can end with a room full of people, and transferring
     * them in one tick means the SMP takes the entire lobby's reconnects simultaneously — the exact
     * thundering herd a graceful restart is supposed to avoid.
     */
    public static void onSmpBack() {
        MinecraftServer s = server;
        if (s == null) return;
        s.execute(() -> {
            java.util.List<ServerPlayer> waiting =
                new java.util.ArrayList<>(s.getPlayerList().getPlayers());
            for (ServerPlayer p : waiting) {
                send(p, "§a✔ The survival server is back.");
            }
            boolean auto;
            try { auto = AuthConfig.AUTO_READMIT.get(); } catch (Exception e) { auto = false; }
            if (!auto) {
                for (ServerPlayer p : waiting) {
                    send(p, "§7Use your §aJoin Survival§7 paper whenever you're ready.");
                }
                return;
            }

            int stagger;
            try { stagger = AuthConfig.READMIT_STAGGER_MS.get(); } catch (Exception e) { stagger = 750; }
            CoffeesAeroAuth.LOGGER.info("[Lobby] Re-admitting {} player(s), {}ms apart.",
                waiting.size(), stagger);

            // Spread the transfers out. Scheduled off-thread, each hop re-entering the server thread,
            // because a transfer is a disconnect and doing 40 of them in one tick is a stall.
            java.util.concurrent.ScheduledExecutorService pacer =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "AeroLobby-Readmit");
                    t.setDaemon(true);
                    return t;
                });
            int i = 0;
            for (ServerPlayer p : waiting) {
                final java.util.UUID id = p.getUUID();
                pacer.schedule(() -> s.execute(() -> {
                    ServerPlayer live = s.getPlayerList().getPlayer(id);
                    if (live == null) return;                       // left in the meantime
                    if (!SmpLiveness.isUp()) return;                // went down again mid-readmit
                    LobbyHandoff.tryTransfer(live);
                }), (long) i * stagger, java.util.concurrent.TimeUnit.MILLISECONDS);
                i++;
            }
            pacer.schedule(pacer::shutdown, (long) (i + 2) * stagger,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        });
    }

    private static void send(ServerPlayer p, String msg) {
        try { p.sendSystemMessage(Component.literal(TextUtil.PREFIX + msg)); } catch (Exception ignored) {}
    }
}
