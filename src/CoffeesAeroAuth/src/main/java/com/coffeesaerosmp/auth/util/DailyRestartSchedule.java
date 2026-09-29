package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Arms {@link RestartWarning} automatically before the host's DAILY scheduled restart.
 *
 * <h2>Why this exists</h2>
 * The Lagless panel restarts this server every day at 10:30 Asia/Colombo and says nothing to anyone.
 * A player mid-flight in a Create airship, mid-trade, or halfway through a build simply drops. The
 * restart itself is not ours to move — it lives in the panel's scheduled tasks — so the only thing
 * we control is whether players see it coming.
 *
 * <p>This does <b>not</b> restart anything. It only counts down to a moment someone else decided.
 * If the panel's schedule is ever changed, THIS CONFIG MUST BE CHANGED TOO — nothing here can detect
 * the panel's timetable, so a silent drift between the two would produce a countdown that ends at
 * the wrong moment, which is worse than no countdown at all.
 *
 * <h2>Fires on CROSSING, never on equality</h2>
 * 🔑 The same rule {@code ItemClearer} documents: this server's tick loop skips whole seconds under
 * load, so a check for "is it exactly 10:20" would silently miss the warning on precisely the days
 * the server is busiest. The test here is "are we at or past the arm moment, and have we not already
 * armed for this particular restart" — which cannot be skipped by a stalled tick, only delayed.
 *
 * <h2>Late boots still warn, correctly</h2>
 * Boot at 10:26 for a 10:30 restart and the countdown arms for the real 4 minutes remaining, not a
 * fresh 10. That is why {@link RestartWarning#startSeconds} exists.
 */
public final class DailyRestartSchedule {

    private DailyRestartSchedule() {}

    /** The restart moment we have already armed for, so each one arms exactly once. */
    private static ZonedDateTime armedFor;
    /** Set once the sub-minute chat ping has been sent for {@link #armedFor}. */
    private static boolean finalPingSent;
    /** True once this restart's evacuation has run, so it fires exactly once per restart. */
    private static boolean evacuated;
    /** Previous tick's countdown state, so a NEW countdown of any origin re-arms {@link #evacuated}. */
    private static boolean wasCountingDown;

    private static int ticks;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    // ── Config accessors (defensive: a config read before load throws) ────────

    private static boolean enabled() {
        try { return AuthConfig.DAILY_RESTART_WARN_ENABLED.get(); } catch (Exception e) { return false; }
    }

    private static int leadMinutes() {
        try { return AuthConfig.DAILY_RESTART_WARN_MINUTES.get(); } catch (Exception e) { return 10; }
    }

    /** The host's restart time. Falls back to 10:30 rather than throwing on a typo'd config. */
    private static LocalTime restartTime() {
        String raw = "10:30";
        try { raw = AuthConfig.DAILY_RESTART_TIME.get(); } catch (Exception ignored) {}
        try {
            return LocalTime.parse(raw.trim());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn(
                "[RestartSchedule] dailyRestartTime '{}' is not HH:mm — falling back to 10:30.", raw);
            return LocalTime.of(10, 30);
        }
    }

    /**
     * ⚠️ An explicit zone, NOT the JVM default. The container currently reports +05:30, which happens
     * to match Asia/Colombo — so a default-zone implementation would look correct today and quietly
     * fire at the wrong hour the moment the backend moves host. The backend has moved three times
     * already (Contabo → Apex → Cybrancee → Lagless).
     */
    private static ZoneId zone() {
        String raw = "Asia/Colombo";
        try { raw = AuthConfig.DAILY_RESTART_TIMEZONE.get(); } catch (Exception ignored) {}
        try {
            return ZoneId.of(raw.trim());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn(
                "[RestartSchedule] dailyRestartTimezone '{}' is not a valid zone id — using Asia/Colombo.", raw);
            return ZoneId.of("Asia/Colombo");
        }
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    /** Called every tick from the mod's ServerTickEvent.Post listener. Self-throttles to 1 Hz. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++ticks % 20 != 0) return;

        // ── Evacuation follows ANY countdown, whatever started it ────────────────
        //
        // 🔴 Deliberately ABOVE both early-returns below. Until 2026-09-09 evacuation only ever ran
        // from the daily-schedule branch, which meant the two ways an admin actually restarts the
        // server never evacuated anybody:
        //   • `/authmod warn 5` — the daily branch sees RestartWarning.isActive() and returns at the
        //     "never stomp an admin's countdown" guard, so it never reached the evacuation call;
        //   • any countdown outside the daily arm window — killed by `now.isBefore(armMoment)`.
        // Both looked like the feature was on and doing nothing.
        //
        // Gated on the countdown rather than on dailyRestartWarnEnabled for the same reason: a manual
        // warn must still evacuate when the daily warning is switched off.
        boolean counting = RestartWarning.isActive();
        if (counting && !wasCountingDown) evacuated = false;   // a NEW countdown, of any origin
        wasCountingDown = counting;

        if (counting && !evacuated && RestartWarning.secondsLeft() <= evacuateLead()) {
            evacuated = true;
            evacuate(server);
        }

        if (!enabled()) return;

        try {
            ZoneId zone = zone();
            ZonedDateTime now = ZonedDateTime.now(zone);

            // Next occurrence of the restart time. Rolling forward when today's has passed is what
            // makes an early-morning restart time work: for a 00:05 restart with a 10-minute lead,
            // the arm moment is 23:55 the PREVIOUS day, and anchoring on "today's date" would build
            // a window that starts after it ends and therefore never opens.
            ZonedDateTime restartMoment = now.toLocalDate().atTime(restartTime()).atZone(zone);
            if (restartMoment.isBefore(now)) restartMoment = restartMoment.plusDays(1);

            ZonedDateTime armMoment = restartMoment.minusMinutes(leadMinutes());
            if (now.isBefore(armMoment)) return;                 // common path: not yet

            long secondsLeft = Duration.between(now, restartMoment).getSeconds();
            if (secondsLeft <= 0) return;

            if (!restartMoment.equals(armedFor)) {
                // Never stomp a countdown an admin started by hand with /authmod warn. Theirs is
                // deliberate and probably shorter; ours would silently replace it.
                if (RestartWarning.isActive()) return;

                RestartWarning.startSeconds(server, secondsLeft);
                armedFor = restartMoment;
                finalPingSent = false;
                evacuated = false;

                broadcast(server, "§c§l⚠ Daily restart at §f" + restartMoment.format(CLOCK)
                    + "§c§l — §e" + (secondsLeft / 60 + 1) + " minute(s) away.");
                broadcast(server, "§7Land your ship, finish your trade and stand somewhere safe. "
                    + "The server comes straight back up.");

                CoffeesAeroAuth.LOGGER.info(
                    "[RestartSchedule] Armed the countdown for the {} restart ({}s out, {} players online).",
                    restartMoment.format(CLOCK), secondsLeft, server.getPlayerList().getPlayerCount());
                return;
            }

            // One last chat line under a minute — the boss bar is always on screen, but a player
            // deep in an inventory or a Create UI is looking at neither the bar nor the world.
            if (!finalPingSent && secondsLeft <= 60) {
                finalPingSent = true;
                broadcast(server, "§4§lRESTARTING IN " + secondsLeft + " SECONDS.");
            }

            // ── Graceful evacuation: move everyone to the lobby rather than letting the restart
            //    drop them. Nobody is kicked and nobody is told to reconnect.
            //
            // ⚠️ Fires EARLY (evacuateSecondsBeforeRestart) on purpose. A transfer is asynchronous —
            // the client tears down this connection and dials the lobby itself — so evacuating at the
            // restart moment cuts transfers that are still in flight. Evacuating early lets the player
            // list drain so the panel's stop lands on an empty server.
            //
            // 🔴 This does NOT stop the server. The Pterodactyl panel still owns that. Two independent
            // things restarting one process is how you get a stop landing on a server that is already
            // starting up.
            if (!evacuated && secondsLeft <= evacuateLead()) {
                evacuated = true;
                evacuate(server);
            }
        } catch (Throwable t) {
            // A courtesy warning must never be able to take the tick loop down.
            CoffeesAeroAuth.LOGGER.warn("[RestartSchedule] skipped: {}", t.toString());
        }
    }

    private static int evacuateLead() {
        try { return AuthConfig.EVACUATE_SECONDS_BEFORE.get(); } catch (Exception e) { return 20; }
    }

    /**
     * Moves every online player to the lobby server.
     *
     * <p>Reuses {@code LobbyHandoff.returnToLobby}, which SIGNS A COOKIE — so an evacuated premium
     * player arrives at the lobby still premium instead of being silently demoted to OFFLINE by the
     * anti-spoof rule. That is the whole reason evacuation is a transfer and not a kick.
     *
     * <p>Nobody is disconnected and nobody is told to reconnect: the client is handed an address and
     * moves itself, which is the entire point of having a lobby.
     *
     * <p>ONE summary log line, not one per player. Logging or alerting per evacuated player is the
     * mistake the movement watchdog made — a full server emits forty lines, and routed to Discord it
     * would rate limit the bridge.
     */
    private static void evacuate(MinecraftServer server) {
        boolean on;
        try { on = AuthConfig.GRACEFUL_RESTART_ENABLED.get(); } catch (Exception e) { return; }
        if (!on) return;
        if (com.coffeesaerosmp.auth.lobby.LobbyHandoff.isLobbyRole()) return;   // SMP only

        java.util.List<net.minecraft.server.level.ServerPlayer> players =
            new java.util.ArrayList<>(server.getPlayerList().getPlayers());
        if (players.isEmpty()) {
            CoffeesAeroAuth.LOGGER.info("[Restart] Evacuation: nobody online, nothing to move.");
            return;
        }
        broadcast(server, "\u00a7e\u23f3 Moving everyone to the lobby for the restart \u2014 stay put, you will be let back in.");
        int moved = 0, failed = 0;
        for (net.minecraft.server.level.ServerPlayer p : players) {
            try {
                if (com.coffeesaerosmp.auth.lobby.LobbyHandoff.returnToLobby(p)) moved++;
                else failed++;      // lobbyReturnHost unset - nothing we can do for them
            } catch (Exception e) {
                failed++;
            }
        }
        CoffeesAeroAuth.LOGGER.info("[Restart] Evacuation: moved {} player(s) to the lobby, {} not moved.",
            moved, failed);
        if (failed > 0) {
            CoffeesAeroAuth.LOGGER.warn("[Restart] {} player(s) stayed \u2014 check lobbyReturnHost is set. "
                + "They will be dropped by the restart as before.", failed);
        }
    }

    /** Human-readable next-restart line, for status commands. Empty when disabled. */
    public static String nextRestartDescription() {
        if (!enabled()) return "";
        try {
            ZoneId zone = zone();
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime next = now.toLocalDate().atTime(restartTime()).atZone(zone);
            if (next.isBefore(now)) next = next.plusDays(1);
            long mins = Duration.between(now, next).toMinutes();
            return "Next daily restart " + next.format(CLOCK) + " (" + (mins / 60) + "h " + (mins % 60) + "m)";
        } catch (Exception e) {
            return "";
        }
    }

    private static void broadcast(MinecraftServer server, String line) {
        server.getPlayerList().broadcastSystemMessage(
            Component.literal(TextUtil.PREFIX + line), false);
    }
}
