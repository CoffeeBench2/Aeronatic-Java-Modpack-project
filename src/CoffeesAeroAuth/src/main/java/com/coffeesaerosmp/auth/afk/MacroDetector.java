package com.coffeesaerosmp.auth.afk;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tells a person at the keyboard from something pressing keys for them — per player, pure, no
 * Minecraft types, so it is unit-tested directly.
 *
 * <h2>Two signatures, because macros come in two shapes</h2>
 * <ul>
 *   <li><b>Clockwork timing</b> — auto-clickers, a weight on the mouse (vanilla repeats a held use every
 *       4 ticks), "swap hotbar slot every 30 s" anti-AFK scripts. A human's intervals scatter widely; a
 *       machine's sit within one server tick of each other. Judged per input {@link Kind}, so a clicker on
 *       the attack button never mutes the same player's chat.</li>
 *   <li><b>Repeated values</b> — a mouse jiggler flicks between two angles, a strafe macro bounces between
 *       two blocks. Timing can't see these (the tracker samples rotation and position once a second, so the
 *       intervals are clockwork for everyone), but the set of places visited is tiny.</li>
 * </ul>
 *
 * <h2>What a detection does</h2>
 * Refused input simply <b>does not count as activity</b>: the player goes AFK on the normal timer and gets
 * the normal outcome (lobby). Nothing is punished here. The tally of refused input is what lets the
 * tracker tell staff "went AFK while something kept clicking", which is the signal worth a look.
 *
 * <p>Any input that breaks the pattern counts immediately — an off-beat click, a new angle, a new block.
 */
public final class MacroDetector {

    public enum Kind { CHAT, COMMAND, BREAK, USE, INTERACT, ATTACK, CONTAINER, INVENTORY, SWING, HOTBAR,
                       SNEAK_SPRINT, VEHICLE_INPUT }

    /** Intervals needed before a rhythm can be called clockwork. */
    static final int MIN_INTERVALS = 16;
    /** Intervals kept per kind. */
    static final int TIMING_WINDOW = 24;
    /** Share of intervals that must sit on the beat. */
    static final double ON_BEAT_SHARE = 0.85;
    /** Server-tick jitter on both sides of the beat; widened proportionally for slow rhythms. */
    static final long MIN_TOLERANCE_MS = 110;   // ±1 tick each side of a median that can itself sit a tick off
    static final double TOLERANCE_FRACTION = 0.03;
    /** Two presses of one kind this close together are the same press (e.g. both hands fire RightClickItem). */
    static final long SAME_PRESS_MS = 25;
    /** Faster than a tick cannot be judged from server-side timestamps. */
    static final long MIN_MEDIAN_MS = 40;

    /** Samples kept for the value patterns. */
    static final int VALUE_WINDOW = 20;
    static final int MAX_LOOK_CELLS = 3;
    static final int MAX_MOVE_CELLS = 2;
    static final float LOOK_CELL_DEGREES = 2f;

    private static final class Timing {
        final ArrayDeque<Long> times = new ArrayDeque<>();
        long last = Long.MIN_VALUE;
        boolean lastVerdict = true;
    }

    private final Map<Kind, Timing> timing = new EnumMap<>(Kind.class);
    private final ArrayDeque<Long> looks = new ArrayDeque<>();
    private final ArrayDeque<Long> moves = new ArrayDeque<>();
    private final Map<String, Integer> refused = new java.util.LinkedHashMap<>();
    private final Map<Kind, Long> refusedBeat = new EnumMap<>(Kind.class);
    private int refusedTotal;

    // ── timed events ─────────────────────────────────────────────────────────

    /** @return true if this input counts as a person being present. */
    public boolean event(Kind kind, long nowMs) {
        Timing t = timing.computeIfAbsent(kind, k -> new Timing());
        if (t.last != Long.MIN_VALUE && nowMs - t.last >= 0 && nowMs - t.last < SAME_PRESS_MS) {
            return t.lastVerdict;                         // same press, same answer
        }
        boolean counts = true;
        if (t.last != Long.MIN_VALUE) {
            long interval = nowMs - t.last;
            long[] beat = beat(t);                        // judged on the rhythm BEFORE this press
            if (beat != null && Math.abs(interval - beat[0]) <= beat[1]) counts = false;
            t.times.addLast(interval);
            if (t.times.size() > TIMING_WINDOW) t.times.removeFirst();
            if (!counts) refusedBeat.put(kind, beat[0]);
        }
        t.last = nowMs;
        t.lastVerdict = counts;
        return tally(kind.name(), counts);
    }

    /** True when the established rhythm of this kind is clockwork. */
    public boolean isClockwork(Kind kind) {
        Timing t = timing.get(kind);
        return t != null && beat(t) != null;
    }

    /** {median, tolerance} if the stored intervals are clockwork, else null. */
    private static long[] beat(Timing t) {
        if (t.times.size() < MIN_INTERVALS) return null;
        long[] a = t.times.stream().mapToLong(Long::longValue).toArray();
        long[] s = a.clone();
        Arrays.sort(s);
        long median = s[s.length / 2];
        if (median < MIN_MEDIAN_MS) return null;
        long tol = Math.max(MIN_TOLERANCE_MS, Math.round(median * TOLERANCE_FRACTION));
        int on = 0;
        for (long v : a) if (Math.abs(v - median) <= tol) on++;
        return on >= ON_BEAT_SHARE * a.length ? new long[]{median, tol} : null;
    }

    // ── sampled values ───────────────────────────────────────────────────────

    /** A rotation sample that already passed the tracker's movement threshold. */
    public boolean look(float yaw, float pitch) {
        long y = Math.round(Math.floorMod(Math.round(yaw), 360) / LOOK_CELL_DEGREES);
        long p = Math.round(pitch / LOOK_CELL_DEGREES);
        return tally("LOOK", sample(looks, (y << 16) ^ (p & 0xffff), MAX_LOOK_CELLS));
    }

    /** A position sample that already passed the tracker's movement threshold. */
    public boolean move(double x, double y, double z) {
        long cell = (Math.floorDiv((long) Math.floor(x), 1) & 0x1FFFFF)
                  | ((Math.floorDiv((long) Math.floor(y), 1) & 0xFFF) << 21)
                  | ((Math.floorDiv((long) Math.floor(z), 1) & 0x1FFFFF) << 33);
        return tally("MOVE", sample(moves, cell, MAX_MOVE_CELLS));
    }

    public boolean isJiggling() {
        return jiggling(looks, MAX_LOOK_CELLS) || jiggling(moves, MAX_MOVE_CELLS);
    }

    private static boolean sample(ArrayDeque<Long> window, long cell, int maxCells) {
        boolean pattern = jiggling(window, maxCells);
        boolean inPattern = pattern && window.contains(cell);
        window.addLast(cell);
        if (window.size() > VALUE_WINDOW) window.removeFirst();
        return !inPattern;
    }

    private static boolean jiggling(ArrayDeque<Long> window, int maxCells) {
        if (window.size() < VALUE_WINDOW) return false;
        Set<Long> distinct = new HashSet<>(window);
        return distinct.size() <= maxCells;
    }

    // ── what staff are told ─────────────────────────────────────────────────

    private boolean tally(String what, boolean counts) {
        if (counts) {
            refused.clear();
            refusedBeat.clear();
            refusedTotal = 0;
        } else {
            refused.merge(what, 1, Integer::sum);
            refusedTotal++;
        }
        return counts;
    }

    /** Inputs refused since the last one that counted. */
    public int refusedSinceHuman() {
        return refusedTotal;
    }

    /** e.g. {@code "ATTACK x43 every ~1000 ms, LOOK x20"}. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        refused.forEach((what, n) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(what).append(" x").append(n);
            try {
                Long beat = refusedBeat.get(Kind.valueOf(what));
                if (beat != null) sb.append(" every ~").append(beat).append(" ms");
            } catch (IllegalArgumentException notTimed) { /* LOOK / MOVE */ }
        });
        return sb.toString();
    }
}
