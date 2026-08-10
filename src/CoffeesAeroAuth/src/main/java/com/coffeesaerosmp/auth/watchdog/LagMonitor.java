package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.Sounds;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells players when the server is genuinely struggling.
 *
 * <p>WHY: without this, bad lag is indistinguishable from a bad connection. Players blame their own
 * internet, relog (which costs the server another join), or assume the server is broken and leave.
 * One honest line — "this is us, not you" — is worth a lot.
 *
 * <p><b>Deliberately hard to trigger.</b> This pack routinely produces ~4s spikes every ~19s under
 * normal chunk loading; warning on those would fire constantly and train everyone to ignore the
 * message, which is worse than saying nothing. So the warning needs the average MSPT to stay above
 * {@code lagWarnMsptThreshold} (default 250ms ≈ 4 TPS) for {@code lagWarnSustainSeconds} (default
 * 10s) continuously, and then stays quiet for {@code lagWarnCooldownSeconds} (default 5 min).
 *
 * <p>Tick time is measured from wall-clock deltas between our own tick callbacks rather than read
 * from the server's internal counters — same number, no dependency on mapping-specific accessors
 * that shift between versions, and it captures whatever is stalling the thread including work that
 * happens outside the measured tick body.
 */
public final class LagMonitor {

    private LagMonitor() {}

    /** Rolling window of tick durations, in ms. 100 ticks = 5s of history at full speed. */
    private static final int WINDOW = 100;
    private static final double[] samples = new double[WINDOW];
    private static int    filled;
    private static int    cursor;
    private static long   lastTickNanos;

    /** When the average first went bad in the current bad patch; 0 = not currently bad. */
    private static long   badSinceMs;
    private static long   lastWarnMs;

    public static void reset() {
        filled = 0; cursor = 0; lastTickNanos = 0; badSinceMs = 0; lastWarnMs = 0;
    }

    /** Called every tick from the mod's ServerTickEvent.Post listener. */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        long nowNanos = System.nanoTime();
        if (lastTickNanos == 0) { lastTickNanos = nowNanos; return; }
        double deltaMs = (nowNanos - lastTickNanos) / 1_000_000.0;
        lastTickNanos = nowNanos;

        samples[cursor] = deltaMs;
        cursor = (cursor + 1) % WINDOW;
        if (filled < WINDOW) filled++;

        try {
            if (!AuthConfig.LAG_WARN_ENABLED.get()) return;
            if (filled < WINDOW) return;                       // need a full window to judge
            if (server.getPlayerList().getPlayerCount() == 0) { badSinceMs = 0; return; }

            double avg = average();
            long now = System.currentTimeMillis();
            int threshold = AuthConfig.LAG_WARN_MSPT.get();

            if (avg < threshold) { badSinceMs = 0; return; }   // healthy — reset the sustain clock

            if (badSinceMs == 0) { badSinceMs = now; return; } // bad patch just started
            if (now - badSinceMs < AuthConfig.LAG_WARN_SUSTAIN_SECONDS.get() * 1000L) return;
            if (now - lastWarnMs < AuthConfig.LAG_WARN_COOLDOWN_SECONDS.get() * 1000L) return;

            lastWarnMs = now;
            warn(server, avg);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[LagMonitor] skipped: {}", e.toString());
        }
    }

    private static double average() {
        double sum = 0;
        for (int i = 0; i < filled; i++) sum += samples[i];
        return sum / filled;
    }

    private static void warn(MinecraftServer server, double avgMs) {
        // TPS is capped at 20 no matter how fast ticks are; below that it is 1000/mspt.
        double tps = Math.min(20.0, 1000.0 / Math.max(avgMs, 1.0));
        String tpsStr = String.format("%.1f", tps);

        CoffeesAeroAuth.LOGGER.warn(
            "[LagMonitor] Sustained lag — avg {}ms/tick (~{} TPS) over {} ticks. Players notified.",
            String.format("%.0f", avgMs), tpsStr, filled);

        Component msg = Component.literal(
            TextUtil.PREFIX + "§e⚠ Unusual lag right now §7(~" + tpsStr + " TPS)§e — "
            + "§7it's the server, not your connection. Hang tight, it should settle.");
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(msg);
            Sounds.error(p);
        }
    }
}
