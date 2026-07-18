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

    // Highest milestone (hours) already ANNOUNCED per player — PERSISTED. The old in-memory set
    // wiped on every restart, so each join replayed the player's whole milestone history to the
    // public channel (the 1h/5h/10h/50h walls of 2026-07-18).
    private final java.util.Map<java.util.UUID, Integer> announcedUpTo = new ConcurrentHashMap<>();
    private volatile java.nio.file.Path milestoneFile;

    public DiscordBridge(WebhookQueue queue, DiscordGateway gateway) {
        this.queue   = queue;
        this.gateway = gateway;
    }

    /** Load the persisted per-player announced-milestone highs (call once at server start). */
    public void initMilestones(java.nio.file.Path dataDir) {
        milestoneFile = dataDir.resolve("milestones_announced.json");
        announcedUpTo.clear();
        if (!java.nio.file.Files.exists(milestoneFile)) return;
        try {
            var o = com.google.gson.JsonParser.parseString(
                java.nio.file.Files.readString(milestoneFile)).getAsJsonObject();
            o.entrySet().forEach(e ->
                announcedUpTo.put(java.util.UUID.fromString(e.getKey()), e.getValue().getAsInt()));
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Discord] milestones_announced.json load failed: {}", e.getMessage());
        }
    }

    private void saveMilestones() {
        java.nio.file.Path f = milestoneFile;
        if (f == null) return;
        var o = new com.google.gson.JsonObject();
        announcedUpTo.forEach((id, v) -> o.addProperty(id.toString(), v));
        String json = o.toString();
        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            try { java.nio.file.Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[Discord] milestone save failed: {}", e.getMessage()); }
        });
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
        if (!AuthConfig.DISCORD_PUBLIC_DEATHS.get()) return;
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        DiscordWebhook.send(url, AlertFormatter.publicEmbed("💀 " + deathMessage, 0x808080));
    }

    public void onPlayerChat(String badge, String displayName, String rawMessage) {
        // OFF by default since 2026-07-12 — the public feed stays curated (joins/leaves,
        // achievements, milestones); in-game chatter doesn't belong in the community server.
        // Hot-reloadable; Discord→MC (gateway MESSAGE_CREATE) is a separate path and unaffected.
        if (!AuthConfig.DISCORD_PUBLIC_CHAT.get()) return;
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
        // Always the DISPLAY name (2026-07-12 fix) — the old getGameProfile().getName() only equals
        // the display name AFTER NameMask swaps it (premium/unmasked players leaked the raw username
        // into achievement posts, and the watchdog path never applied the display name at all).
        String name = displayNameOf(player);
        String discordId = null;
        if (CoffeesAeroAuth.PROFILE_STORE != null) {
            var prof = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
            if (prof != null) discordId = prof.discordId;
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
        java.util.UUID uuid = player.getUUID();
        Integer prevBoxed = announcedUpTo.get(uuid);
        if (prevBoxed == null) {
            // First time this player is seen since persistence exists: GRANDFATHER every already-
            // crossed milestone silently — otherwise the first join after the fix replays the wall
            // one last time. Genuinely-new players store 0 and announce normally from here on.
            int highestCrossed = 0;
            for (int m : milestones) if (totalHours >= m) highestCrossed = Math.max(highestCrossed, m);
            announcedUpTo.put(uuid, highestCrossed);
            saveMilestones();
            return;
        }
        int prev = prevBoxed, newHigh = prev;
        for (int m : milestones) {
            if (totalHours >= m && m > prev) {
                String desc = "🎉 **" + displayNameOf(player)
                        + "** has played for **" + m + " hours** on " + AuthConfig.SERVER_DISPLAY_NAME.get() + "!";
                DiscordWebhook.send(url, discordId != null && !discordId.isBlank()
                    ? AlertFormatter.publicEmbedMention(desc, 0x5865F2, discordId)
                    : AlertFormatter.publicEmbed(desc, 0x5865F2));
                newHigh = Math.max(newHigh, m);
            }
        }
        if (newHigh != prev) {
            announcedUpTo.put(uuid, newHigh);
            saveMilestones();
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
