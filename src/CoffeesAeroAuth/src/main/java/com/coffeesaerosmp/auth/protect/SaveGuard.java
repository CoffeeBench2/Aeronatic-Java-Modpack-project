package com.coffeesaerosmp.auth.protect;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.server.MinecraftServer;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bounds how much progress a hard crash can destroy.
 *
 * <h2>Why this exists</h2>
 * Sable's physics runs in native Rust (Rapier) behind JNI. That boundary is {@code extern "C"} and
 * declared non-unwinding, so when Rapier panics — {@code no entry found for key} in
 * {@code Rapier3D_getPose}, i.e. a pose query against a rigid-body handle that has already been
 * removed — Rust's only legal move is {@code abort()}.
 *
 * <p>{@code abort()} is not a Java exception. It is SIGABRT. The JVM dies mid-instruction:
 * <ul>
 *   <li>no crash report is written — the JVM never reaches its handler;</li>
 *   <li>no shutdown hook runs, so <b>the world is never saved</b>;</li>
 *   <li>the log simply stops mid-line, wherever the appender last flushed.</li>
 * </ul>
 *
 * <p>On 2026-08-08 the server aborted at 15:42:10 having booted at 15:38:52 — <b>3m18s of uptime,
 * inside vanilla's 5-minute autosave period</b>. Nothing from that session ever reached disk. Six
 * players lost everything they had done, including a Corail Tombstone grave created 36 seconds
 * before the abort, whose owner was still walking back to collect it.
 *
 * <p><b>This class cannot stop the panic.</b> Nothing on the Java side can: by the time
 * {@code getPose} is entered the process is already going down, and SIGABRT is not catchable by the
 * JVM. What it can do is make the blast radius a minute instead of five.
 *
 * <h2>Design</h2>
 * Two independent cadences, because the two costs are wildly different:
 * <ul>
 *   <li><b>Player data</b> (default 60s) — one small file per online player. This is what holds
 *       inventories, so it is the half that actually answers "my backpack vanished". Cheap enough
 *       to run often.</li>
 *   <li><b>Full world</b> (default 120s) — chunks too, so it covers anything stored in the world
 *       rather than on the player: graves, dropped items, chests, ships. Expensive, hence rarer.</li>
 * </ul>
 *
 * <p>Both run on the server thread, which is mandatory — a save is not thread-safe against a
 * running tick, and dispatching it elsewhere would trade lost items for a corrupted region file.
 * The tradeoff is honest: saving blocks the tick, so a slow save is logged loudly rather than
 * hidden, and the intervals are config so they can be backed off if this server can't afford them.
 *
 * <p>Note this reduces LOSS, not corruption risk. An abort landing mid-write can still damage a
 * region file whatever the interval is; off-box backups remain the only answer to that.
 */
public final class SaveGuard {

    private SaveGuard() {}

    private static int  tickCounter;
    private static long lastPlayerSaveMs;
    private static long lastWorldSaveMs;

    /** Reset between server lifecycles so a restart doesn't inherit the previous run's clock. */
    public static void onServerStarted() {
        tickCounter      = 0;
        lastPlayerSaveMs = System.currentTimeMillis();
        lastWorldSaveMs  = System.currentTimeMillis();
        if (!AuthConfig.SAVEGUARD_ENABLED.get()) {
            CoffeesAeroAuth.LOGGER.info("[SaveGuard] Disabled by config.");
            return;
        }
        CoffeesAeroAuth.LOGGER.info(
            "[SaveGuard] Active — player data every {}s, full world every {}s.",
            AuthConfig.SAVEGUARD_PLAYER_SECONDS.get(), AuthConfig.SAVEGUARD_WORLD_SECONDS.get());
    }

    /** Called from the mod's ServerTickEvent.Post listener. Self-throttles to 1Hz. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++tickCounter < 20) return;         // 1Hz is plenty; the intervals are in seconds
        tickCounter = 0;

        try {
            if (!AuthConfig.SAVEGUARD_ENABLED.get()) return;

            if (AuthConfig.SAVEGUARD_SKIP_WHEN_EMPTY.get()
                && server.getPlayerList().getPlayerCount() == 0) {
                // Keep the clocks moving, or an empty server would fire both saves the instant
                // someone joins — the worst possible moment, since a join is already expensive.
                long now = System.currentTimeMillis();
                lastPlayerSaveMs = now;
                lastWorldSaveMs  = now;
                return;
            }

            long now = System.currentTimeMillis();

            int worldSecs = AuthConfig.SAVEGUARD_WORLD_SECONDS.get();
            if (worldSecs > 0 && now - lastWorldSaveMs >= worldSecs * 1000L) {
                lastWorldSaveMs  = now;
                // A full save also writes player data, so hold the cheap timer off too rather than
                // saving the same players twice in consecutive seconds.
                lastPlayerSaveMs = now;
                timed("world", () -> server.saveEverything(true, false, false));
                return;
            }

            int playerSecs = AuthConfig.SAVEGUARD_PLAYER_SECONDS.get();
            if (playerSecs > 0 && now - lastPlayerSaveMs >= playerSecs * 1000L) {
                lastPlayerSaveMs = now;
                bankPlaytime(server);
                timed("player data", () -> server.getPlayerList().saveAll());
            }
        } catch (Exception e) {
            // Never let this kill the tick listener. A save that fails is bad; a save that takes
            // the server down with it is worse, and losing the listener would silently restore the
            // exact 5-minute exposure this class exists to remove.
            CoffeesAeroAuth.LOGGER.error("[SaveGuard] Save pass failed", e);
        }
    }

    /**
     * Bank each online player's accrued playtime into their profile, then restart their session
     * clock from now.
     *
     * <p>WHY (2026-08-08, the "he had 100 hours, where did they go?" report): playtime was banked in
     * exactly ONE place — {@code AuthManager.onPlayerLeave}. A clean disconnect runs it; a
     * <b>SIGABRT does not</b>. Sable's Rapier panic kills the JVM outright, so no leave handler runs
     * for anybody, and <b>every online player loses their entire session's hours</b>. With the
     * server aborting repeatedly, hours were evaporating faster than they accrued — which is exactly
     * what the profile cards showed.
     *
     * <p>Rolling {@code sessionStartEpoch} forward to {@code now} rather than zeroing it is what
     * makes this safe to repeat: the session stays "open" so the live figure in /profile still
     * works, and the same seconds can never be counted twice. {@code onPlayerLeave} then banks only
     * the remainder since the last bank.
     */
    private static void bankPlaytime(MinecraftServer server) {
        var store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return;
        long now = System.currentTimeMillis();
        int banked = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            try {
                PlayerProfile prof = store.get(p.getUUID());
                if (prof == null || prof.sessionStartEpoch <= 0) continue;
                long secs = (now - prof.sessionStartEpoch) / 1000L;
                if (secs <= 0) continue;
                prof.totalPlaytimeSeconds += secs;
                prof.sessionStartEpoch = now;          // roll forward — never double-count
                store.save(prof);
                banked++;
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.debug("[SaveGuard] playtime bank skipped for {}: {}",
                    p.getGameProfile().getName(), e.toString());
            }
        }
        if (banked > 0) CoffeesAeroAuth.LOGGER.debug("[SaveGuard] Banked playtime for {} player(s).", banked);
    }

    private static void timed(String what, Runnable save) {
        long t0 = System.nanoTime();
        save.run();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms >= AuthConfig.SAVEGUARD_SLOW_WARN_MS.get()) {
            CoffeesAeroAuth.LOGGER.warn(
                "[SaveGuard] {} save blocked the server thread for {}ms — raise the interval if this repeats.",
                what, ms);
        } else {
            CoffeesAeroAuth.LOGGER.debug("[SaveGuard] {} save took {}ms.", what, ms);
        }
    }
}
