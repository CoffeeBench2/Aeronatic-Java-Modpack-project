package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import com.coffeesaerosmp.auth.discord.AlertFormatter;
import com.coffeesaerosmp.auth.discord.WebhookQueue;
import com.coffeesaerosmp.auth.util.NetUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WatchdogManager {

    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));

    private final MinecraftServer server;
    private final IpBanManager    ipBans;
    private final TrustedIpStore  trustedIps;
    private final AuditLogger     auditLog;
    private final AuditLogger     watchdogLog;
    private final WebhookQueue    webhookQueue;

    // ── Threat tracking ───────────────────────────────────────────────────────
    private record SubnetFailure(UUID uuid, long ts) {}
    private final Map<String, Deque<SubnetFailure>> subnetFailures  = new ConcurrentHashMap<>();
    private final Map<String, UUID>                 usernameUUIDs   = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>>            commandTimes    = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>>            adminCmdTimes   = new ConcurrentHashMap<>();
    private final Map<UUID, Long>                   lockedAdmins    = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger>          movementOffenses = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger>          preAuthBlocked  = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger>          ipHopCounts     = new ConcurrentHashMap<>();
    private volatile boolean                        joinBlocked     = false;

    // Normalized premium display names for lookalike detection
    private final Map<String, String> normalizedPremiumNames = new ConcurrentHashMap<>(); // normalized → original

    // ── Daily stats ───────────────────────────────────────────────────────────
    private final AtomicInteger dailyLogins      = new AtomicInteger();
    private final AtomicInteger dailyFailures    = new AtomicInteger();
    private final AtomicInteger dailyAdminActs   = new AtomicInteger();
    private final Set<String>   dailyFlaggedIps  = ConcurrentHashMap.newKeySet();
    private final Set<String>   dailyAnomalies   = ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService scheduler;

    public WatchdogManager(MinecraftServer server, IpBanManager ipBans,
                            TrustedIpStore trustedIps, AuditLogger auditLog,
                            AuditLogger watchdogLog, WebhookQueue webhookQueue) {
        this.server       = server;
        this.ipBans       = ipBans;
        this.trustedIps   = trustedIps;
        this.auditLog     = auditLog;
        this.watchdogLog  = watchdogLog;
        this.webhookQueue = webhookQueue;
    }

    public void start(ProfileStore profileStore) {
        scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "AeroAuth-Watchdog"); t.setDaemon(true); return t;
        });
        auditLog.initialize();
        watchdogLog.initialize();

        // Build normalized premium name index from all profiles
        loadPremiumNames(profileStore);

        // Audit checksum every 60s
        scheduler.scheduleAtFixedRate(this::runAuditCheck, 60, 60, TimeUnit.SECONDS);
        // Server health every 10s
        scheduler.scheduleAtFixedRate(this::checkServerHealth, 10, 10, TimeUnit.SECONDS);
        // Reset pre-auth packet counters every second
        scheduler.scheduleAtFixedRate(preAuthBlocked::clear, 1, 1, TimeUnit.SECONDS);
        // Schedule daily digest
        scheduleDigest();
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ── Premium name index ────────────────────────────────────────────────────

    private void loadPremiumNames(ProfileStore store) {
        for (com.coffeesaerosmp.auth.db.PlayerProfile p : store.getAll()) {
            if (p.getAccountType() == com.coffeesaerosmp.auth.db.PlayerProfile.AccountType.PREMIUM
                    && p.displayName != null) {
                normalizedPremiumNames.put(normalizeLookalike(p.displayName), p.displayName);
            }
        }
    }

    public void addPremiumName(String displayName) {
        if (displayName != null) normalizedPremiumNames.put(normalizeLookalike(displayName), displayName);
    }

    public void removePremiumName(String displayName) {
        if (displayName != null) normalizedPremiumNames.remove(normalizeLookalike(displayName));
    }

    /** True if the given name exactly matches a known premium player's display name (case-insensitive). */
    public boolean isPremiumDisplayName(String name) {
        if (name == null) return false;
        for (String premium : normalizedPremiumNames.values()) {
            if (premium.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ── Threat Detection ─────────────────────────────────────────────────────

    /** Called by PlayerAuthEvents before auth setup. Checks IP ban, UUID switch, lookalike. */
    public boolean checkJoin(ServerPlayer player, boolean isOffline) {
        String ip     = NetUtil.getPlayerIP(player);
        UUID   uuid   = player.getUUID();
        String mcName = player.getGameProfile().getName();

        // 1. IP ban check
        if (ipBans.isBanned(ip)) {
            player.connection.disconnect(Component.literal("§cYour IP is temporarily banned from this server."));
            auditLog.log("BLOCK", "Banned IP attempted join: " + ip + " as " + mcName);
            return true; // blocked
        }

        // 2. Join blocking (too many unauth players)
        if (joinBlocked) {
            player.connection.disconnect(Component.literal("§cServer is temporarily not accepting new connections. Try again shortly."));
            return true;
        }

        // 3. UUID switch detection (offline only — same name, different UUID vs last known)
        if (isOffline) {
            UUID prev = usernameUUIDs.put(mcName, uuid);
            if (prev != null && !prev.equals(uuid)) {
                alert(WatchdogEvent.of(Severity.CRITICAL, "UUID Switch Detected",
                    "Instant kick + session wipe",
                    "Player", mcName, "Old UUID", prev.toString(), "New UUID", uuid.toString(), "IP", ip));
                player.connection.disconnect(Component.literal(
                    "§cSecurity violation: UUID mismatch for username " + mcName + ". Contact an admin."));
                auditLog.log("UUID_SWITCH", "mcName=" + mcName + " old=" + prev + " new=" + uuid + " ip=" + ip);
                return true;
            }

            // 4. Lookalike check
            String normalized = normalizeLookalike(mcName);
            String impersonated = normalizedPremiumNames.get(normalized);
            if (impersonated != null && !impersonated.equalsIgnoreCase(mcName)) {
                alert(WatchdogEvent.of(Severity.HIGH, "Lookalike Username Blocked",
                    "Player kicked on join",
                    "Offline Username", mcName, "Looks like", impersonated, "IP", ip));
                player.connection.disconnect(Component.literal(
                    "§cYour username §e" + mcName + "§c is visually similar to a verified player. " +
                    "Please change your Minecraft username."));
                auditLog.log("LOOKALIKE", "blocked=" + mcName + " impersonates=" + impersonated + " ip=" + ip);
                return true;
            }
        }
        return false;
    }

    /** Record a failed login attempt. Checks for login storm. */
    public void recordFailedLogin(UUID uuid, String ip, String mcName) {
        dailyFailures.incrementAndGet();
        dailyFlaggedIps.add(ip);
        auditLog.log("LOGIN_FAIL", "uuid=" + uuid + " ip=" + ip + " name=" + mcName);

        String subnet = NetUtil.subnetOf(ip);
        Deque<SubnetFailure> q = subnetFailures.computeIfAbsent(subnet, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            q.addLast(new SubnetFailure(uuid, now));
            long windowMs = AuthConfig.LOGIN_STORM_WINDOW_SECONDS.get() * 1000L;
            q.removeIf(f -> now - f.ts() > windowMs);
            checkLoginStorm(subnet, q);
        }
    }

    /** Audit-log a one-time display-name change (request / approval / rejection). */
    public void recordNameChange(String detail) {
        auditLog.log("NAME_CHANGE", detail);
    }

    /** Audit-log a moderation action taken from Discord (ban / unban via watchdog buttons). */
    public void recordModAction(String detail) {
        auditLog.log("MOD_ACTION", detail);
    }

    /** Record a successful login. Handles trusted IP logic. */
    public void recordSuccessfulLogin(UUID uuid, String ip, String displayName, boolean isPremium) {
        dailyLogins.incrementAndGet();
        auditLog.log("LOGIN_OK", "uuid=" + uuid + " ip=" + ip + " name=" + displayName
            + " type=" + (isPremium ? "PREMIUM" : "OFFLINE"));

        if (!isPremium) {
            boolean known = trustedIps.isKnownIp(uuid, ip);
            if (!known) {
                alert(WatchdogEvent.of(Severity.MEDIUM, "New IP Login (Offline Player)",
                    "IP added to trusted list",
                    "Player", displayName, "IP", ip));
                dailyAnomalies.add("New IP login: " + displayName);
            }
            trustedIps.addTrustedIp(uuid, ip);
        }
    }

    /** Called on failed login attempt for an offline player with no trusted IPs or unknown IP. */
    public void recordFailedLoginFromUnknownIp(UUID uuid, String ip, String mcName) {
        boolean knownIp = trustedIps.isKnownIp(uuid, ip);
        if (!knownIp && trustedIps.getTrustedIps(uuid).size() > 0) {
            // Has existing trusted IPs but this one is unknown — possible takeover
            alert(WatchdogEvent.of(Severity.HIGH, "Failed Login from Unknown IP",
                "Possible account takeover — ops notified",
                "Player", mcName, "IP", ip));
            dailyAnomalies.add("Possible takeover: " + mcName + " from " + ip);
        }
    }

    /** Record an admin command. Checks rate limit and quiet hours. */
    public void recordAdminCommand(ServerPlayer op, String command) {
        UUID  uuid = op.getUUID();
        String name = op.getGameProfile().getName();
        long  now  = System.currentTimeMillis();
        dailyAdminActs.incrementAndGet();

        // Log to watchdog channel
        String ts = TS_FMT.format(Instant.now());
        String target = command.contains("<") ? command.replaceAll("<[^>]+>", "?") : "—";
        String watchdogWh = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (!watchdogWh.isBlank()) {
            webhookQueue.enqueue(watchdogWh,
                AlertFormatter.adminAction(name, command, target, ts),
                Severity.MEDIUM);
        }

        // Quiet hours check
        if (isQuietHours()) {
            alert(WatchdogEvent.of(Severity.MEDIUM, "Admin Command During Quiet Hours",
                "Owner notified",
                "Op", name, "Command", command, "Time", ts));
        }

        // Rate limit
        if (lockedAdmins.getOrDefault(uuid, 0L) > now) {
            op.sendSystemMessage(Component.literal("§c[Watchdog] Admin commands are temporarily locked. Too many recent actions."));
            return;
        }

        Deque<Long> times = adminCmdTimes.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
            long windowMs = AuthConfig.ADMIN_CMD_WINDOW_SECONDS.get() * 1000L;
            times.removeIf(t -> now - t > windowMs);
            if (times.size() > AuthConfig.ADMIN_CMD_LIMIT.get()) {
                long lockUntil = now + 5 * 60_000L; // 5 min lock
                lockedAdmins.put(uuid, lockUntil);
                alert(WatchdogEvent.of(Severity.CRITICAL, "Admin Abuse Detected",
                    "Admin commands locked for 5 minutes",
                    "Op", name, "Commands in window", String.valueOf(times.size())));
                op.sendSystemMessage(Component.literal("§c[Watchdog] Admin commands locked for 5 minutes due to excessive use."));
            }
        }
    }

    public boolean isAdminLocked(UUID uuid) {
        Long lockUntil = lockedAdmins.get(uuid);
        if (lockUntil == null) return false;
        if (lockUntil <= System.currentTimeMillis()) { lockedAdmins.remove(uuid); return false; }
        return true;
    }

    /** Called from WatchdogEvents every tick for authenticated players. */
    public void checkMovement(ServerPlayer player) {
        double speed = player.getDeltaMovement().length();
        double maxSpeed = AuthConfig.MAX_MOVEMENT_SPEED.get();
        if (speed > maxSpeed) {
            double[] frozen = CoffeesAeroAuth.AUTH_MANAGER != null
                ? CoffeesAeroAuth.AUTH_MANAGER.getFrozenPositionIfPresent(player.getUUID()) : null;
            // Teleport back (frozen pos if available, else just zero velocity)
            player.setDeltaMovement(0, 0, 0);

            int offenses = movementOffenses
                .computeIfAbsent(player.getUUID(), k -> new AtomicInteger()).incrementAndGet();
            if (offenses >= 3 && offenses % 5 == 0) {
                alert(WatchdogEvent.of(Severity.MEDIUM, "Impossible Movement (Repeat Offender)",
                    "Velocity zeroed",
                    "Player", player.getGameProfile().getName(),
                    "Speed", String.format("%.2f", speed) + " b/t",
                    "Offenses", String.valueOf(offenses)));
                dailyAnomalies.add("Movement hack: " + player.getGameProfile().getName());
            }
        }
    }

    /** Called from WatchdogEvents when any command fires. */
    public boolean checkCommandVelocity(ServerPlayer player) {
        UUID  uuid = player.getUUID();
        long  now  = System.currentTimeMillis();
        Deque<Long> times = commandTimes.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(now);
            times.removeIf(t -> now - t > 1000);
            int count = times.size();
            int throttle = AuthConfig.CMD_VELOCITY_THROTTLE.get();
            int alert    = AuthConfig.CMD_VELOCITY_ALERT.get();
            if (count > alert) {
                alert(WatchdogEvent.of(Severity.MEDIUM, "Command Velocity Alert",
                    "Commands throttled",
                    "Player", player.getGameProfile().getName(), "Commands/sec", String.valueOf(count)));
                return false; // block command
            }
            if (count > throttle) return false; // silently throttle
        }
        return true; // allow command
    }

    /** Record a pre-auth packet/block event. Kicks + bans on flood. */
    public void recordPreAuthBlock(ServerPlayer player) {
        UUID   uuid = player.getUUID();
        String ip   = NetUtil.getPlayerIP(player);
        int count = preAuthBlocked.computeIfAbsent(uuid, k -> new AtomicInteger()).incrementAndGet();
        int threshold = AuthConfig.PRE_AUTH_PACKET_THRESHOLD.get();
        if (count >= threshold) {
            long banMs = AuthConfig.PRE_AUTH_BAN_MINUTES.get() * 60_000L;
            ipBans.ban(ip, banMs);
            alert(WatchdogEvent.of(Severity.HIGH, "Pre-Auth Packet Flood",
                "IP banned for " + AuthConfig.PRE_AUTH_BAN_MINUTES.get() + " minutes",
                "Player", player.getGameProfile().getName(), "IP", ip,
                "Blocked packets/sec", String.valueOf(count)));
            player.connection.disconnect(Component.literal("§cExcessive activity before authentication."));
        }
    }

    // ── Server Health ─────────────────────────────────────────────────────────

    private void checkServerHealth() {
        if (CoffeesAeroAuth.AUTH_MANAGER == null) return;
        int total    = server.getPlayerList().getPlayerCount();
        int maxSlots = server.getPlayerList().getMaxPlayers();
        if (total == 0 || maxSlots == 0) return;
        int unauth   = CoffeesAeroAuth.AUTH_MANAGER.getUnauthenticatedCount();
        double ratio = (double) unauth / maxSlots;
        double maxRatio = AuthConfig.MAX_UNAUTH_RATIO.get();
        if (ratio >= maxRatio && !joinBlocked) {
            joinBlocked = true;
            alert(WatchdogEvent.of(Severity.HIGH, "Unauthenticated Session Drain",
                "New joins temporarily blocked",
                "Unauth players", String.valueOf(unauth),
                "Ratio", String.format("%.0f%%", ratio * 100),
                "Threshold", String.format("%.0f%%", maxRatio * 100)));
        } else if (ratio < maxRatio * 0.5 && joinBlocked) {
            joinBlocked = false; // unblock when it drops to half threshold
        }
    }

    // ── Audit checksum ────────────────────────────────────────────────────────

    private void runAuditCheck() {
        if (!auditLog.verifyAndBackup()) {
            auditLog.restoreFromBackup();
            alert(WatchdogEvent.of(Severity.CRITICAL, "Audit Log Tampered",
                "Backup restored — investigate immediately",
                "File", "audit.log"));
            dailyAnomalies.add("AUDIT LOG TAMPERED");
        }
        if (!watchdogLog.verifyAndBackup()) {
            watchdogLog.restoreFromBackup();
            alert(WatchdogEvent.of(Severity.CRITICAL, "Watchdog Log Tampered",
                "Backup restored",
                "File", "watchdog.log"));
        }
    }

    // ── Alerts ────────────────────────────────────────────────────────────────

    public void alert(WatchdogEvent event) {
        // 1. Log to watchdog.log
        StringBuilder sb = new StringBuilder(event.title());
        event.fields().forEach((k, v) -> sb.append(" | ").append(k).append("=").append(v));
        sb.append(" | Action=").append(event.actionTaken());
        watchdogLog.log(event.severity().label(), sb.toString());

        // 2. In-game op alert for HIGH+
        if (event.severity().ordinal() >= Severity.HIGH.ordinal()) {
            String msg = "§c[Watchdog " + event.severity().label() + "] §r" + event.title()
                + " | " + event.actionTaken();
            server.execute(() -> {
                for (ServerPlayer op : server.getPlayerList().getPlayers()) {
                    if (op.hasPermissions(2)) op.sendSystemMessage(Component.literal(msg));
                }
            });
        }

        // 3. Discord — interactive bot alert (Ban/Unban buttons) for actionable HIGH+ events;
        //    otherwise fall back to the plain watchdog webhook (webhooks can't carry components).
        boolean postedActionable = event.severity().ordinal() >= Severity.HIGH.ordinal()
            && postActionableAlert(event);
        if (!postedActionable) {
            String watchdogUrl = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
            if (!watchdogUrl.isBlank()) {
                webhookQueue.enqueue(watchdogUrl, AlertFormatter.watchdogAlert(event), event.severity());
            }
        }

        // 4. Obsidian vault alert
        if (CoffeesAeroAuth.OBSIDIAN_EXPORTER != null) {
            String playerName = event.fields().getOrDefault("Player", "unknown");
            CoffeesAeroAuth.OBSIDIAN_EXPORTER.onWatchdogAlert(event, playerName);
        }
    }

    /**
     * For HIGH/CRITICAL alerts carrying an IP or subnet, posts an interactive bot message to the watchdog
     * channel with Ban/Unban buttons (which webhooks cannot do). Returns true if it posted, so the caller
     * can skip the plain webhook. Falls back (returns false) if the bot/channel isn't configured.
     */
    private boolean postActionableAlert(WatchdogEvent event) {
        var rest = CoffeesAeroAuth.DISCORD_REST;
        String channel = AuthConfig.DISCORD_WATCHDOG_CHANNEL_ID.get();
        if (rest == null || !rest.isConfigured() || channel == null || channel.isBlank()) return false;

        String ip     = event.fields().get("IP");
        String subnet = event.fields().get("Subnet");   // e.g. "1.2.3.*"
        boolean hasIp     = ip != null && !ip.isBlank();
        boolean hasSubnet = subnet != null && !subnet.isBlank();
        if (!hasIp && !hasSubnet) return false;

        JsonArray buttons = new JsonArray();
        if (hasIp) {
            if (ipBans.isBanned(ip)) buttons.add(button("✅ Unban " + ip, "wdunban:" + ip, 3));
            else                     buttons.add(button("⛔ Ban IP 1h",    "wdban:" + ip,   4));
        }
        if (hasSubnet) {
            String prefix = subnet.replace(".*", "").trim();
            buttons.add(button("✅ Unban subnet " + prefix + ".*", "wdunbansub:" + prefix, 3));
        }
        if (buttons.size() == 0) return false;

        JsonObject embed = new JsonObject();
        embed.addProperty("title", event.severity().emoji() + " " + event.severity().label() + " — " + event.title());
        embed.addProperty("color", event.severity().color());
        JsonArray fields = new JsonArray();
        event.fields().forEach((k, v) -> {
            JsonObject f = new JsonObject();
            f.addProperty("name", k);
            f.addProperty("value", v == null || v.isBlank() ? "—" : v);
            f.addProperty("inline", true);
            fields.add(f);
        });
        if (event.actionTaken() != null && !event.actionTaken().isBlank()) {
            JsonObject f = new JsonObject();
            f.addProperty("name", "Action Taken");
            f.addProperty("value", event.actionTaken());
            f.addProperty("inline", false);
            fields.add(f);
        }
        embed.add("fields", fields);
        embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(event.timestamp()));

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        row.add("components", buttons);
        JsonArray rows = new JsonArray();
        rows.add(row);

        JsonObject body = new JsonObject();
        body.add("embeds", embeds);
        body.add("components", rows);
        rest.postMessage(channel, body.toString());
        return true;
    }

    private static JsonObject button(String label, String customId, int style) {
        JsonObject b = new JsonObject();
        b.addProperty("type", 2);          // button
        b.addProperty("style", style);     // 3 success, 4 danger, 2 secondary
        b.addProperty("label", label);
        b.addProperty("custom_id", customId);
        return b;
    }

    // ── Daily Digest ──────────────────────────────────────────────────────────

    private void scheduleDigest() {
        try {
            LocalTime target = LocalTime.parse(AuthConfig.DISCORD_DIGEST_TIME.get());
            LocalDateTime now  = LocalDateTime.now();
            LocalDateTime next = now.toLocalDate().atTime(target);
            if (!next.isAfter(now)) next = next.plusDays(1);
            long delayMs = Duration.between(now, next).toMillis();
            scheduler.schedule(() -> { sendDailyDigest(); scheduleDigest(); }, delayMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Watchdog] Invalid digest time: {}", AuthConfig.DISCORD_DIGEST_TIME.get());
        }
    }

    private void sendDailyDigest() {
        String url = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (url.isBlank()) return;
        String anomalies = dailyAnomalies.isEmpty() ? "None" : String.join(", ", dailyAnomalies);
        webhookQueue.enqueue(url, AlertFormatter.dailyDigest(
            dailyLogins.getAndSet(0), dailyFailures.getAndSet(0),
            dailyFlaggedIps.size(), dailyAdminActs.getAndSet(0), anomalies),
            Severity.MEDIUM);
        dailyFlaggedIps.clear();
        dailyAnomalies.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isIpBanned(String ip) { return ipBans.isBanned(ip); }
    public boolean isJoinBlocked()       { return joinBlocked; }
    public TrustedIpStore getTrustedIpStore() { return trustedIps; }
    public IpBanManager   getIpBanManager()   { return ipBans; }
    public int getDailyLogins()   { return dailyLogins.get(); }
    public int getDailyFailures() { return dailyFailures.get(); }

    private boolean isQuietHours() {
        try {
            LocalTime now   = LocalTime.now();
            LocalTime start = LocalTime.parse(AuthConfig.QUIET_HOURS_START.get());
            LocalTime end   = LocalTime.parse(AuthConfig.QUIET_HOURS_END.get());
            if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
            return !now.isBefore(start) || now.isBefore(end); // wraps midnight
        } catch (Exception e) { return false; }
    }

    private void checkLoginStorm(String subnet, Deque<SubnetFailure> q) {
        long windowMs    = AuthConfig.LOGIN_STORM_WINDOW_SECONDS.get() * 1000L;
        long now         = System.currentTimeMillis();
        Set<UUID> accts  = new HashSet<>();
        int count        = 0;
        for (SubnetFailure f : q) {
            if (now - f.ts() <= windowMs) { count++; accts.add(f.uuid()); }
        }
        if (count >= AuthConfig.LOGIN_STORM_FAILURES.get() && accts.size() >= AuthConfig.LOGIN_STORM_ACCOUNTS.get()) {
            long banMs = AuthConfig.LOGIN_STORM_BAN_MINUTES.get() * 60_000L;
            ipBans.banSubnet(subnet, banMs);
            alert(WatchdogEvent.of(Severity.CRITICAL, "Login Storm Detected",
                "Subnet " + subnet + ".* banned for " + AuthConfig.LOGIN_STORM_BAN_MINUTES.get() + " min",
                "Subnet", subnet + ".*", "Failures", String.valueOf(count),
                "Distinct Accounts", String.valueOf(accts.size())));
            dailyAnomalies.add("Login storm from " + subnet + ".*");
            q.clear();
        }
    }

    private static String normalizeLookalike(String name) {
        if (name == null) return "";
        String s = name.toLowerCase(Locale.ROOT)
            // Strip invisible/zero-width Unicode
            .replaceAll("[\\u0000-\\u001F\\u007F-\\u009F\\u200B-\\u200F\\u2028-\\u202F\\u2060-\\u206F\\uFEFF]", "");
        // Character substitutions (attacker representation → canonical)
        s = s.replace('1', 'l').replace('!', 'l').replace('|', 'l')
             .replace('0', 'o')
             .replace('i', 'l')
             .replace('@', 'a')
             .replace('3', 'e')
             .replace('$', 's').replace('5', 's');
        // Sequence substitutions
        s = s.replace("rn", "m").replace("vv", "w");
        return s;
    }
}
