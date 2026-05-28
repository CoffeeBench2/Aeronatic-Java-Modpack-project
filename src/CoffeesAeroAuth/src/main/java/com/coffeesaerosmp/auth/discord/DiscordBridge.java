package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public channel bridge: MC events → Discord, Discord messages → MC chat.
 * Owns the DiscordGateway and routes incoming Discord messages to the server thread.
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
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        String desc = isFirstEver
            ? "🌟 **" + player.getGameProfile().getName() + "** joined for the first time! Welcome!"
            : "✈️ **" + player.getGameProfile().getName() + "** joined the server " + badge;
        queue.enqueue(url, AlertFormatter.publicEmbed(desc, 0x57F287), isFirstEver
            ? com.coffeesaerosmp.auth.watchdog.Severity.MEDIUM
            : com.coffeesaerosmp.auth.watchdog.Severity.LOW);
    }

    public void onPlayerLeave(ServerPlayer player) {
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        queue.enqueue(url,
            AlertFormatter.publicEmbed("💨 **" + player.getGameProfile().getName() + "** left the server", 0xED4245),
            com.coffeesaerosmp.auth.watchdog.Severity.LOW);
    }

    public void onPlayerDeath(ServerPlayer player, String deathMessage) {
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        queue.enqueue(url,
            AlertFormatter.publicEmbed("💀 " + deathMessage, 0x808080),
            com.coffeesaerosmp.auth.watchdog.Severity.LOW);
    }

    public void onPlayerChat(String badge, String displayName, String rawMessage) {
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        queue.enqueue(url,
            AlertFormatter.chatMessage(badge, displayName, rawMessage),
            com.coffeesaerosmp.auth.watchdog.Severity.LOW);
    }

    public void onAdvancement(ServerPlayer player, String title) {
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        queue.enqueue(url,
            AlertFormatter.publicEmbed("🏆 **" + player.getGameProfile().getName() + "** just earned **[" + title + "]**", 0xFEE75C),
            com.coffeesaerosmp.auth.watchdog.Severity.LOW);
    }

    /** Called after each successful auth with the player's total playtime in hours. */
    public void checkMilestones(ServerPlayer player, long totalHours) {
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        int[] milestones = parseMilestones(AuthConfig.DISCORD_MILESTONE_HOURS.get());
        for (int m : milestones) {
            if (totalHours >= m) {
                long key = (long)player.getUUID().hashCode() * 31 + m;
                if (postedMilestones.add(key)) {
                    queue.enqueue(url,
                        AlertFormatter.publicEmbed("🎉 **" + player.getGameProfile().getName()
                            + "** has played for **" + m + " hours** on " + AuthConfig.SERVER_DISPLAY_NAME.get() + "!", 0x5865F2),
                        com.coffeesaerosmp.auth.watchdog.Severity.LOW);
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
