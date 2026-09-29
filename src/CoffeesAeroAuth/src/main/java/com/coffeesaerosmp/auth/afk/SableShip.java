package com.coffeesaerosmp.auth.afk;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Where a player is relative to the Sable ship they are on, if any.
 *
 * <h2>Why the AFK tracker needs this</h2>
 * Sable re-positions every entity that is on a ship each tick, following the ship's pose. A hovering
 * airship never holds perfectly still — its physics body jitters — so a player standing on one "moves"
 * in world space without touching a key. That is how zzholmes idled 2 h 39 min in a cockpit on 09-29
 * while the same tracker caught him ~25 times elsewhere. In DECK coordinates the drift disappears and
 * walking the deck still shows up, which is exactly the distinction the tracker needs.
 *
 * <h2>Isolation</h2>
 * Every Sable type lives in {@link Impl}, which is only touched after {@code ModList} says Sable is
 * loaded, so a server without Sable never loads a Sable class. Any failure (an API change in a Sable
 * update) disables this for the rest of the run and the tracker falls back to world position — the old
 * behaviour, never a crash on the tick loop.
 */
final class SableShip {

    private SableShip() {}

    /** A position in a ship's own coordinates. */
    record Local(UUID ship, double x, double y, double z) {}

    private static volatile Boolean available;

    /** Deck-relative position, or null when not on a ship (or Sable is absent/broken). */
    static Local local(ServerPlayer player) {
        Boolean ok = available;
        if (ok == null) {
            try {
                ok = net.neoforged.fml.ModList.get().isLoaded("sable");
            } catch (Throwable t) {
                ok = false;
            }
            available = ok;
        }
        if (!ok) return null;
        try {
            return Impl.local(player);
        } catch (Throwable t) {
            available = false;
            CoffeesAeroAuth.LOGGER.warn("[AFK] Sable ship lookup failed; falling back to world position "
                + "for the rest of this run: {}", t.toString());
            return null;
        }
    }

    private static final class Impl {
        static Local local(ServerPlayer player) {
            if (!(player instanceof dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension ext)) {
                return null;
            }
            dev.ryanhcode.sable.sublevel.SubLevel ship = ext.sable$getTrackingSubLevel();
            if (ship == null || ship.isRemoved()) return null;
            Vec3 deck = ship.logicalPose().transformPositionInverse(player.position());
            return new Local(ship.getUniqueId(), deck.x, deck.y, deck.z);
        }
    }
}
