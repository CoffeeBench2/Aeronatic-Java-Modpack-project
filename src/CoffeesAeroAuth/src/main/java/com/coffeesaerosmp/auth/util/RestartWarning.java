package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * The live countdown bar for {@code /authmod warn <minutes>}.
 *
 * <p>A chat line and a title both scroll away or fade — a player who alt-tabs for two minutes comes
 * back with no idea how long is left. A boss bar sits at the top of the screen for the whole
 * countdown, so the answer is always on screen without anyone having to ask in chat.
 *
 * <p>Counts down in real time, not in ticks: the bar is driven from
 * {@link System#currentTimeMillis()} so a lag spike or a long tick cannot desynchronise it from the
 * wall clock the admin actually announced. Ticking is only how often it REDRAWS.
 *
 * <p>Server-side only and stateless across restarts by design — the countdown ends when the server
 * goes down, which is the event it was counting to.
 */
public final class RestartWarning {

    private RestartWarning() {}

    private static ServerBossEvent bar;
    private static long endsAtMs;
    private static long totalMs;
    private static int  tickCounter;

    /** True while a countdown is running. */
    public static boolean isActive() {
        return bar != null;
    }

    /** Seconds left, or 0 if no countdown is running. */
    public static long secondsLeft() {
        if (bar == null) return 0L;
        return Math.max(0L, (endsAtMs - System.currentTimeMillis() + 999L) / 1000L);
    }

    /** Starts (or replaces) the countdown. {@code minutes} must be > 0. */
    public static void start(MinecraftServer server, int minutes) {
        if (server == null || minutes <= 0) return;
        cancel(server);

        totalMs  = minutes * 60_000L;
        endsAtMs = System.currentTimeMillis() + totalMs;

        bar = new ServerBossEvent(label(secondsLeft()),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(1.0f);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) bar.addPlayer(p);
    }

    /** Removes the bar. Safe to call when nothing is running. */
    public static void cancel(MinecraftServer server) {
        if (bar == null) return;
        bar.removeAllPlayers();
        bar.setVisible(false);
        bar = null;
        totalMs = 0L;
        endsAtMs = 0L;
    }

    /** Shows the bar to someone who joined mid-countdown — otherwise they'd be the only one blind to it. */
    public static void onPlayerJoin(ServerPlayer player) {
        if (bar != null && player != null) bar.addPlayer(player);
    }

    /**
     * Redraws the bar. Called every server tick; refreshes 4x/second, which is smooth enough for a
     * per-second readout without sending a packet to everyone 20 times a second for a cosmetic.
     */
    public static void onServerTick(MinecraftServer server) {
        if (bar == null) return;
        if (++tickCounter % 5 != 0) return;

        long left = secondsLeft();
        if (left <= 0) {
            // Hold at "restarting now" rather than vanishing: the bar disappearing on its own would
            // read as "cancelled", at the exact moment the opposite is true.
            bar.setName(Component.literal("§4§lRESTARTING NOW"));
            bar.setProgress(0.0f);
            return;
        }
        bar.setName(label(left));
        bar.setProgress(totalMs <= 0 ? 0f
            : Math.max(0f, Math.min(1f, (endsAtMs - System.currentTimeMillis()) / (float) totalMs)));

        // Colour shifts as it gets close, so urgency is readable at a glance without reading text.
        bar.setColor(left > 60 ? BossEvent.BossBarColor.YELLOW : BossEvent.BossBarColor.RED);
    }

    private static Component label(long secondsLeft) {
        long m = secondsLeft / 60, s = secondsLeft % 60;
        String time = m > 0 ? m + "m " + String.format("%02d", s) + "s" : s + "s";
        return Component.literal("§c§l⚠ SERVER RESTART §r§ein §f" + time);
    }
}
