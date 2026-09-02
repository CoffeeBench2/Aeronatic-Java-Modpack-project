package com.coffeesaerosmp.auth.afk;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idle timer — stops playtime accruing while a player is AFK.
 *
 * <h2>🔑 How it works, and why it is done this way</h2>
 *
 * A session's elapsed time is derived as {@code now - sessionStartEpoch} in <b>six</b> independent
 * places: {@code AuthManager.onPlayerLeave} (the real bank), {@code SaveGuard.bankPlaytime} (the
 * periodic bank), {@code SidebarManager}, {@code ProfileCommands} (three separate sites) and
 * {@code DiscordInteractions} (the leaderboard). Subtracting an "AFK seconds" accumulator would
 * therefore have meant editing six call sites and getting all six right — and any one that was
 * missed would silently disagree with the others, which is the class of bug that produced
 * "he had 100 hours, where did they go?" in the first place.
 *
 * <p>So this does not accumulate anything. <b>It rolls {@code sessionStartEpoch} FORWARD while the
 * player is idle.</b> Idle time then never enters {@code now - sessionStartEpoch} at all, and every
 * one of those six consumers becomes correct without being touched. It is the same trick
 * {@code SaveGuard} already uses to bank repeatedly without double-counting, pointed the other way.
 *
 * <p>A useful consequence: because the roll happens every second, the un-excluded window at any
 * instant is at most one second. A crash, a SIGABRT or a disconnect mid-AFK can therefore lose at
 * most ~1s of correction — there is no "pending AFK debt" that has to be flushed on the way out.
 *
 * <h2>Retroactive by design</h2>
 *
 * When the timeout expires the WHOLE idle stretch is excluded, not just the part after the
 * threshold. Those first minutes were equally idle, and crediting them would hand out a free
 * {@code afkTimeoutMinutes} every time an auto-clicker or a mouse-jiggler twitched — which is
 * precisely the behaviour this exists to stop.
 *
 * <h2>⚠ What this does and does not catch</h2>
 *
 * Activity is position, rotation, and the deliberate actions wired up in {@code CoffeesAeroAuth}
 * (chat, commands, breaking, placing, right-clicking, attacking, opening a container). Taking
 * damage and picking items up are deliberately NOT activity — an AFK player parked in a mob farm
 * does both continuously, and counting them would make the farm case, the main thing worth
 * catching, invisible. Nothing here defeats a physical mouse-jiggler; that is accepted.
 */
public final class AfkTracker {

    private AfkTracker() {}

    /** Movement smaller than this is jitter (mounts, boats, pistons, floating-point noise). */
    private static final double MOVE_EPSILON_SQR = 0.02 * 0.02;
    /** Rotation smaller than this is jitter rather than a player looking around. */
    private static final float LOOK_EPSILON = 0.5f;

    private static final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static int ticks = 0;

    private static final class State {
        long    lastActivityMs;
        boolean afk;
        /** Wall clock up to which idle time has already been excluded. Only meaningful while afk. */
        long    excludedTo;
        double  x, y, z;
        float   yRot, xRot;
    }

    // ── Activity ──────────────────────────────────────────────────────────────

    /**
     * Records deliberate activity. Safe to call from any event handler; it only stamps a timestamp,
     * so the AFK/return transition (and the profile write that goes with it) stays on the tick path
     * where it cannot race.
     */
    public static void touch(ServerPlayer player) {
        if (player == null) return;
        State s = states.get(player.getUUID());
        if (s != null) s.lastActivityMs = System.currentTimeMillis();
    }

    /** Event-bus adapter: any event carrying a player counts as activity. */
    public static void onPlayerActivity(net.neoforged.neoforge.event.entity.player.PlayerEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) touch(sp);
    }

    public static boolean isAfk(ServerPlayer player) {
        State s = player == null ? null : states.get(player.getUUID());
        return s != null && s.afk;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    /** Call every server tick; throttles internally to once a second. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++ticks % 20 != 0) return;
        if (!AuthConfig.AFK_ENABLED.get()) return;

        long now       = System.currentTimeMillis();
        long timeoutMs = AuthConfig.AFK_TIMEOUT_MINUTES.get() * 60_000L;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            try {
                update(p, now, timeoutMs);
            } catch (Throwable t) {
                // An idle timer must never be able to take the tick loop down.
                CoffeesAeroAuth.LOGGER.warn("[AFK] update failed for {}",
                    p.getGameProfile().getName(), t);
            }
        }
    }

    private static void update(ServerPlayer player, long now, long timeoutMs) {
        UUID id = player.getUUID();
        State s = states.get(id);
        if (s == null) {
            // First sight of this player: start them as active, from wherever they are standing.
            s = new State();
            s.lastActivityMs = now;
            snapshot(s, player);
            states.put(id, s);
            return;
        }

        boolean moved = moved(s, player);
        // Snapshot EVERY tick, not just when movement was detected. Otherwise the stored position
        // goes stale while a player is carried around by something (see moved()), and the moment
        // they dismount the tick-to-tick delta is enormous and reads as activity they never
        // performed. Comparing consecutive ticks keeps the test honest.
        snapshot(s, player);
        if (moved) s.lastActivityMs = now;

        long idle = now - s.lastActivityMs;

        if (!s.afk) {
            if (idle < timeoutMs) return;
            s.afk = true;
            // Retroactive: exclude the entire idle stretch, not just what follows the threshold.
            exclude(player, idle, now);
            s.excludedTo = now;
            TextUtil.msg(player, "§7You are now §8AFK§7 — playtime is paused. Move to resume.");
            announce(player, " is now AFK");
            // Playtime is settled above, so the disconnect below cannot lose or double-count it.
            AfkKick.consider(player);
            return;
        }

        if (idle >= timeoutMs) {
            exclude(player, now - s.excludedTo, now);
            s.excludedTo = now;
            return;
        }

        // Back at the keyboard. Exclude only up to the MOMENT of activity, not up to now, or every
        // return would quietly eat the sub-second remainder of the tick they came back on.
        exclude(player, s.lastActivityMs - s.excludedTo, now);
        s.afk = false;
        TextUtil.msg(player, "§7Welcome back — playtime is counting again.");
        announce(player, " is no longer AFK");
    }

    /**
     * Tells everyone else, in plain grey — no house prefix, no colour accent, no bold. This is
     * ambient information ("don't wait for a reply"), not a server announcement, and it should read
     * as quieter than normal chat rather than louder.
     *
     * <p>Sent to everyone EXCEPT the player themselves, who already got the more useful private
     * line about their playtime being paused; broadcasting to them as well would just say the same
     * thing twice.
     *
     * <p>⚠ Uses the DISPLAY name. This server masks real account names, and a broadcast built from
     * {@code getGameProfile().getName()} is exactly how an account name leaks to every player at
     * once.
     */
    private static void announce(ServerPlayer player, String suffix) {
        if (!AuthConfig.AFK_ANNOUNCE.get()) return;
        var msg = net.minecraft.network.chat.Component.literal("§7" + displayName(player) + suffix);
        for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
            if (other != player) other.sendSystemMessage(msg);
        }
    }

    private static String displayName(ServerPlayer player) {
        var store = CoffeesAeroAuth.PROFILE_STORE;
        PlayerProfile p = store == null ? null : store.get(player.getUUID());
        return p != null && p.displayName != null && !p.displayName.isBlank()
            ? p.displayName : player.getGameProfile().getName();
    }

    /**
     * Has the player done something that only a person at the keyboard does?
     *
     * <p>⚠ <b>Position is ignored while they are a passenger.</b> A boat, minecart or horse moves
     * its rider without any input at all, so counting that as activity would exempt the single most
     * common AFK farm design on any server. Rotation still counts, and anyone actually steering is
     * moving the mouse constantly — so a real rider is never mistaken for an idle one.
     *
     * <p>⚠ NOT covered: a player standing on a moving Sable ship is not a vanilla passenger, so
     * their world position genuinely changes and they read as active. Closing that needs Sable's
     * sub-level API and is not attempted here.
     */
    private static boolean moved(State s, ServerPlayer p) {
        if (!p.isPassenger()) {
            double dx = p.getX() - s.x, dy = p.getY() - s.y, dz = p.getZ() - s.z;
            if (dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQR) return true;
        }
        return Math.abs(p.getYRot() - s.yRot) > LOOK_EPSILON
            || Math.abs(p.getXRot() - s.xRot) > LOOK_EPSILON;
    }

    private static void snapshot(State s, ServerPlayer p) {
        s.x = p.getX(); s.y = p.getY(); s.z = p.getZ();
        s.yRot = p.getYRot(); s.xRot = p.getXRot();
    }

    // ── The one mutation ──────────────────────────────────────────────────────

    /**
     * Removes {@code millis} of already-elapsed time from the player's playtime.
     *
     * <p><b>Two stages, and the second one is not optional.</b> The obvious implementation is just
     * to push {@code sessionStartEpoch} forward. That is correct in the steady state, but it cannot
     * work for the retroactive step, because <b>{@code SaveGuard} banks playtime every 60 seconds</b>
     * ({@code saveGuardPlayerSeconds}, default 60). By the time a 5-minute idle stretch trips the
     * timeout, SaveGuard has already run ~5 times, committed all five idle minutes into
     * {@code totalPlaytimeSeconds}, and rolled {@code sessionStartEpoch} up to ~now — leaving no
     * headroom at all to push into. The retroactive credit would silently do nothing, and every AFK
     * episode would leak a full threshold. A jiggler firing just over the timeout would keep ~83% of
     * its idle time.
     *
     * <p>So: push the session clock forward as far as {@code now} allows, and take whatever is left
     * back out of {@code totalPlaytimeSeconds}, which is where SaveGuard put it.
     *
     * <p>Not written through {@code ProfileStore.save} on purpose — SaveGuard and
     * {@code onPlayerLeave} both persist the profile anyway, and saving here would add a DB round
     * trip per idle player per second on a link with a 234 ms RTT.
     */
    private static void exclude(ServerPlayer player, long millis, long now) {
        if (millis <= 0L) return;
        if (CoffeesAeroAuth.PROFILE_STORE == null) return;
        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
        if (profile == null || profile.sessionStartEpoch <= 0L) return;   // no open session

        // Stage 1 — the live, unbanked part of the session.
        // Never push the start past now: every consumer computes (now - sessionStartEpoch), and a
        // start in the future makes that negative, which would read as time travelling backwards in
        // /profile and could bank a negative session.
        long headroom = Math.max(0L, now - profile.sessionStartEpoch);
        long viaSession = Math.min(millis, headroom);
        profile.sessionStartEpoch += viaSession;

        // Stage 2 — the part SaveGuard already banked.
        long remaining = (millis - viaSession) / 1000L;
        if (remaining <= 0L) return;
        // Floor at the frozen Season 1 snapshot rather than at zero: season playtime is computed as
        // (total − season1), so dipping below it would make this season's hours negative.
        long floor = Math.max(0L, profile.season1PlaytimeSeconds);
        profile.totalPlaytimeSeconds = Math.max(floor, profile.totalPlaytimeSeconds - remaining);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Drop per-player state on logout so nothing leaks across sessions. */
    public static void onPlayerLogout(ServerPlayer player) {
        states.remove(player.getUUID());
    }
}
