package com.coffeesaerosmp.auth.util;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class TextUtil {

    // Coffees Aero SMP house style — amber/gold airship theme. Restyle here to retheme everything.
    public static final String PREFIX         = "§6✈ §eAeroSMP §8» §r";
    // Chat badges: small icon only (no "[Verified]"/"[Guest]" words) — gold for premium, grey for offline.
    public static final String VERIFIED_BADGE = "§6✦ ";
    public static final String OFFLINE_BADGE  = "§7◈ ";

    private TextUtil() {}

    public static Component prefixed(String msg) {
        return Component.literal(PREFIX + msg);
    }

    /**
     * Sends a house-styled line to one player.
     *
     * <p>RtpCommand, ShipNameCommand and TpaCommands each carried a byte-identical private
     * {@code msg(ServerPlayer, String)}, so the house prefix had four independent definitions and a
     * restyle would have silently missed three of them. The command classes keep their local
     * {@code msg} name (the call sites are unchanged) but now delegate here, so {@link #PREFIX}
     * really is the single place that decides how server messages look.
     */
    public static void msg(ServerPlayer player, String text) {
        player.sendSystemMessage(prefixed(text));
    }

    /**
     * Human-readable remaining time for a cooldown.
     *
     * <p>FIXES THE "COOLDOWN IS DOUBLE WHAT I SET IT TO" BUG (2026-08-08). Four call sites each
     * carried {@code (remainingMs + 59_999) / 60_000} — a ceiling to whole minutes. That is fine for
     * a 3-hour cooldown and badly wrong for a short one: with a 1-minute cooldown, ANY remaining
     * time from 1ms to 60s displays as "1m". A player reads "1m", waits a full minute from the
     * moment they read it, and has therefore waited nearly two — the cooldown behaves as if it were
     * double its configured length, even though the underlying gate is correct.
     *
     * <p>So: seconds below a minute, and never round a partial minute up to a whole one silently.
     */
    public static String formatRemaining(long ms) {
        long secs = Math.max(0, (ms + 999) / 1000);      // ceil to the next whole second
        if (secs < 60) return secs + "s";
        long mins = secs / 60, remSecs = secs % 60;
        if (mins < 60) return remSecs == 0 ? mins + "m" : mins + "m " + remSecs + "s";
        long hours = mins / 60, remMins = mins % 60;
        return remMins == 0 ? hours + "h" : hours + "h " + remMins + "m";
    }

    public static Component error(String msg) {
        return Component.literal(PREFIX + "§c" + msg);
    }

    public static Component success(String msg) {
        return Component.literal(PREFIX + "§a" + msg);
    }

    public static Component info(String msg) {
        return Component.literal(PREFIX + "§7" + msg);
    }
}
