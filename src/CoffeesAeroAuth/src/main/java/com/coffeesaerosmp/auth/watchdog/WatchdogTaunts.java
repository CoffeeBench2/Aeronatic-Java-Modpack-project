package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.lobby.PrivateRoomManager;
import com.coffeesaerosmp.auth.util.Sounds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Occasional reminder in chat that the watchdog is running, with a bark.
 *
 * <p>This is deterrence, not decoration. The watchdog already logs UUID switches, lookalike names,
 * impossible movement, command velocity and admin abuse — but a monitor nobody knows about deters
 * nobody. Saying so at intervals is the cheapest anti-cheat there is, and the tone is meant to land
 * as funny-but-not-joking: the lines are true, which is what makes them work.
 *
 * <h2>Why the interval is random</h2>
 *
 * A fixed schedule is something to plan around — "the message just fired, I have 30 clean minutes".
 * A uniform random gap between {@code min} and {@code max} means the next one is never predictable,
 * which is the entire deterrent value. The gap is re-rolled after every broadcast, never reused.
 */
public final class WatchdogTaunts {

    private WatchdogTaunts() {}

    /** The watchdog has a name and a face: Hector, a brown dog. He is not a metaphor. */
    public static final String NAME = "Hector";

    /**
     * Deliberately written as statements of fact rather than threats. Every line describes
     * something the watchdog genuinely does, so nobody can call the bluff.
     *
     * <p>Named in third person now — a nameless "the watchdog" is a system, and systems are argued
     * with. Hector is a brown dog who remembers things, and that is much harder to argue with.
     */
    private static final List<String> LINES = List.of(
        "Hector is awake. Every block you break carries a timestamp.",
        "Somewhere in a log file, your name is already written. Hector put it there.",
        "He sees the chunks you load. He sees the chests you open. Good boy, Hector.",
        "Rule one: Hector is watching. Rule two: see rule one.",
        "Play fair. The brown dog has a very long memory.",
        "Every command you run is on the record. Hector filed it.",
        "Nothing personal — Hector simply remembers everything.",
        "Cheat if you like. Hector's logs are patient.",
        "He knows your IP, your UUID and your last thousand actions. Sleep well.",
        "No alarms today. Hector approves. Let's keep it that way.",
        "Hector does not blink, and he does not bark twice.",
        "Hector has not moved in six hours. He is not asleep.",
        "The brown dog tilted his head. Something in the logs did not add up.",
        "Hector wags for clean sessions. He has not wagged all night."
    );

    private static long nextAt = 0L;
    private static int  lastIndex = -1;
    private static int  ticks = 0;

    /** Call every server tick; throttles internally to once a second. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++ticks % 20 != 0) return;
        if (!AuthConfig.WATCHDOG_TAUNTS_ENABLED.get()) return;

        long now = System.currentTimeMillis();
        if (nextAt == 0L) { schedule(now); return; }   // first arming after boot
        if (now < nextAt) return;

        try {
            broadcast(server);
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[Watchdog] taunt broadcast failed", t);
        } finally {
            // Rescheduled in a finally block so a failure can never wedge the timer at a past
            // deadline and then fire on every single tick from then on.
            schedule(now);
        }
    }

    private static void schedule(long now) {
        int min = AuthConfig.WATCHDOG_TAUNT_MIN_MINUTES.get();
        int max = Math.max(min, AuthConfig.WATCHDOG_TAUNT_MAX_MINUTES.get());
        long gap = ThreadLocalRandom.current().nextLong(min * 60_000L, max * 60_000L + 1L);
        nextAt = now + gap;
    }

    private static void broadcast(MinecraftServer server) {
        // Lobby players are mid-onboarding — being told they are under surveillance before they
        // have even chosen a name is the wrong first impression. World chat only.
        List<ServerPlayer> audience = server.getPlayerList().getPlayers().stream()
            .filter(p -> p.level().dimension() != PrivateRoomManager.LOBBY_DIMENSION)
            .toList();
        if (audience.isEmpty()) return;      // never talk to an empty room

        // §6 (gold) is the closest the legacy §-palette gets to brown — it has no brown code at all,
        // the same limitation that pushed the sidebar header to true RGB in 1.7.37.
        Component line = Component.literal("§8[§c🐾§8] §6§o" + NAME + " §7§o" + pick());
        for (ServerPlayer p : audience) {
            p.sendSystemMessage(line);
            Sounds.watchdog(p);
        }
        CoffeesAeroAuth.LOGGER.debug("[Watchdog] taunt sent to {} player(s).", audience.size());
    }

    /** Never the same line twice running — repetition is what turns a warning into wallpaper. */
    private static String pick() {
        if (LINES.size() == 1) return LINES.get(0);
        int i;
        do {
            i = ThreadLocalRandom.current().nextInt(LINES.size());
        } while (i == lastIndex);
        lastIndex = i;
        return LINES.get(i);
    }
}
