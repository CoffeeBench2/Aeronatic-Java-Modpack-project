package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.network.CosmeticsPayload;
import com.coffeesaerosmp.auth.network.CosmeticsPayload.Entry;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import com.coffeesaerosmp.auth.store.StoreState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tells every AeroCore client who wears what (1.14.0). The clients draw all of it themselves (denser particles,
 * 3D wings and auras, attached to the player as each client sees them), so the server sends one small message
 * when something changes instead of a particle packet every few ticks per player.
 *
 * <ul>
 *   <li>1 Hz (or the next tick after a selection changes): the state of every online player is rebuilt and
 *       diffed against what was last sent; only the difference goes out, to every client with the channel.</li>
 *   <li>A joining client gets the whole table once.</li>
 *   <li>A shop try-on is a private {@code self} entry sent to the one player trying it on.</li>
 * </ul>
 * Players whose client has the channel get NO server-drawn trail/contrail packets ({@link CosmeticEngine} skips
 * them), so nothing is drawn twice.
 */
public final class CosmeticSync {

    private CosmeticSync() {}

    private static Map<UUID, Entry> sent = new HashMap<>();
    /** The private try-on entry last sent to each player (compared whole, so a look change mid-try-on resends). */
    private static final Map<UUID, Entry> SELF_SENT = new HashMap<>();
    private static int sentFlags = -1;
    private static List<CosmeticsPayload.ShipFit> sentShips = List.of();
    private static volatile boolean dirty;
    private static long tick;

    public static boolean supported(ServerPlayer p) {
        return p != null && p.connection != null && p.connection.hasChannel(CosmeticsPayload.TYPE);
    }

    /** Something about someone's look changed: sync on the next tick instead of waiting up to a second. */
    public static void markDirty() { dirty = true; }

    public static void onLogin(ServerPlayer p) {
        dirty = true;
        if (!active() || !supported(p)) return;
        var server = p.getServer();
        if (server == null) return;
        Map<UUID, Entry> now = snapshot(server);
        send(p, new CosmeticsPayload(true, flags(), new ArrayList<>(now.values()), List.of(), ships(server)));
    }

    public static void onLogout(UUID id) {
        SELF_SENT.remove(id);
        dirty = true;
    }

    /** Every server tick; cheap unless due. */
    public static void tick(MinecraftServer server) {
        tick++;
        if (!dirty && tick % 20 != 0) return;
        dirty = false;
        try {
            sync(server);
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[CosmeticSync] {}", t.toString());
        }
    }

    private static void sync(MinecraftServer server) {
        if (!active()) {
            if (!sent.isEmpty() || sentFlags != 0) {             // switched off: tell clients to draw nothing
                broadcast(server, new CosmeticsPayload(true, 0, List.of(), List.of(), List.of()));
                sent = new HashMap<>();
                sentFlags = 0;
                sentShips = List.of();
            }
            return;
        }
        Map<UUID, Entry> now = snapshot(server);
        int flags = flags();
        List<CosmeticsPayload.ShipFit> ships = ships(server);
        boolean shipsChanged = !ships.equals(sentShips);
        if (flags != sentFlags) {
            broadcast(server, new CosmeticsPayload(true, flags, new ArrayList<>(now.values()), List.of(), ships));
        } else {
            MapDiff<UUID, Entry> d = MapDiff.of(sent, now);
            if (!d.isEmpty() || shipsChanged) {
                broadcast(server, new CosmeticsPayload(false, flags, d.upserts(), d.removed(), shipsChanged ? ships : null));
            }
        }
        sent = now;
        sentFlags = flags;
        sentShips = ships;
        syncTryOns(server);
    }

    /** Private try-on previews: the trying player sees the cosmetic on themselves, nobody else does. */
    private static void syncTryOns(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!supported(p)) continue;
            String id = TryOn.activeId(p.getUUID());
            Entry was = SELF_SENT.get(p.getUUID());
            Entry e;
            if (id == null) {
                if (was == null) continue;
                e = new Entry(p.getUUID(), null, null, null, null, true, true);        // self + hidden = clear
                SELF_SENT.remove(p.getUUID());
            } else {
                CosmeticCatalog.Type t = CosmeticCatalog.Type.of(id);
                if (t == null) continue;
                // the try-on on top of what they wear NOW: changing an aura mid-try-on must show straight away
                Selection s = StoreState.selectionFor(p.getUUID()).with(t, id);
                e = new Entry(p.getUUID(), s.trail(), contrail(s), s.wings(), s.aura(), false, true);
                if (e.equals(was)) continue;
                SELF_SENT.put(p.getUUID(), e);
            }
            send(p, new CosmeticsPayload(false, flags(), List.of(e), List.of()));
        }
    }

    /**
     * Every fitted ship contrail clients should draw. A fit whose owner is online but may no longer wear that
     * contrail (rank lapsed) is left out until they can again; an offline owner's fit stays as last validated.
     */
    static List<CosmeticsPayload.ShipFit> ships(MinecraftServer server) {
        List<CosmeticsPayload.ShipFit> out = new ArrayList<>();
        if (!CosmeticCatalog.CONTRAILS_ON) return out;          // parked: fits stay on disk, nothing is drawn
        for (var e : ShipContrails.all().entrySet()) {
            var fit = e.getValue();
            if (CosmeticCatalog.get(fit.contrail()).isEmpty()) continue;
            if (server.getPlayerList().getPlayer(fit.owner()) != null) {
                var snap = StoreState.get(fit.owner());
                if (!com.coffeesaerosmp.auth.store.Entitlements.mayUse(fit.contrail(), snap.effectiveRank(), snap.owned(),
                        com.coffeesaerosmp.auth.store.Previewers.isPreview(fit.owner()))) continue;
            }
            out.add(new CosmeticsPayload.ShipFit(e.getKey(), fit.contrail()));
        }
        out.sort(java.util.Comparator.comparing(CosmeticsPayload.ShipFit::ship));
        return out;
    }

    /** The public look of everyone online who wears anything. */
    static Map<UUID, Entry> snapshot(MinecraftServer server) {
        Map<UUID, Entry> out = new LinkedHashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Selection s = StoreState.selectionFor(p.getUUID());
            if (s.trail() == null && contrail(s) == null && s.wings() == null && s.aura() == null) continue;
            out.put(p.getUUID(), new Entry(p.getUUID(), s.trail(), contrail(s), s.wings(), s.aura(), hidden(p), false));
        }
        return out;
    }

    /** The contrail to draw: none while contrails are parked (the player's choice is kept for when they return). */
    private static String contrail(Selection s) {
        return CosmeticCatalog.CONTRAILS_ON ? s.contrail() : null;
    }

    /** Never draw anything on players the server hides: unauthenticated, vanished staff, PvP ghosts, spectators. */
    private static boolean hidden(ServerPlayer p) {
        if (p.isSpectator()) return true;
        if (CoffeesAeroAuth.AUTH_MANAGER == null || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(p.getUUID())) return true;
        if (com.coffeesaerosmp.auth.display.HiddenOps.isHidden(p.getUUID())) return true;
        return com.coffeesaerosmp.auth.pvp.GhostState.isGhost(p.getUUID());
    }

    private static boolean active() {
        try {
            return AuthConfig.COSMETICS_ENABLED.get() && !LobbyHandoff.isLobbyRole();
        } catch (Throwable t) {
            return false;
        }
    }

    static int flags() {
        int f = 0;
        try { if (AuthConfig.TRAILS_ENABLED.get()) f |= CosmeticsPayload.TRAILS_ON; } catch (Throwable ignored) { }
        try { if (AuthConfig.CONTRAILS_ENABLED.get()) f |= CosmeticsPayload.CONTRAILS_ON; } catch (Throwable ignored) { }
        if (CosmeticEngine.paused()) f |= CosmeticsPayload.PAUSED;
        return f;
    }

    private static void broadcast(MinecraftServer server, CosmeticsPayload pl) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (supported(p)) send(p, pl);
    }

    private static void send(ServerPlayer p, CosmeticsPayload pl) {
        p.connection.send(pl);
        packets++;
    }

    /** Payloads sent since boot (GameTests, /aerostore cosmetics status). */
    static volatile long packets;

}
