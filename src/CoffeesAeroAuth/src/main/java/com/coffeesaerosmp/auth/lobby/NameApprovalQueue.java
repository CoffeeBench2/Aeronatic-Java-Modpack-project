package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.AuthState;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import com.coffeesaerosmp.auth.discord.WebhookQueue;
import com.coffeesaerosmp.auth.util.NetUtil;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.coffeesaerosmp.auth.watchdog.Severity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NameApprovalQueue {

    public record PendingEntry(UUID uuid, String mcName, String proposedName, long submittedAt, boolean isRename) {}

    private final Map<UUID, PendingEntry> pending = new ConcurrentHashMap<>();
    private final ProfileStore  store;
    private final WebhookQueue  webhooks;
    private final com.coffeesaerosmp.auth.discord.DiscordRest discordRest;
    private final MinecraftServer server;
    private final PrivateRoomManager rooms;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "name-approval-timer");
        t.setDaemon(true);
        return t;
    });

    public NameApprovalQueue(ProfileStore store, WebhookQueue webhooks,
                              com.coffeesaerosmp.auth.discord.DiscordRest discordRest,
                              MinecraftServer server, PrivateRoomManager rooms) {
        this.store   = store;
        this.webhooks = webhooks;
        this.discordRest = discordRest;
        this.server  = server;
        this.rooms   = rooms;
    }

    // ── Submit (first time) ───────────────────────────────────────────────────

    public void submit(ServerPlayer player, String proposedName) {
        UUID uuid = player.getUUID();
        pending.put(uuid, new PendingEntry(uuid, player.getGameProfile().getName(), proposedName, System.currentTimeMillis(), false));
        postDiscordPending(player.getGameProfile().getName(), proposedName, uuid);
        scheduleAutoApprove(uuid, proposedName);
        player.sendSystemMessage(Component.literal(
            TextUtil.PREFIX + "§aDisplay name §f" + proposedName + "§a submitted for approval.\n" +
            TextUtil.PREFIX + "§7An admin will review shortly. Wait in your room."));
    }

    /** Submit a one-time post-approval display-name change for admin review (player stays in-world). */
    public void submitRename(ServerPlayer player, String proposedName) {
        UUID uuid = player.getUUID();
        pending.put(uuid, new PendingEntry(uuid, player.getGameProfile().getName(), proposedName, System.currentTimeMillis(), true));
        postDiscordPending(player.getGameProfile().getName(), proposedName, uuid);
        scheduleAutoApprove(uuid, proposedName);
    }

    /** Re-adds a player to the pending queue on reconnect (no Discord post). Lobby naming only. */
    public void requeue(UUID uuid, String mcName, String proposedName) {
        if (!pending.containsKey(uuid)) {
            pending.put(uuid, new PendingEntry(uuid, mcName, proposedName, System.currentTimeMillis(), false));
            scheduleAutoApprove(uuid, proposedName);
        }
    }

    // ── Auto-approve ──────────────────────────────────────────────────────────

    private void scheduleAutoApprove(UUID uuid, String proposedName) {
        int minutes = AuthConfig.AUTO_APPROVE_MINUTES.get();
        if (minutes <= 0) return;
        scheduler.schedule(() -> {
            if (!pending.containsKey(uuid)) return; // already handled
            // Check banned words
            String lower = proposedName.toLowerCase();
            for (String word : getBannedWords()) {
                if (!word.isBlank() && lower.contains(word.trim().toLowerCase())) {
                    server.execute(() -> {
                        PendingEntry e = pending.remove(uuid);
                        boolean rename = e != null && e.isRename();
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        rejectInternal(uuid, proposedName, "Name contains prohibited content (auto-rejected).", p, rename);
                        postDiscordRejected(e != null ? e.mcName() : uuid.toString(), proposedName, uuid, "prohibited content (auto)");
                    });
                    return;
                }
            }
            server.execute(() -> approveInternal(uuid, proposedName, true));
        }, minutes, TimeUnit.MINUTES);
    }

    // ── Admin operations ──────────────────────────────────────────────────────

    /** Returns false if the player was not in the pending queue. */
    public boolean adminApprove(String playerName) {
        UUID uuid = findUUID(playerName);
        if (uuid == null) return false;
        PendingEntry entry = pending.get(uuid);
        if (entry == null) return false;
        approveInternal(uuid, entry.proposedName(), false);
        return true;
    }

    /** Returns false if the player was not in the pending queue. */
    public boolean adminReject(String playerName, String reason) {
        UUID uuid = findUUID(playerName);
        if (uuid == null) return false;
        PendingEntry entry = pending.remove(uuid);
        if (entry == null) return false;
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
        rejectInternal(uuid, entry.proposedName(), reason, p, entry.isRename());
        postDiscordRejected(entry.mcName(), entry.proposedName(), uuid, reason);
        return true;
    }

    // ── Internal approve / reject ─────────────────────────────────────────────

    private void approveInternal(UUID uuid, String proposedName, boolean auto) {
        PendingEntry entry = pending.remove(uuid);
        boolean rename = entry != null && entry.isRename();
        PlayerProfile profile = store.get(uuid);
        if (profile == null) return;
        ServerPlayer p = server.getPlayerList().getPlayer(uuid);

        if (rename) {
            // One-time post-approval change: player is already authenticated and in-world.
            String old = profile.displayName;
            store.releaseDisplayName(old);                // free the previous name
            profile.displayName         = proposedName;   // already reserved tentatively at submit
            profile.nameApprovalPending = false;
            profile.pendingDisplayName  = null;
            store.save(profile);
            if (p != null) {
                p.sendSystemMessage(Component.literal(
                    TextUtil.PREFIX + "§aName change approved: §f" + old + " §7→ §a" + proposedName));
            }
            if (CoffeesAeroAuth.WATCHDOG != null) {
                CoffeesAeroAuth.WATCHDOG.recordNameChange(
                    "APPROVED" + (auto ? "(auto)" : "") + " uuid=" + uuid + " new=" + proposedName);
            }
            postDiscordApproved(profile.username, proposedName, uuid, auto);
            return;
        }

        // Initial lobby naming approval.
        profile.displayName          = proposedName;
        profile.nameApproved         = true;
        profile.nameApprovalPending  = false;
        profile.pendingDisplayName   = null;
        store.save(profile);
        if (p != null) {
            p.sendSystemMessage(Component.literal(
                TextUtil.PREFIX + "§aYour display name §f" + proposedName +
                "§a has been approved! Welcome to the server!"));
            if (CoffeesAeroAuth.AUTH_MANAGER != null) {
                CoffeesAeroAuth.AUTH_MANAGER.completeApproval(p, profile);
            }
        }
        postDiscordApproved(profile.username, proposedName, uuid, auto);
    }

    private void rejectInternal(UUID uuid, String proposedName, String reason, ServerPlayer p, boolean isRename) {
        // Release the tentative name reservation
        store.releaseDisplayName(proposedName);

        PlayerProfile profile = store.get(uuid);

        if (isRename) {
            // Authenticated player's one-time change rejected: keep their current name, the one-time
            // use stays consumed, no lobby reset, no IP ban.
            if (profile != null) {
                profile.nameApprovalPending = false;
                profile.pendingDisplayName  = null;
                store.save(profile);
            }
            if (p != null) {
                p.sendSystemMessage(Component.literal(
                    TextUtil.PREFIX + "§cName change to §f" + proposedName + "§c was rejected.\n" +
                    TextUtil.PREFIX + "§7Reason: §e" + reason + "\n" +
                    TextUtil.PREFIX + "§7Your current display name is unchanged. (one-time change used)"));
            }
            if (CoffeesAeroAuth.WATCHDOG != null) {
                CoffeesAeroAuth.WATCHDOG.recordNameChange(
                    "REJECTED uuid=" + uuid + " new=" + proposedName + " reason=" + reason);
            }
            return;
        }

        // Initial lobby naming reject (3-strike kick + 1h ban).
        if (profile != null) {
            profile.nameApprovalPending = false;
            profile.pendingDisplayName  = null;
            profile.nameRejectionCount++;
            store.save(profile);

            if (profile.nameRejectionCount >= 3) {
                if (p != null) {
                    if (CoffeesAeroAuth.WATCHDOG != null) {
                        CoffeesAeroAuth.WATCHDOG.getIpBanManager().ban(
                            NetUtil.getPlayerIP(p), 60 * 60_000L);
                    }
                    p.connection.disconnect(Component.literal(
                        "§cToo many name rejections. Your IP is banned for 1 hour.\n§7Reason: §e" + reason));
                }
                return;
            }
        }

        if (p != null) {
            int remaining = profile != null ? 3 - profile.nameRejectionCount : 3;
            p.sendSystemMessage(Component.literal(
                TextUtil.PREFIX + "§cDisplay name §f" + proposedName + "§c rejected.\n" +
                TextUtil.PREFIX + "§7Reason: §e" + reason + "\n" +
                TextUtil.PREFIX + "§eChoose a different name: §a/setname <name>§e. (" + remaining + " tries left)"));
            if (CoffeesAeroAuth.AUTH_MANAGER != null) {
                CoffeesAeroAuth.AUTH_MANAGER.resetToNaming(p);
            }
        }
    }

    // ── Queue inspection ──────────────────────────────────────────────────────

    public Map<UUID, PendingEntry> getPending() { return Collections.unmodifiableMap(pending); }
    public boolean isPending(UUID uuid) { return pending.containsKey(uuid); }

    // ── Discord ───────────────────────────────────────────────────────────────

    private void postDiscordPending(String mcName, String proposedName, UUID uuid) {
        if (!AuthConfig.DISCORD_ENABLED.get()) return;

        // Action-needed events must PING the admin role (embeds alone never notify anyone).
        String adminRole = AuthConfig.DISCORD_ADMIN_ROLE_ID.get();
        String ping = adminRole == null || adminRole.isBlank() ? ""
            : "\"content\":\"<@&" + jesc(adminRole) + "> name approval needed\","
            + "\"allowed_mentions\":{\"parse\":[],\"roles\":[\"" + jesc(adminRole) + "\"]},";

        // Preferred: bot message with Approve/Reject BUTTONS in the moderation channel.
        String channelId = AuthConfig.DISCORD_WATCHDOG_CHANNEL_ID.get();
        if (discordRest != null && discordRest.isConfigured() && !channelId.isBlank()) {
            String json = "{" + ping
                + "\"embeds\":[{\"title\":\"🔔 Name Approval Required\",\"color\":16776960,\"fields\":["
                +   "{\"name\":\"Player\",\"value\":\"" + jesc(mcName) + "\",\"inline\":true},"
                +   "{\"name\":\"Requested Name\",\"value\":\"" + jesc(proposedName) + "\",\"inline\":true},"
                +   "{\"name\":\"UUID\",\"value\":\"" + jesc(uuid.toString()) + "\",\"inline\":false}"
                + "]}],"
                + "\"components\":[{\"type\":1,\"components\":["
                +   "{\"type\":2,\"style\":3,\"label\":\"Approve\",\"custom_id\":\"nameapprove:" + jesc(mcName) + "\"},"
                +   "{\"type\":2,\"style\":4,\"label\":\"Reject\",\"custom_id\":\"namereject:" + jesc(mcName) + "\"}"
                + "]}]}";
            discordRest.postMessage(channelId, json);
            return;
        }

        // Fallback: webhook embed with the text commands (no buttons).
        String url = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (url.isBlank()) return;
        webhooks.enqueue(url, String.format("""
            {%s"embeds": [{"title": "🔔 Name Approval Required", "color": 16776960,
              "fields": [
                {"name": "Player",          "value": "%s", "inline": true},
                {"name": "Requested Name",  "value": "%s", "inline": true},
                {"name": "UUID",            "value": "%s", "inline": false},
                {"name": "Approve",         "value": "`/authmod approve %s`", "inline": true},
                {"name": "Reject",          "value": "`/authmod reject %s <reason>`", "inline": true}
              ]}]}""", ping, mcName, proposedName, uuid, mcName, mcName), Severity.MEDIUM);
    }

    private void postDiscordApproved(String mcName, String proposedName, UUID uuid, boolean auto) {
        if (!AuthConfig.DISCORD_ENABLED.get()) return;
        String url = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (url.isBlank()) return;
        webhooks.enqueue(url, String.format(
            "{\"embeds\": [{\"title\": \"✅ Name Approved%s\", \"color\": 65280, " +
            "\"description\": \"**%s** → display name: **%s**\"}]}",
            auto ? " (auto)" : "", mcName, proposedName), Severity.LOW);
    }

    private void postDiscordRejected(String mcName, String proposedName, UUID uuid, String reason) {
        if (!AuthConfig.DISCORD_ENABLED.get()) return;
        String url = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (url.isBlank()) return;
        webhooks.enqueue(url, String.format(
            "{\"embeds\": [{\"title\": \"❌ Name Rejected\", \"color\": 16711680, " +
            "\"description\": \"**%s** → **%s** rejected\\\\nReason: %s\"}]}",
            mcName, proposedName, reason), Severity.MEDIUM);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UUID findUUID(String playerName) {
        // Check online players first
        ServerPlayer online = server.getPlayerList().getPlayerByName(playerName);
        if (online != null) return online.getUUID();
        // Then search pending queue by mcName
        for (PendingEntry entry : pending.values()) {
            if (entry.mcName().equalsIgnoreCase(playerName)) return entry.uuid();
        }
        return null;
    }

    private String getEntryMcName(UUID uuid) {
        PendingEntry e = pending.get(uuid);
        return e != null ? e.mcName() : uuid.toString();
    }

    private String[] getBannedWords() {
        String raw = AuthConfig.BANNED_WORDS.get();
        if (raw == null || raw.isBlank()) return new String[0];
        return raw.split(",");
    }

    private static String jesc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
