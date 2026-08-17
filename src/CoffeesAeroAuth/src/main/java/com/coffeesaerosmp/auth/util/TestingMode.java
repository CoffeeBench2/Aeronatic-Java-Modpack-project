package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * "The server is in a testing phase" banner — an announced, persistent server state.
 *
 * <p>Toggled with {@code /authmod testing on|off}. While it is on, the state shows in three places
 * that a player cannot miss by looking away: the tab-list header, a line on join, and a broadcast
 * at the moment it is switched.
 *
 * <p><b>It persists to disk.</b> A testing phase outlives restarts — that is usually the whole
 * reason it is on — and a flag that silently clears on reboot is worse than no flag, because the
 * admin still believes players have been told. Written off-thread through {@link AsyncIo}, same as
 * the daily-reward store.
 *
 * <p>This is a NOTICE, not an enforcement switch. It changes nothing about permissions, claims or
 * gameplay; it only tells people what is going on. Anything that gates behaviour should read its
 * own config rather than piggy-backing on this.
 */
public final class TestingMode {

    private TestingMode() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** On-disk shape. A class rather than a bare boolean so a reason and start time fit alongside. */
    private static final class State {
        boolean active;
        String  note;
        long    since;
    }

    private static Path file;
    private static volatile boolean active;
    private static volatile String  note = "";
    private static volatile long    since;

    /** Load the persisted flag. Called once at server start, before players can join. */
    public static void initialize(Path dataDir) {
        file = dataDir == null ? null : dataDir.resolve("testing_mode.json");
        if (file == null || !Files.exists(file)) return;
        try {
            State s = GSON.fromJson(Files.readString(file), State.class);
            if (s != null) {
                active = s.active;
                note   = s.note == null ? "" : s.note;
                since  = s.since;
            }
            if (active) {
                CoffeesAeroAuth.LOGGER.info("[Testing] Testing mode is ON (persisted{}).",
                    note.isBlank() ? "" : ": " + note);
            }
        } catch (Exception ex) {
            CoffeesAeroAuth.LOGGER.warn("[Testing] load failed: {}", ex.getMessage());
        }
    }

    public static boolean isActive() { return active; }

    /** Free-text reason shown next to the banner, or empty. */
    public static String note()      { return note; }

    /** Epoch millis the current phase started; 0 if never set. */
    public static long since()       { return since; }

    /** Returns true if this call actually changed the state. */
    public static boolean set(boolean on, String reason) {
        boolean changed = (active != on);
        active = on;
        note   = (reason == null) ? "" : reason.trim();
        if (on && changed) since = System.currentTimeMillis();
        if (!on) since = 0L;
        save();
        return changed;
    }

    /** The one-line notice shown on join and in {@code /authmod testing status}. */
    public static String banner() {
        return TextUtil.PREFIX + "§e§l⚙ TESTING PHASE §r§7— expect restarts, rollbacks and odd behaviour."
             + (note.isBlank() ? "" : " §f" + note);
    }

    private static void save() {
        if (file == null) return;
        State s = new State();
        s.active = active;
        s.note   = note;
        s.since  = since;
        AsyncIo.submit(() -> {
            try {
                Files.writeString(file, GSON.toJson(s));
            } catch (Exception ex) {
                CoffeesAeroAuth.LOGGER.warn("[Testing] save failed: {}", ex.getMessage());
            }
        });
    }
}
