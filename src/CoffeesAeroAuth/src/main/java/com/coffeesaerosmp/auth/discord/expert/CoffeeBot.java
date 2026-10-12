package com.coffeesaerosmp.auth.discord.expert;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.display.HiddenOps;
import com.coffeesaerosmp.auth.leveling.LevelFormula;
import com.coffeesaerosmp.auth.leveling.LevelService;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.sidebar.SidebarManager;
import com.coffeesaerosmp.auth.watchdog.LagMonitor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Wires {@link CoffeeBotExpert} to the running server: live facts, the knowledge-base files, the unanswered log,
 * and its own worker thread so answering never runs on the Discord sender thread (heartbeats) or the server
 * thread (ticks). Facts are read on the server thread via {@code server.execute} with a short timeout, and the
 * last good snapshot is reused if the server is mid-stall — the bot still answers during lag.
 */
public final class CoffeeBot {

    private static volatile CoffeeBotExpert expert;
    private static volatile KnowledgeBase kb;
    private static volatile MinecraftServer server;
    private static volatile Path overridePath, unansweredLog;
    private static volatile Map<String, String> lastGlobal = Map.of();
    private static volatile long lastGlobalMs;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CoffeeBot-Expert");
        t.setDaemon(true);
        return t;
    });

    private CoffeeBot() {}

    public static void init(MinecraftServer srv, Path dataDir) {
        server = srv;
        overridePath = srv.getServerDirectory().resolve("config").resolve("coffeebot-kb.json");
        unansweredLog = dataDir.resolve("coffeebot-unanswered.log");
        String err = reload();
        if (err != null) CoffeesAeroAuth.LOGGER.error("[CoffeeBot] knowledge base failed to load: {}", err);
    }

    /** @return null on success, else the error (the previous knowledge base stays in use). */
    public static String reload() {
        try {
            KnowledgeBase loaded = KnowledgeBase.load(overridePath);
            kb = loaded;
            if (expert == null) {
                CoffeeBotExpert e = new CoffeeBotExpert(() -> kb, new ServerFacts(), wiki());
                e.setUnansweredSink(CoffeeBot::logUnanswered);
                expert = e;
            }
            CoffeesAeroAuth.LOGGER.info("[CoffeeBot] knowledge base loaded: {} topics{}", loaded.size(),
                overridePath != null && Files.isRegularFile(overridePath) ? " (with " + overridePath.getFileName() + ")" : "");
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static boolean active() {
        try {
            return expert != null && AuthConfig.COFFEEBOT_EXPERT_ENABLED.get() && !LobbyHandoff.isLobbyRole();
        } catch (Throwable t) {
            return false;
        }
    }

    public static int topicCount() { return kb == null ? 0 : kb.size(); }

    public static java.util.List<String> unanswered() {
        return expert == null ? java.util.List.of() : expert.unanswered();
    }

    /** Answer off-thread. {@code onReply} gets the text, or null when it's small talk for BotBanter. */
    public static void ask(String discordUserId, String content, java.util.function.Consumer<String> onReply) {
        CoffeeBotExpert e = expert;
        if (e == null) { onReply.accept(null); return; }
        WORKER.execute(() -> {
            String text = null;
            try {
                var r = e.answer(discordUserId, content);
                text = r == null ? null : r.text();
            } catch (Throwable t) {
                CoffeesAeroAuth.LOGGER.warn("[CoffeeBot] answer failed: {}", t.toString());
            }
            try { onReply.accept(text); } catch (Throwable ignored) { }
        });
    }

    private static String wiki() {
        try { return AuthConfig.COFFEEBOT_WIKI_URL.get(); } catch (Throwable t) { return "https://coffeebench2.github.io/aerosmp-wiki"; }
    }

    private static void logUnanswered(String q) {
        Path p = unansweredLog;
        if (p == null) return;
        String line = Instant.now() + "  " + q.replace('\n', ' ') + System.lineSeparator();
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) { }
        });
        CoffeesAeroAuth.LOGGER.info("[CoffeeBot] unanswered: {}", q);
    }

    /** Runs {@code task} on the server thread and waits briefly; null if the server is busy or gone. */
    private static <T> T onServer(java.util.function.Supplier<T> task) {
        MinecraftServer s = server;
        if (s == null || !s.isRunning()) return null;
        try {
            return CompletableFuture.supplyAsync(task, s).get(1500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    static String verdict(double tps) {
        if (tps >= 19) return "full speed";
        if (tps >= 15) return "a little behind";
        if (tps >= 10) return "under heavy load";
        return "struggling right now";
    }

    private static final class ServerFacts implements CoffeeBotExpert.FactSource {

        @Override
        public Map<String, String> global() {
            long now = System.currentTimeMillis();
            if (now - lastGlobalMs < 5_000 && !lastGlobal.isEmpty()) return lastGlobal;
            Map<String, String> m = new HashMap<>();
            try {
                m.put("address", AuthConfig.COFFEEBOT_JOIN_ADDRESS.get());
                m.put("discord", AuthConfig.DISCORD_INVITE_URL.get());
                m.put("wiki", wiki());
            } catch (Throwable ignored) { }
            double tps = LagMonitor.recentTps;
            m.put("tps", String.format(Locale.ROOT, "%.1f", Math.min(20.0, tps)));
            m.put("tpsVerdict", verdict(tps));
            long sessionMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
            m.put("session", (sessionMs / 3_600_000) + "h " + ((sessionMs % 3_600_000) / 60_000) + "m");
            try {
                var since = java.time.LocalDate.parse(AuthConfig.DISCORD_SMP_LAUNCH_DATE.get().trim());
                m.put("uptimeDays", String.valueOf(java.time.temporal.ChronoUnit.DAYS.between(since,
                    java.time.LocalDate.now(java.time.ZoneOffset.UTC))));
            } catch (Throwable ignored) { }
            int[] counts = onServer(() -> new int[]{
                server.getPlayerList().getPlayerCount() - HiddenOps.hiddenCount(server.getPlayerList().getPlayers()),
                server.getPlayerList().getMaxPlayers()});
            if (counts != null) {
                m.put("status", "online");
                m.put("online", String.valueOf(counts[0]));
                m.put("max", String.valueOf(counts[1]));
            } else if (!lastGlobal.isEmpty()) {
                // Server busy (a stall): reuse the last counts rather than claim it's down.
                for (String k : new String[]{"status", "online", "max"}) m.put(k, lastGlobal.getOrDefault(k, "?"));
            } else {
                m.put("status", "online");
                m.put("online", "?");
                m.put("max", "?");
            }
            lastGlobal = Map.copyOf(m);
            lastGlobalMs = now;
            return lastGlobal;
        }

        @Override
        public Map<String, String> personal(String discordUserId) {
            var store = CoffeesAeroAuth.PROFILE_STORE;
            if (store == null || discordUserId == null || discordUserId.isBlank()) return null;
            return onServer(() -> {
                PlayerProfile p = store.findByDiscordId(discordUserId);
                if (p == null) return null;
                Map<String, String> m = new HashMap<>();
                String name = p.displayName != null && !p.displayName.isBlank() ? p.displayName : p.username;
                long lifetime = SidebarManager.playtimeSecondsOf(p);
                long season = Math.max(0L, lifetime - p.seasonStartPlaytime);
                m.put("me.name", name == null ? "you" : name);
                m.put("me.seasonHours", String.format(Locale.ROOT, "%.1f", season / 3600.0));
                m.put("me.totalHours", String.format(Locale.ROOT, "%.1f", lifetime / 3600.0));
                ServerPlayer online = p.getUUID() == null ? null : server.getPlayerList().getPlayer(p.getUUID());
                if (online != null && LevelService.enabled()) {
                    var params = LevelService.params();
                    int level = LevelFormula.level(LevelService.xpOf(online), params);
                    m.put("me.levelLine", "Level **" + level + "**, with **" + LevelFormula.claims(level, params)
                        + "** claimable chunks.");
                } else {
                    m.put("me.levelLine", "Your level shows on the sidebar while you're in game (I can only work it out while you're online).");
                }
                return m;
            });
        }
    }
}
