package com.coffeesaerosmp.auth.display;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.store.StoreState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom join / leave lines (a cosmetic): a SECOND line under the normal join or leave message, e.g.
 * "⚙ Bob » has come aboard". Only the APPROVED text is ever read ({@code StoreState.selectionFor}, which reads the
 * live column, never {@code pending_*}), so moderation holds by construction.
 *
 * <p>Guards: SMP only (the lobby runs the same jar and would say it twice), switchable, never for a vanished op,
 * once per session, and not again within 60 s (a relog storm must not spam chat).
 */
public final class JoinAnnouncer {

    private static final long RELOG_QUIET_MS = 60_000L;
    private static final Set<UUID> ANNOUNCED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_JOIN_LINE = new ConcurrentHashMap<>();

    /** Lines sent since boot — read by the GameTest. */
    static volatile int joinLines, leaveLines;

    private JoinAnnouncer() {}

    private static boolean enabled() {
        try { return AuthConfig.JOIN_MESSAGES_ENABLED.get() && !LobbyHandoff.isLobbyRole(); }
        catch (Throwable t) { return false; }
    }

    private static String name(ServerPlayer p) {
        var seg = PlayerDisplay.segments(DisplayAdapter.partsFor(p), PlayerDisplay.Surface.JOIN, false);
        return seg.prefix() + seg.name();
    }

    /** Called once the player's store state has loaded (AuthManager), on the server thread. */
    public static void announceJoin(ServerPlayer p) {
        try {
            if (p == null || !enabled() || HiddenOps.isHidden(p.getUUID())) return;
            UUID id = p.getUUID();
            String msg = StoreState.selectionFor(id).joinMessage();
            if (msg == null || msg.isBlank()) return;
            if (!ANNOUNCED.add(id)) return;                                   // once per session
            long now = System.currentTimeMillis();
            Long last = LAST_JOIN_LINE.get(id);
            if (last != null && now - last < RELOG_QUIET_MS) return;          // relog storm
            LAST_JOIN_LINE.put(id, now);
            p.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal(name(p) + " §7» §f" + msg.replace('§', ' ')), false);
            joinLines++;
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[JoinAnnouncer] join line skipped: {}", t.toString());
        }
    }

    /** Called from the leave-line mixin, right after the normal leave message. */
    public static void announceLeave(PlayerList list, ServerPlayer p) {
        try {
            if (p == null || list == null || !enabled() || HiddenOps.isHidden(p.getUUID())) return;
            UUID id = p.getUUID();
            ANNOUNCED.remove(id);
            if (CoffeesAeroAuth.AUTH_MANAGER == null || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(id)) return;
            String msg = StoreState.selectionFor(id).leaveMessage();
            if (msg == null || msg.isBlank()) return;
            list.broadcastSystemMessage(Component.literal(name(p) + " §7» §f" + msg.replace('§', ' ')), false);
            leaveLines++;
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[JoinAnnouncer] leave line skipped: {}", t.toString());
        }
    }

    /** Session over: the next join may announce again (subject to the 60 s quiet period). */
    public static void forget(UUID id) {
        if (id != null) ANNOUNCED.remove(id);
    }
}
