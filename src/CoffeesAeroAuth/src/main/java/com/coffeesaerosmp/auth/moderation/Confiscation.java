package com.coffeesaerosmp.auth.moderation;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is currently confiscated ("held"): frozen, unable to interact, unable to run commands, and
 * required to talk to an admin before they get their game back.
 *
 * <h2>Pure by design</h2>
 * This class imports NOTHING from Minecraft and nothing from {@code AsyncIo}. {@link #isHeld} runs
 * every tick for every player, so it must be a plain map read — and keeping persistence out of here
 * is also what makes this unit-testable without a running game (see the note in {@code build.gradle}
 * about the pure display core).
 *
 * <p>The in-memory map is the copy that gets READ. The {@code confiscations} table is the copy that
 * SURVIVES; it is written through {@code ConfiscationStore} and reloaded at boot with
 * {@link #loadAll}. A hold therefore outlives both a relog and a restart, which is the whole point —
 * release is manual and only manual.
 */
public final class Confiscation {

    /**
     * One held player.
     *
     * @param uuid         the held player
     * @param reason       why, as typed by the admin; may be null for a system-initiated hold
     * @param actor        the admin who placed the hold, or "system"
     * @param startedEpoch when the hold was placed, in epoch MILLISECONDS (System.currentTimeMillis)
     */
    public record Hold(UUID uuid, String reason, String actor, long startedEpoch) {}

    private static final Map<UUID, Hold> HELD = new ConcurrentHashMap<>();

    private Confiscation() {}

    /** The per-tick predicate. Cheap, null-safe, never throws — it runs inside the tick loop. */
    public static boolean isHeld(UUID uuid) {
        return uuid != null && HELD.containsKey(uuid);
    }

    /** The hold for this player, or null if they are not held. */
    public static Hold get(UUID uuid) {
        return uuid == null ? null : HELD.get(uuid);
    }

    /** Every current hold. Snapshot — safe to iterate while the map changes. */
    public static Collection<Hold> all() {
        return List.copyOf(HELD.values());
    }

    /** Places (or replaces) a hold in memory. Persistence is the caller's job. */
    public static void hold(Hold hold) {
        HELD.put(hold.uuid(), hold);
    }

    /** Lifts a hold in memory. Returns the hold that was lifted, or null if none was in force. */
    public static Hold release(UUID uuid) {
        return uuid == null ? null : HELD.remove(uuid);
    }

    /**
     * Replaces the entire set from storage. Deliberately REPLACES rather than merges — the database
     * is authoritative at startup, and a merge would resurrect a hold that was released while this
     * process was down.
     *
     * <p>🔴 <b>BOOT ONLY. Must not be called once players can tick.</b> The clear-then-refill is NOT
     * atomic: every {@link #isHeld} call landing between the clear and that uuid's re-insert answers
     * "not held", for as long as the refill loop runs. At boot nothing observes that window. From a
     * hot "reload holds" command it would briefly UNFREEZE every held player — which is exactly the
     * failure this whole feature exists to prevent. If a hot reload is ever wanted, build the new map
     * off to the side and swap a volatile reference instead of mutating this one in place.
     */
    public static void loadAll(Collection<Hold> holds) {
        HELD.clear();
        for (Hold h : holds) HELD.put(h.uuid(), h);
    }

    /** Test seam — package-private on purpose, so nothing outside this package can unfreeze everyone. */
    static void clearAll() {
        HELD.clear();
    }
}
