package com.coffeesaerosmp.auth.obsidian;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.AuthManager;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import com.coffeesaerosmp.auth.watchdog.WatchdogEvent;
import com.coffeesaerosmp.auth.watchdog.WatchdogManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exports live server events to an Obsidian vault via ObsidianClient.
 * All operations are best-effort — exceptions are caught and logged at DEBUG level.
 * Never throws to the caller.
 */
public class ObsidianExporter {

    private static final DateTimeFormatter DATE  =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter TIME  =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter MONTH =
        DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneId.of("UTC"));

    private final ObsidianClient client;
    private final ProfileStore   store;
    private final String         vault; // e.g. "AeroSMP"

    // ── Per-server-run in-memory state ────────────────────────────────────────

    private final Map<UUID, InMemoryLog> logs          = new ConcurrentHashMap<>();
    private final Set<UUID>              todayPlayers  = ConcurrentHashMap.newKeySet();
    private final Set<String>            createdFiles  = ConcurrentHashMap.newKeySet();
    private final Set<String>            todayConns    = ConcurrentHashMap.newKeySet(); // "A:B" dedup
    private final List<WatchdogEvent>    todayAlerts   = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger          peakPlayers   = new AtomicInteger(0);

    public ObsidianExporter(ObsidianClient client, ProfileStore store, String vault) {
        this.client = client;
        this.store  = store;
        this.vault  = vault;
    }

    // ── Lifecycle hooks ───────────────────────────────────────────────────────

    public void onPlayerJoin(ServerPlayer player, PlayerProfile profile, boolean isFirstEver, String badge) {
        if (!enabled()) return;
        try {
            UUID   uuid = player.getUUID();
            String date = today();
            String time = nowTime();
            String name = profile != null ? profile.displayName : player.getGameProfile().getName();

            todayPlayers.add(uuid);

            // Track peak
            int online = CoffeesAeroAuth.AUTH_MANAGER != null
                ? CoffeesAeroAuth.AUTH_MANAGER.getAuthenticatedCount() : 1;
            peakPlayers.updateAndGet(p -> Math.max(p, online));

            // Get or init in-memory log
            InMemoryLog log = logs.computeIfAbsent(uuid, k -> new InMemoryLog(uuid, name));
            log.displayName = name;
            log.openSession(date, time);

            // Write/create player file
            if (isFirstEver) {
                ensurePlayerFile(uuid, profile, log); // PUT with full content
            } else {
                writePlayerFile(uuid, profile, log);  // PUT (overwrite with current data)
            }

            // Session file: create header if needed, then append timeline entry
            ensureSessionFile(date);
            appendTimeline(date, "- " + time + " [[" + name + "]] joined " + badge);

            // Connections: record pairs with currently online authenticated players
            if (AuthConfig.OBSIDIAN_SYNC_PLAYER_UPDATES.get()) {
                recordConnections(uuid, name, date);
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[Obsidian] onPlayerJoin error: {}", e.getMessage());
        }
    }

    public void onPlayerLeave(ServerPlayer player, PlayerProfile profile) {
        if (!enabled()) return;
        try {
            UUID   uuid = player.getUUID();
            String time = nowTime();
            InMemoryLog log = logs.get(uuid);
            if (log == null) return;

            log.closeSession(time);

            // Append timeline entry
            String name = log.displayName;
            String dur  = log.lastSessionDuration();
            appendTimeline(today(), "- " + time + " [[" + name + "]] left (" + dur + ")");

            // Rewrite player file with closed session + updated playtime
            if (AuthConfig.OBSIDIAN_SYNC_PLAYER_UPDATES.get()) {
                writePlayerFile(uuid, profile, log);
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[Obsidian] onPlayerLeave error: {}", e.getMessage());
        }
    }

    public void onAdvancement(ServerPlayer player, String advancementTitle) {
        if (!enabled()) return;
        try {
            UUID        uuid = player.getUUID();
            InMemoryLog log  = logs.get(uuid);
            if (log == null) return;
            String date = today();
            String line = "- 🏆 [[" + advancementTitle + "]] — " + date;
            log.achievements.add(line);

            appendTimeline(date, "- " + nowTime() + " [[" + log.displayName + "]] earned [[" + advancementTitle + "]]");

            PlayerProfile profile = store.get(uuid);
            writePlayerFile(uuid, profile, log);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[Obsidian] onAdvancement error: {}", e.getMessage());
        }
    }

    public void onWatchdogAlert(WatchdogEvent event, String playerName) {
        if (!enabled() || !AuthConfig.OBSIDIAN_SYNC_WATCHDOG.get()) return;
        try {
            todayAlerts.add(event);
            String month = thisMonth();
            String date  = today();
            String time  = nowTime();

            // Ensure monthly watchdog file exists
            String watchdogPath = "watchdog/alerts-" + month + ".md";
            if (createdFiles.add(watchdogPath)) {
                client.put(vault, watchdogPath,
                    "---\nmonth: " + month + "\ntags: [watchdog, security]\n---\n" +
                    "# Watchdog Alerts — " + month + "\n\n" +
                    "## " + date + "\n");
            }

            // Append alert line
            String playerRef = (playerName != null && !playerName.equals("unknown"))
                ? "player: [[" + playerName + "]]" : "";
            client.append(vault, watchdogPath,
                "- " + time + " " + event.severity().emoji() + " "
                + event.severity().label() + " — " + event.title()
                + (playerRef.isEmpty() ? "" : " — " + playerRef) + "\n");

            // Append flag to player's in-memory log
            if (playerName != null && !playerName.equals("unknown")) {
                logs.values().stream()
                    .filter(l -> playerName.equals(l.displayName))
                    .findFirst()
                    .ifPresent(log -> {
                        log.watchdogFlags.add("- " + event.severity().emoji() + " "
                            + event.severity().label() + " — " + event.title() + " — " + date);
                        UUID uid = log.uuid;
                        PlayerProfile p = store.get(uid);
                        writePlayerFile(uid, p, log);
                    });
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[Obsidian] onWatchdogAlert error: {}", e.getMessage());
        }
    }

    /** Called from CoffeesAeroAuth.onServerStopping — writes the daily devlog. */
    public void onServerStop(AuthManager authManager, WatchdogManager watchdog) {
        if (!enabled() || !AuthConfig.OBSIDIAN_SYNC_ON_STOP.get()) return;
        try {
            writeDevlog(authManager, watchdog);
            writeFullSessionFile(today());
            CoffeesAeroAuth.LOGGER.info("[Obsidian] Vault sync complete on server stop.");
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Obsidian] onServerStop error: {}", e.getMessage());
        }
    }

    /** /authmod obsidianreport — full vault sync right now. */
    public void syncAll(MinecraftServer server, AuthManager authManager, WatchdogManager watchdog) {
        if (!enabled()) return;
        try {
            // Rewrite all known player files
            logs.forEach((uuid, log) -> {
                PlayerProfile profile = store.get(uuid);
                writePlayerFile(uuid, profile, log);
            });
            writeFullSessionFile(today());
            writeDevlog(authManager, watchdog);
            CoffeesAeroAuth.LOGGER.info("[Obsidian] Manual sync complete ({} player files).", logs.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Obsidian] syncAll error: {}", e.getMessage());
        }
    }

    // ── File writers ──────────────────────────────────────────────────────────

    private void ensurePlayerFile(UUID uuid, PlayerProfile profile, InMemoryLog log) {
        if (profile == null) return;
        client.put(vault, "players/" + safe(profile.displayName) + ".md",
            buildPlayerContent(uuid, profile, log));
    }

    private void writePlayerFile(UUID uuid, PlayerProfile profile, InMemoryLog log) {
        if (profile == null || !AuthConfig.OBSIDIAN_SYNC_PLAYER_UPDATES.get()) return;
        client.put(vault, "players/" + safe(profile.displayName) + ".md",
            buildPlayerContent(uuid, profile, log));
    }

    private String buildPlayerContent(UUID uuid, PlayerProfile profile, InMemoryLog log) {
        String type    = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM ? "Verified" : "Offline";
        long totalSecs = profile.totalPlaytimeSeconds
            + (log.sessionStart > 0 ? (System.currentTimeMillis() - log.sessionStart) / 1000 : 0);
        long h = totalSecs / 3600;
        long m = (totalSecs % 3600) / 60;
        int  trustedCount = CoffeesAeroAuth.WATCHDOG != null
            ? CoffeesAeroAuth.WATCHDOG.getTrustedIpStore().getTrustedIps(uuid).size() : 0;
        String joinDate = DATE.format(Instant.ofEpochMilli(profile.joinDate));

        StringBuilder sb = new StringBuilder();
        sb.append("---\n")
          .append("uuid: ").append(profile.uuidStr).append("\n")
          .append("account_type: ").append(type).append("\n")
          .append("first_join: ").append(joinDate).append("\n")
          .append("total_playtime: ").append(h).append("h ").append(m).append("m\n")
          .append("trusted_ips: ").append(trustedCount).append("\n")
          .append("display_name: ").append(profile.displayName).append("\n")
          .append("tags: [player, ").append(type.toLowerCase()).append("]\n")
          .append("---\n\n")
          .append("# ").append(profile.displayName).append("\n\n")
          .append("## Sessions\n");
        log.sessions.forEach(s -> sb.append(s).append("\n"));
        sb.append("\n## Achievements\n");
        log.achievements.forEach(a -> sb.append(a).append("\n"));
        sb.append("\n## Watchdog Flags\n");
        log.watchdogFlags.forEach(f -> sb.append(f).append("\n"));
        return sb.toString();
    }

    private void ensureSessionFile(String date) {
        String path = "sessions/" + date + ".md";
        if (createdFiles.add(path)) {
            client.put(vault, path,
                "---\ndate: " + date + "\ntags: [session, daily]\n---\n" +
                "# Session Log — " + date + "\n\n" +
                "## Players Today\n\n" +
                "## Timeline\n");
        }
    }

    private void appendTimeline(String date, String line) {
        client.append(vault, "sessions/" + date + ".md", line + "\n");
    }

    private void writeFullSessionFile(String date) {
        String path = "sessions/" + date + ".md";
        StringBuilder sb = new StringBuilder();
        sb.append("---\n")
          .append("date: ").append(date).append("\n")
          .append("players_online: ").append(todayPlayers.size()).append("\n")
          .append("peak_players: ").append(peakPlayers.get()).append("\n")
          .append("tags: [session, daily]\n")
          .append("---\n\n")
          .append("# Session Log — ").append(date).append("\n\n")
          .append("## Players Today\n");
        todayPlayers.forEach(uuid -> {
            InMemoryLog log = logs.get(uuid);
            String name = log != null ? log.displayName : uuid.toString();
            String dur  = log != null ? log.totalDuration() : "—";
            sb.append("- [[").append(name).append("]] — ").append(dur).append("\n");
        });
        sb.append("\n## Timeline\n");
        // Re-append from memory is not possible since we used POST for timeline lines.
        // Instead leave a note that inline events were appended during the session.
        sb.append("_Timeline entries were appended live during the session._\n");
        createdFiles.add(path);
        client.put(vault, path, sb.toString());
    }

    private void writeDevlog(AuthManager authManager, WatchdogManager watchdog) {
        String date    = today();
        String session = java.time.LocalDateTime.now(ZoneId.of("UTC"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        StringBuilder sb = new StringBuilder();
        sb.append("---\n")
          .append("date: ").append(date).append("\n")
          .append("session: ").append(session).append("\n")
          .append("tags: [devlog, coffeesaeroauth]\n")
          .append("---\n\n")
          .append("# Dev Session — ").append(date).append("\n\n")
          .append("## Players Online Today\n");
        todayPlayers.forEach(uuid -> {
            InMemoryLog log = logs.get(uuid);
            String name = log != null ? log.displayName : uuid.toString();
            String dur  = log != null ? log.totalDuration() : "—";
            sb.append("- [[").append(name).append("]] — ").append(dur).append("\n");
        });
        sb.append("\n## Watchdog Events Today\n");
        synchronized (todayAlerts) {
            for (WatchdogEvent e : todayAlerts) {
                sb.append("- [").append(e.severity().label()).append("] ")
                  .append(e.title()).append(" — ")
                  .append(TIME.format(e.timestamp())).append("\n");
            }
        }
        sb.append("\n## Server Stats\n");
        sb.append("- Peak players: ").append(peakPlayers.get()).append("\n");
        if (watchdog != null) {
            sb.append("- Total logins: ").append(watchdog.getDailyLogins()).append("\n");
            sb.append("- Failed auth attempts: ").append(watchdog.getDailyFailures()).append("\n");
        }
        sb.append("\n## Next Steps\n- \n");

        client.put(vault, "devlogs/" + date + ".md", sb.toString());
    }

    private void recordConnections(UUID joiner, String joinerName, String date) {
        if (CoffeesAeroAuth.AUTH_MANAGER == null) return;
        for (UUID other : CoffeesAeroAuth.AUTH_MANAGER.getAuthenticatedUUIDs()) {
            if (other.equals(joiner)) continue;
            InMemoryLog otherLog = logs.get(other);
            if (otherLog == null) continue;
            // Canonical pair key (alphabetical) to dedup
            String a = joinerName.compareTo(otherLog.displayName) < 0 ? joinerName : otherLog.displayName;
            String b = joinerName.compareTo(otherLog.displayName) < 0 ? otherLog.displayName : joinerName;
            String key = a + ":" + b + ":" + date;
            if (todayConns.add(key)) {
                ensureConnectionsFile();
                client.append(vault, "graph-data/connections.md",
                    "- [[" + a + "]] played alongside [[" + b + "]] on [[" + date + "]]\n");
            }
        }
    }

    private void ensureConnectionsFile() {
        if (createdFiles.add("graph-data/connections.md")) {
            client.put(vault, "graph-data/connections.md",
                "---\ntags: [graph, social]\n---\n# Player Connections\n\n");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean enabled() {
        return AuthConfig.OBSIDIAN_ENABLED.get() && client != null;
    }

    private static String today()     { return DATE.format(Instant.now()); }
    private static String nowTime()   { return TIME.format(Instant.now()); }
    private static String thisMonth() { return MONTH.format(Instant.now()); }

    /** Strips characters that would break Obsidian file paths. */
    private static String safe(String name) {
        return name == null ? "unknown" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // ── InMemoryLog ───────────────────────────────────────────────────────────

    static class InMemoryLog {
        final UUID uuid;
        String displayName;
        final List<String> sessions      = new ArrayList<>();
        final List<String> achievements  = new ArrayList<>();
        final List<String> watchdogFlags = new ArrayList<>();
        volatile long sessionStart = 0; // epoch ms; 0 = not in session

        InMemoryLog(UUID uuid, String displayName) {
            this.uuid        = uuid;
            this.displayName = displayName;
        }

        void openSession(String date, String time) {
            sessionStart = System.currentTimeMillis();
            sessions.add("- [[" + date + "]] — joined " + time + ", **[in progress]**");
        }

        void closeSession(String leaveTime) {
            if (sessions.isEmpty() || sessionStart == 0) return;
            long ms  = System.currentTimeMillis() - sessionStart;
            String d = formatDuration(ms);
            int last = sessions.size() - 1;
            sessions.set(last, sessions.get(last)
                .replace(", **[in progress]**", ", left " + leaveTime + " (" + d + ")"));
            sessionStart = 0;
        }

        String lastSessionDuration() {
            if (sessionStart == 0) return "—";
            return formatDuration(System.currentTimeMillis() - sessionStart);
        }

        String totalDuration() {
            long ms = sessionStart > 0 ? System.currentTimeMillis() - sessionStart : 0;
            return formatDuration(ms);
        }

        private static String formatDuration(long ms) {
            long secs = ms / 1000;
            long h    = secs / 3600;
            long m    = (secs % 3600) / 60;
            return h > 0 ? h + "h " + m + "m" : m + "m";
        }
    }
}
