package com.coffeesaerosmp.core.cosmetics;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every player on the server wears, as last told by the server ({@link CosmeticsPayload}). Main-thread
 * writes (payload handlers enqueue), main/render-thread reads. Cleared when leaving a server.
 */
public final class CosmeticsState {

    private CosmeticsState() {}

    /** One player's look; ids are catalog ids or null. */
    public record Look(String trail, String contrail, String wings, String aura, boolean hidden) {
        static final Look NONE = new Look(null, null, null, null, true);
    }

    private static final Map<UUID, Look> LOOKS = new ConcurrentHashMap<>();
    /** Contrails fitted to ships by their owners: ship id -> contrail id. */
    private static final Map<UUID, String> SHIP_FITS = new ConcurrentHashMap<>();

    /** The contrail fitted to this ship, or null. */
    public static String shipFit(UUID ship) { return ship == null ? null : SHIP_FITS.get(ship); }

    public static boolean anyShipFits() { return !SHIP_FITS.isEmpty(); }
    private static volatile Look selfPreview;      // the local player's private try-on, overrides their own look
    private static volatile int flags;
    private static volatile boolean connected;     // the server speaks the cosmetics channel

    /** The look to draw on this player (the local player's try-on wins), or null for nothing. */
    public static Look get(UUID id, boolean local) {
        if (local && selfPreview != null) return selfPreview;
        Look l = LOOKS.get(id);
        return l == null || l.hidden() ? null : l;
    }

    // The server's lag pause (PAUSED) is ignored here on purpose: drawing on this client costs the server nothing.
    public static boolean trailsOn() { return (flags & CosmeticsPayload.TRAILS_ON) != 0; }

    public static boolean contrailsOn() { return (flags & CosmeticsPayload.CONTRAILS_ON) != 0; }

    public static boolean connected() { return connected; }

    /** The local player is trying something on from the shop. */
    public static boolean isTryingOn() { return selfPreview != null; }

    public static int size() { return LOOKS.size(); }

    static void apply(CosmeticsPayload p) {
        connected = true;
        flags = p.flags();
        if (p.full()) LOOKS.clear();
        if (p.ships() != null) {
            SHIP_FITS.clear();
            for (CosmeticsPayload.ShipFit f : p.ships()) if (f.contrail() != null) SHIP_FITS.put(f.ship(), f.contrail());
        }
        for (UUID u : p.removed()) LOOKS.remove(u);
        for (CosmeticsPayload.Entry e : p.entries()) {
            Look l = new Look(e.trail(), e.contrail(), e.wings(), e.aura(), e.hidden());
            if (e.self()) selfPreview = e.hidden() ? null : l;
            else LOOKS.put(e.player(), l);
        }
    }

    static void clear() {
        LOOKS.clear();
        SHIP_FITS.clear();
        selfPreview = null;
        flags = 0;
        connected = false;
    }
}
