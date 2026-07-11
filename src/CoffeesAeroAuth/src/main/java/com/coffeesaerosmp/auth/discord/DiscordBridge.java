package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discord bridge: MC events → Discord, Discord messages → MC chat.
 * Chat / deaths / playtime milestones go to the public webhook; join, leave and achievements are
 * routed to the watchdog (admin-only) webhook instead. Owns the DiscordGateway and routes incoming
 * Discord messages to the server thread.
 */
public class DiscordBridge {

    private final WebhookQueue  queue;
    private final DiscordGateway gateway;

    // Track (uuid.hashCode * milestone) to avoid duplicate milestone posts
    private final Set<Long> postedMilestones = ConcurrentHashMap.newKeySet();

    public DiscordBridge(WebhookQueue queue, DiscordGateway gateway) {
        this.queue   = queue;
        this.gateway = gateway;
    }

    public void startGateway() {
        gateway.connect();
    }

    public void stop() {
        gateway.disconnect();
    }

    // ── MC → Discord ──────────────────────────────────────────────────────────

    public void onPlayerJoin(ServerPlayer player, boolean isFirstEver, String badge) {
        // Watchdog copy (admins see everything, incl. real-name context via the overlay tooling).
        String url = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (!url.isBlank()) {
            String desc = isFirstEver
                ? "🌟 **" + player.getGameProfile().getName() + "** joined for the first time! Welcome!"
                : "✈️ **" + player.getGameProfile().getName() + "** joined the server " + badge;
            queue.enqueue(url, AlertFormatter.watchdogEmbed(desc, 0x57F287), isFirstEver
                ? com.coffeesaerosmp.auth.watchdog.Severity.MEDIUM
                : com.coffeesaerosmp.auth.watchdog.Severity.LOW);
        }
        // Public copy (2026-07-11 request) — display names only, celebratory tone.
        String pub = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (AuthConfig.DISCORD_PUBLIC_JOINLEAVE.get() && !pub.isBlank()) {
            String name = displayNameOf(player);
            DiscordWebhook.send(pub, isFirstEver
                ? AlertFormatter.publicEmbed("🌟 **" + name + "** just made their first flight on "
                    + AuthConfig.SERVER_DISPLAY_NAME.get() + " — welcome aboard, pilot! o7", 0xFEE75C)
                : AlertFormatter.publicEmbed("🛫 **" + name + "** boarded the server", 0x57F287));
        }
    }

    public void onPlayerLeave(ServerPlayer player) {
        String url = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (!url.isBlank()) {
            DiscordWebhook.send(url, AlertFormatter.watchdogEmbed("💨 **" + player.getGameProfile().getName() + "** left the server", 0xED4245));
        }
        String pub = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (AuthConfig.DISCORD_PUBLIC_JOINLEAVE.get() && !pub.isBlank()) {
            DiscordWebhook.send(pub, AlertFormatter.publicEmbed(
                "🛬 **" + displayNameOf(player) + "** left the server", 0x99AAB5));
        }
    }

    /** Display name for PUBLIC posts — never the real account name. */
    private static String displayNameOf(ServerPlayer player) {
        if (CoffeesAeroAuth.PROFILE_STORE != null) {
            var prof = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
            if (prof != null && prof.displayName != null && !prof.displayName.isBlank())
                return prof.displayName;
        }
        return player.getGameProfile().getName();   // post-NameMask this is already the display name
    }

    public void onPlayerDeath(ServerPlayer player, String deathMessage) {
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        DiscordWebhook.send(url, AlertFormatter.publicEmbed("💀 " + deathMessage, 0x808080));
    }

    public void onPlayerChat(String badge, String displayName, String rawMessage) {
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        queue.enqueue(url,
            AlertFormatter.chatMessage(badge, displayName, rawMessage),
            com.coffeesaerosmp.auth.watchdog.Severity.LOW);
    }

    public void onAdvancement(ServerPlayer player, String title, String description, String frame) {
        // Public feed (display names only — never real names in public) when enabled; else watchdog.
        boolean pub = AuthConfig.DISCORD_PUBLIC_ACHIEVEMENTS.get();
        String url = pub ? AuthConfig.DISCORD_WEBHOOK_PUBLIC.get() : AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (url.isBlank()) return;
        String name = player.getGameProfile().getName();
        String discordId = null;
        if (CoffeesAeroAuth.PROFILE_STORE != null) {
            var prof = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
            if (prof != null) {
                if (pub && prof.displayName != null) name = prof.displayName;
                discordId = prof.discordId;
            }
        }
        String json = pub
            ? AlertFormatter.achievementEmbed(name, title, description, frame, discordId)
            : AlertFormatter.watchdogEmbed("🏆 **" + name + "** just earned **[" + title + "]**", 0xFEE75C);
        DiscordWebhook.send(url, json);
    }

    /** Live player count -> bot status. */
    public void updatePlayerCount(int online) {
        if (gateway != null) gateway.updatePresence(online);
    }

    /** Called after each successful auth with the player's total playtime in hours. */
    public void checkMilestones(ServerPlayer player, long totalHours) {
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        String discordId = null;
        if (CoffeesAeroAuth.PROFILE_STORE != null) {
            var prof = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
            if (prof != null) discordId = prof.discordId;
        }
        int[] milestones = parseMilestones(AuthConfig.DISCORD_MILESTONE_HOURS.get());
        for (int m : milestones) {
            if (totalHours >= m) {
                long key = (long)player.getUUID().hashCode() * 31 + m;
                if (postedMilestones.add(key)) {
                    String desc = "🎉 **" + player.getGameProfile().getName()
                            + "** has played for **" + m + " hours** on " + AuthConfig.SERVER_DISPLAY_NAME.get() + "!";
                    DiscordWebhook.send(url, discordId != null && !discordId.isBlank()
                        ? AlertFormatter.publicEmbedMention(desc, 0x5865F2, discordId)
                        : AlertFormatter.publicEmbed(desc, 0x5865F2));
                }
            }
        }
    }

    // ── Discord → MC ──────────────────────────────────────────────────────────

    /** Called by DiscordGateway on incoming MESSAGE_CREATE. Runs on server thread. */
    public void onDiscordMessage(MinecraftServer server, String authorName, String content) {
        Component msg = Component.literal("§9[Discord | " + authorName + "]§r " + content);
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int[] parseMilestones(String csv) {
        try {
            return Arrays.stream(csv.split(","))
                .map(String::trim)
                .mapToInt(Integer::parseInt)
                .toArray();
        } catch (Exception e) {
            return new int[]{1, 5, 10, 50, 100};
        }
    }
}
