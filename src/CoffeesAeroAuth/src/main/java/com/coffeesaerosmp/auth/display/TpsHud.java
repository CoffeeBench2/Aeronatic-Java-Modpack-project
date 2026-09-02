package com.coffeesaerosmp.auth.display;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.watchdog.TickStats;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An always-on TPS/MSPT bar for ops — no command needed to see it.
 *
 * <p>A boss bar rather than the action bar or the sidebar: the action bar is transient and fights
 * with every other message, and the sidebar already carries player stats. The boss bar sits at the
 * top of the screen permanently, is server-driven, and needs no client mod.</p>
 *
 * <p><b>Opt-OUT, not opt-in.</b> Ops see it by default; the persisted set holds the ops who have
 * turned it off. That is what makes it "no commands" — a new op sees the numbers without knowing a
 * command exists.</p>
 *
 * <p>Numbers come from {@link TickStats}, which measures Pre→Post (real tick work) rather than the
 * interval between ticks. The interval floors at ~50 ms whenever the server is keeping up, which is
 * why an interval-based MSPT can never agree with spark.</p>
 */
public final class TpsHud {

    private static final ServerBossEvent BAR = new ServerBossEvent(
        Component.literal("§7⚡ measuring…"),
        BossEvent.BossBarColor.GREEN,
        BossEvent.BossBarOverlay.PROGRESS);

    /** Ops who have turned the bar OFF. Everyone else with permission sees it. */
    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();
    private static volatile Path file;

    private static int ticks;

    private TpsHud() {}

    public static void initialize(Path dataDir) {
        file = dataDir.resolve("tps_hud_off.json");
        HIDDEN.clear();
        BAR.setProgress(0f);
        if (!Files.exists(file)) return;
        try {
            JsonArray a = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            a.forEach(e -> {
                try { HIDDEN.add(UUID.fromString(e.getAsString())); }
                catch (IllegalArgumentException ignored) {}
            });
            CoffeesAeroAuth.LOGGER.info("[TpsHud] {} op(s) have the TPS bar disabled.", HIDDEN.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[TpsHud] tps_hud_off.json load failed: {}", e.getMessage());
        }
    }

    /** True if this player currently WANTS the bar (they are an op and have not turned it off). */
    private static boolean wants(ServerPlayer p) {
        return p.hasPermissions(2) && !HIDDEN.contains(p.getUUID());
    }

    /** Toggles for one op and persists. Returns the NEW state: true = bar visible. */
    public static boolean toggle(ServerPlayer player) {
        UUID id = player.getUUID();
        boolean visible;
        if (HIDDEN.remove(id)) visible = true;
        else { HIDDEN.add(id); visible = false; }
        if (!visible) BAR.removePlayer(player);
        persist();
        return visible;
    }

    /**
     * Call every tick. Throttled internally to ~2 updates/second — a HUD that reports lag must not
     * add any, so the common path is an increment and a compare.
     */
    public static void onServerTick(MinecraftServer server) {
        if (server == null) return;
        if (++ticks % 10 != 0) return;
        if (!AuthConfig.TPS_HUD_ENABLED.get()) {
            if (!BAR.getPlayers().isEmpty()) BAR.removeAllPlayers();
            return;
        }

        try {
            TickStats.Reading r = TickStats.read();
            BAR.setName(Component.literal(
                TpsHudFormat.title(r.msptMean(), r.msptWorst(), r.tps(), r.seconds())));
            BAR.setProgress(TpsHudFormat.progress(r.msptMean()));
            BAR.setColor(switch (TpsHudFormat.severity(r.msptMean())) {
                case GOOD -> BossEvent.BossBarColor.GREEN;
                case WARN -> BossEvent.BossBarColor.YELLOW;
                case BAD  -> BossEvent.BossBarColor.RED;
            });

            // Reconcile viewers. addPlayer/removePlayer are idempotent, so this self-heals when an
            // op is promoted, demoted, joins or leaves — no join/leave hooks to keep in sync.
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (wants(p)) BAR.addPlayer(p); else BAR.removePlayer(p);
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[TpsHud] skipped: {}", e.toString());
        }
    }

    private static void persist() {
        Path f = file;
        if (f == null) return;
        JsonArray a = new JsonArray();
        HIDDEN.forEach(u -> a.add(u.toString()));
        String json = a.toString();
        AsyncIo.submit(() -> {
            try { Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[TpsHud] save failed: {}", e.getMessage()); }
        });
    }
}
