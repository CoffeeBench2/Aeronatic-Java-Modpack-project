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
 * <h2>Activity is player INPUT (reworked 1.11.5)</h2>
 *
 * Counted: chat, commands, breaking, using/placing, interacting, attacking, opening containers
 * (events wired in {@code CoffeesAeroAuth}); inventory clicks, hotbar changes, arm swings, sneak/sprint
 * and vehicle steering ({@code mixin/AfkInputMixin}); plus walking and looking around, sampled here
 * once a second. Taking damage and picking items up are deliberately NOT activity — an AFK player
 * parked in a mob farm does both continuously.
 *
 * <p>🔴 Movement caused by something else is NOT activity. Position is ignored while riding, and on a
 * Sable ship it is measured in DECK coordinates ({@link SableShip}). Before 1.11.5 a hovering airship's
 * physics drift read as walking: zzholmes idled 2 h 39 min in a cockpit on 2026-09-29 and was only
 * removed by a staff kick, while this tracker had lobby-transferred him ~25 times elsewhere. While
 * riding or on a ship, only a real head turn ({@value #RIDING_LOOK_EPSILON}°) counts as looking,
 * because the ship's own wobble turns the rider with it.
 *
 * <p>Every input also passes through {@link MacroDetector}: clockwork timing (auto-clickers, a weight
 * on the mouse) and jigglers do not reset the timer. See that class.
 */
public final class AfkTracker {

    private AfkTracker() {}

    /** Movement smaller than this is jitter (mounts, boats, pistons, floating-point noise). */
    private static final double MOVE_EPSILON_SQR = 0.02 * 0.02;
    /** Rotation smaller than this is jitter rather than a player looking around. */
    private static final float LOOK_EPSILON = 0.5f;
    /** While riding or on a ship the vehicle turns the player with it; only a deliberate turn counts. */
    static final float RIDING_LOOK_EPSILON = 12f;
    /** Refused inputs during one idle stretch before staff hear about it. */
    private static final int MACRO_ALERT_MIN_REFUSED = 30;
    private static final long MACRO_ALERT_COOLDOWN_MS = 60 * 60_000L;   // per player
    private static final Map<UUID, Long> lastMacroAlert = new ConcurrentHashMap<>();

    private static final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static int ticks = 0;

    private static final class State {
        long    lastActivityMs;
        boolean afk;
        /** Wall clock up to which idle time has already been excluded. Only meaningful while afk. */
        long    excludedTo;
        /** Last position sample, in {@link #frame}'s coordinates; invalid while riding. */
        boolean hasPos;
        UUID    frame;               // the Sable ship whose deck coordinates x/y/z are in; null = world
        double  x, y, z;
        float   yRot, xRot;
        boolean warned;
        String  lastSource = "JOIN";
        final MacroDetector macro = new MacroDetector();
        // Vehicle steering arrives EVERY tick while riding; only a change is a keypress.
        float   inXxa, inZza;
        boolean inJump, inShift;
    }

    // ── Activity ──────────────────────────────────────────────────────────────

    /**
     * Records one player input. Called on the server thread (event handlers and
     * {@code AfkInputMixin}, which injects after the packet has been moved onto it). It only stamps a
     * timestamp, so the AFK/return transition and its profile write stay on the tick path.
     */
    public static void input(ServerPlayer player, MacroDetector.Kind kind) {
        if (player == null) return;
        State s = states.get(player.getUUID());
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (!macroDetection() || s.macro.event(kind, now)) activity(s, now, kind.name());
    }

    /** Event-bus adapter for events that carry a player. */
    public static void onPlayerActivity(net.neoforged.neoforge.event.entity.player.PlayerEvent event,
                                        MacroDetector.Kind kind) {
        if (event.getEntity() instanceof ServerPlayer sp) input(sp, kind);
    }

    /** Vehicle steering (ServerboundPlayerInputPacket). Sent every tick while riding, so only a CHANGE counts. */
    public static void vehicleInput(ServerPlayer player, float xxa, float zza, boolean jump, boolean shift) {
        if (player == null) return;
        State s = states.get(player.getUUID());
        if (s == null) return;
        if (xxa == s.inXxa && zza == s.inZza && jump == s.inJump && shift == s.inShift) return;
        s.inXxa = xxa; s.inZza = zza; s.inJump = jump; s.inShift = shift;
        input(player, MacroDetector.Kind.VEHICLE_INPUT);
    }

    private static void activity(State s, long now, String source) {
        s.lastActivityMs = now;
        s.lastSource = source;
    }

    private static boolean macroDetection() {
        try { return AuthConfig.AFK_MACRO_DETECTION.get(); } catch (Exception e) { return true; }
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
            states.put(id, fresh(player, now));
            return;
        }

        // Samples are compared second to second, so a player carried somewhere by something else and
        // then released never produces one huge delta that reads as activity.
        sampleMotion(s, player, now);

        long idle = now - s.lastActivityMs;

        if (!s.afk) {
            if (idle < timeoutMs) {
                warnIfDue(player, s, idle, timeoutMs);
                return;
            }
            s.afk = true;
            s.warned = false;
            // Retroactive: exclude the entire idle stretch, not just what follows the threshold.
            exclude(player, idle, now);
            s.excludedTo = now;
            TextUtil.msg(player, "§7You are now §8AFK§7 — playtime is paused. Move to resume.");
            announce(player, " is now AFK");
            reportMacro(player, s, idle);
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
     * Walking and looking around, from once-a-second samples — counted only when the player did it.
     *
     * <ul>
     *   <li><b>Riding</b> (boat, minecart, horse, a Create or Aeronautics seat): position is ignored —
     *       the vehicle moves them. Steering arrives separately as {@code VEHICLE_INPUT}.</li>
     *   <li><b>On a Sable ship</b>: position is compared in the ship's own coordinates, so the ship
     *       drifting is invisible and walking its deck is not. Boarding or leaving a ship re-bases the
     *       sample instead of counting as a step.</li>
     *   <li><b>Looking</b>: 0.5° on solid ground, {@value #RIDING_LOOK_EPSILON}° while riding or on a ship.</li>
     * </ul>
     * Both then pass the jiggle check in {@link MacroDetector}.
     */
    private static void sampleMotion(State s, ServerPlayer p, long now) {
        boolean riding = p.isPassenger();
        SableShip.Local deck = riding ? null : SableShip.local(p);
        boolean macro = macroDetection();

        if (riding) {
            s.hasPos = false;
        } else {
            UUID frame = deck == null ? null : deck.ship();
            double x = deck == null ? p.getX() : deck.x();
            double y = deck == null ? p.getY() : deck.y();
            double z = deck == null ? p.getZ() : deck.z();
            if (s.hasPos && java.util.Objects.equals(frame, s.frame)) {
                double dx = x - s.x, dy = y - s.y, dz = z - s.z;
                if (dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQR && (!macro || s.macro.move(x, y, z))) {
                    activity(s, now, deck == null ? "MOVE" : "MOVE_ON_SHIP");
                }
            }
            s.frame = frame;
            s.x = x; s.y = y; s.z = z;
            s.hasPos = true;
        }

        float eps = (riding || deck != null) ? RIDING_LOOK_EPSILON : LOOK_EPSILON;
        float yRot = p.getYRot(), xRot = p.getXRot();
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(yRot - s.yRot)) > eps
                || Math.abs(xRot - s.xRot) > eps) {
            if (!macro || s.macro.look(yRot, xRot)) activity(s, now, "LOOK");
        }
        s.yRot = yRot; s.xRot = xRot;
    }

    private static void snapshot(State s, ServerPlayer p) {
        s.x = p.getX(); s.y = p.getY(); s.z = p.getZ();
        s.yRot = p.getYRot(); s.xRot = p.getXRot();
        s.hasPos = !p.isPassenger();
        SableShip.Local deck = s.hasPos ? SableShip.local(p) : null;
        if (deck != null) { s.frame = deck.ship(); s.x = deck.x(); s.y = deck.y(); s.z = deck.z(); }
    }

    /** One warning, {@code afkWarnSeconds} before the player would be moved. Any input re-arms it. */
    private static void warnIfDue(ServerPlayer player, State s, long idle, long timeoutMs) {
        long warnMs = AuthConfig.AFK_WARN_SECONDS.get() * 1000L;
        if (warnMs <= 0 || idle < timeoutMs - warnMs) {
            s.warned = false;
            return;
        }
        if (s.warned || !AfkKick.wouldMove(player)) return;
        s.warned = true;
        long secs = Math.max(1, (timeoutMs - idle + 999) / 1000);
        try {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 70, 20));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                net.minecraft.network.chat.Component.literal("§eStill there?")));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                net.minecraft.network.chat.Component.literal("§7Move, look around or type within §f" + secs + "s§7 to stay")));
        } catch (Exception ignored) {}
        TextUtil.msg(player, "§eYou've been idle a while — you'll be moved to the lobby in §f" + secs
            + "s§e. Move, look around or type to stay.");
    }

    /**
     * Went AFK while something kept pressing inputs we refused as machine-made. Worth a human look —
     * it is the auto-clicker / weighted-mouse / jiggler signature — but not an accusation, so MEDIUM,
     * once an hour per player. Every case is logged regardless of the alert limit.
     */
    private static void reportMacro(ServerPlayer player, State s, long idleMs) {
        int refused = s.macro.refusedSinceHuman();
        if (refused < MACRO_ALERT_MIN_REFUSED) return;
        String who = displayName(player);
        String what = s.macro.summary();
        CoffeesAeroAuth.LOGGER.info("[AFK] {} went AFK while {} inputs were refused as machine-made: {}",
            player.getGameProfile().getName(), refused, what);
        long now = System.currentTimeMillis();
        Long last = lastMacroAlert.get(player.getUUID());
        if (last != null && now - last < MACRO_ALERT_COOLDOWN_MS) return;
        lastMacroAlert.put(player.getUUID(), now);
        if (lastMacroAlert.size() > 512) lastMacroAlert.clear();
        if (CoffeesAeroAuth.WATCHDOG == null) return;
        try {
            CoffeesAeroAuth.WATCHDOG.alert(com.coffeesaerosmp.auth.watchdog.WatchdogEvent.of(
                com.coffeesaerosmp.auth.watchdog.Severity.MEDIUM,
                "Possible macro / auto-clicker",
                "Player went AFK and was moved as normal. Inputs kept firing on a fixed rhythm or pattern.",
                "Player",  who,
                "Idle",    (idleMs / 60_000) + " min",
                "Refused", refused + " inputs: " + what,
                "Note",    "Could be an auto-clicker, a weighted mouse/key, or a jiggler. Check before acting."));
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[AFK] macro alert failed: {}", t.toString());
        }
    }

    /** For {@code /afkcheck}: what the tracker currently believes about this player. */
    public static java.util.List<String> describe(ServerPlayer player) {
        java.util.List<String> out = new java.util.ArrayList<>();
        State s = states.get(player.getUUID());
        if (s == null) { out.add("§7No AFK state yet (just joined, or AFK tracking is off)."); return out; }
        long idle = (System.currentTimeMillis() - s.lastActivityMs) / 1000;
        out.add("§6AFK §f" + player.getGameProfile().getName() + (s.afk ? " §8[AFK]" : " §a[active]"));
        out.add("§7idle §f" + idle + "s§7 · last activity §f" + s.lastSource);
        SableShip.Local deck = player.isPassenger() ? null : SableShip.local(player);
        out.add("§7frame §f" + (player.isPassenger() ? "riding " + player.getVehicle().getType().toShortString()
            : deck != null ? "Sable ship " + deck.ship() : "world"));
        int refused = s.macro.refusedSinceHuman();
        out.add(refused == 0 ? "§7macro §anothing refused"
                             : "§7macro §e" + refused + " refused §7(" + s.macro.summary() + ")");
        return out;
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
        // 🔴 SMP-ONLY, and this guard is load-bearing rather than tidy.
        //
        // Playtime is only ever ADDED on the SMP (owner decision 2026-09-22 — the lobby is a
        // waiting room, not play). Stage 2 below SUBTRACTS from the banked total. Running that on
        // the lobby would therefore deduct AFK time from hours the player genuinely earned on the
        // SMP: a one-way leak that gets worse the longer someone idles in the lobby.
        if (com.coffeesaerosmp.auth.lobby.LobbyHandoff.isLobbyRole()) return;
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

    /** First sight: start active, from wherever they are. */
    private static State fresh(ServerPlayer player, long now) {
        State s = new State();
        s.lastActivityMs = now;
        snapshot(s, player);
        return s;
    }
}
