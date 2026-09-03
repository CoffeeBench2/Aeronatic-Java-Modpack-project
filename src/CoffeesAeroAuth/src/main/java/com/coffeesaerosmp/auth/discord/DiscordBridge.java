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
        if (AuthConfig.DISCORD_PUBLIC_JOINLEAVE.get() && !pub.isBlank() && !isHidden(player)) {
            String name = displayNameOf(player);
            publish(pub, isFirstEver
                ? AlertFormatter.publicEmbed("🌟 **" + name + "** just made their first flight on "
                    + AuthConfig.SERVER_DISPLAY_NAME.get() + " — welcome aboard, pilot! o7", 0xFEE75C)
                : AlertFormatter.publicEmbed("🛫 **" + name + "** boarded the server", 0x57F287));
        }
    }

    public void onPlayerLeave(ServerPlayer player) {
        String url = AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get();
        if (!url.isBlank()) {
            queue.enqueue(url, AlertFormatter.watchdogEmbed("💨 **" + player.getGameProfile().getName() + "** left the server", 0xED4245),
                com.coffeesaerosmp.auth.watchdog.Severity.LOW);
        }
        String pub = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (AuthConfig.DISCORD_PUBLIC_JOINLEAVE.get() && !pub.isBlank() && !isHidden(player)) {
            publish(pub, AlertFormatter.publicEmbed(
                "🛬 **" + displayNameOf(player) + "** left the server", 0x99AAB5));
        }
    }

    /**
     * True when this player has hidden themselves with {@code /authmod hide}.
     *
     * <p>🔑 Hiding is a PRESENCE toggle, and Discord is a presence surface. Before this check the
     * hide only ever touched the in-game TAB list, so a hidden op still announced themselves to the
     * whole community feed on join — the single most visible place they were trying not to be.
     *
     * <p>Deliberately gates the PUBLIC feed only. The watchdog webhook keeps posting, because that
     * channel exists so admins see everything (same reasoning as the real-name copy in
     * {@link #onPlayerJoin}), and an admin who cannot see another admin come and go is worse than
     * no hide at all. Chat is also deliberately NOT gated: hiding presence is not a mute, and a
     * hidden op who chooses to talk has chosen to be seen.
     */
    private static boolean isHidden(ServerPlayer player) {
        return com.coffeesaerosmp.auth.display.HiddenOps.isHidden(player.getUUID());
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
        if (isHidden(player)) return;   // a death message names them, and names their coords' worth of context
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        publish(url, AlertFormatter.publicEmbed("💀 " + deathMessage, 0x808080));
    }

    public void onPlayerChat(String badge, String displayName, String rawMessage) {
        onPlayerChat(badge, displayName, rawMessage, null, null);
    }

    public void onPlayerChat(String badge, String displayName, String rawMessage, String accountName) {
        onPlayerChat(badge, displayName, rawMessage, accountName, null);
    }

    /**
     * {@code skinTextures} = the profile's stored base64 "textures" value, {@code accountName} = the
     * real Mojang username. Together they resolve the Discord avatar so each line shows the player's
     * head; the skin is preferred because it is the only one that works for OFFLINE players (1.7.8).
     * See {@link AlertFormatter#avatarUrl}.
     *
     * <p>Only ServerChatEvent reaches here, so commands, /msg and any other hidden text are already
     * excluded by construction — the event fires for chat packets only, never for the command
     * dispatcher. Nothing extra is filtered because nothing extra arrives.
     */
    public void onPlayerChat(String badge, String displayName, String rawMessage,
                             String accountName, String skinTextures) {
        // OFF by default since 2026-07-12 — the public feed stays curated (joins/leaves,
        // achievements, milestones); in-game chatter doesn't belong in the community server.
        // Hot-reloadable; Discord→MC (gateway MESSAGE_CREATE) is a separate path and unaffected.
        if (!AuthConfig.DISCORD_PUBLIC_CHAT.get()) return;
        String url = AuthConfig.DISCORD_WEBHOOK_PUBLIC.get();
        if (url.isBlank()) return;
        queue.enqueue(url,
            AlertFormatter.chatMessage(badge, displayName, rawMessage, accountName, skinTextures),
            com.coffeesaerosmp.auth.watchdog.Severity.LOW);
    }

    public void onAdvancement(ServerPlayer player, String title, String description, String frame) {
        // Public feed (display names only — never real names in public) when enabled; else watchdog.
        boolean pub = AuthConfig.DISCORD_PUBLIC_ACHIEVEMENTS.get();
        // Hidden + public feed = announcing the presence they just hid. Hidden + watchdog is fine:
        // that channel is admins-only and is meant to keep seeing them.
        if (pub && isHidden(player)) return;
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
        publish(url, json);
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
                publish(url, discordId != null && !discordId.isBlank()
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
        onDiscordMessage(server, "", authorName, content);
    }

    /**
     * Discord → MC with LINKED IDENTITY (1.7.5).
     *
     * <p>If the Discord author has completed /link, the message renders under their in-game display
     * name — the same name the server shows in chat and tab — so a linked player talking from their
     * phone reads as themselves rather than as a stranger's Discord handle. Unlinked members still
     * come through, tagged with their Discord username, so the bridge never silently drops anyone.
     *
     * <p>Resolution is by Discord snowflake, never by username: Discord handles are changeable and
     * not unique, and matching on them would let anyone impersonate a linked player by renaming.
     *
     * <p>Content is sanitised before it reaches MC chat — section signs would otherwise let Discord
     * inject colour codes (and, with §k, unreadable scrambled text) into every player's chat.
     */
    public void onDiscordMessage(MinecraftServer server, String authorId, String authorName, String content) {
        String safe = content.replace('§', '&');
        if (safe.length() > 256) safe = safe.substring(0, 256) + "…";

        String label;
        var store = com.coffeesaerosmp.auth.CoffeesAeroAuth.PROFILE_STORE;
        var profile = store != null ? store.findByDiscordId(authorId) : null;
        if (profile != null) {
            String shown = (profile.displayName != null && !profile.displayName.isBlank())
                ? profile.displayName : profile.username;
            label = "§9[Discord]§r §b" + shown + "§r";      // linked → their server identity
        } else {
            label = "§9[Discord | " + authorName + "]§r";   // unlinked → plain Discord handle
        }

        Component msg = Component.literal(label + " " + safe);
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * The single MC → Discord exit point for non-chat events.
     *
     * <p>WHY THIS EXISTS: until 2026-08-08 chat was the only event that went through
     * {@link WebhookQueue}; join, leave, death, achievement and milestone posts each called
     * {@code DiscordWebhook.send} directly. That had two consequences, and both are exactly what
     * players reported:
     *
     * <ol>
     *   <li>Whenever the queue stalled, <b>chat went silent while joins and achievements kept
     *       arriving</b> — they were never in the queue to begin with, so the outage looked
     *       partial and pointed away from its own cause.</li>
     *   <li>{@code send()} discards the {@code retry_after} that {@code sendForRetry()} returns, so
     *       any direct post that met a 429 was <b>dropped with no retry</b> — the intermittent
     *       "achievements sometimes don't come".</li>
     * </ol>
     *
     * <p>Routing everything through the queue means one rate-limit gate, one retry policy and one
     * ordering guarantee for the whole bridge. {@code MEDIUM} keeps these out of the LOW batch
     * wrapper, which is watchdog-channel styling and must never wrap a public post.
     */
    private void publish(String url, String json) {
        queue.enqueue(url, json, com.coffeesaerosmp.auth.watchdog.Severity.MEDIUM);
    }

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
