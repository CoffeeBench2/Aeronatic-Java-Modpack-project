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

    /** One held player. {@code reason} may be null; {@code actor} is the admin who ran the command. */
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
     * Replaces the entire set from storage at boot. Deliberately REPLACES rather than merges — the
     * database is authoritative at startup, and a merge would resurrect a hold that was released
     * while this process was down.
     */
    public static void loadAll(Collection<Hold> holds) {
        HELD.clear();
        for (Hold h : holds) HELD.put(h.uuid(), h);
    }

    /** Test seam, and used by nothing in production. */
    public static void clearAll() {
        HELD.clear();
    }
}
