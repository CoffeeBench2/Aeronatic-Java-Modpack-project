package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.watchdog.Severity;
import com.coffeesaerosmp.auth.watchdog.WatchdogEvent;
import com.google.gson.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** Converts WatchdogEvents and public server events to Discord webhook JSON. */
public final class AlertFormatter {

    private static final Gson GSON = new GsonBuilder().create();
    private static final String BOT_NAME   = "⚓ AeroGuard";
    private static final String PUBLIC_BOT = "✈ Coffees Aero SMP";

    private AlertFormatter() {}

    // ── Watchdog Alerts ───────────────────────────────────────────────────────

    public static String watchdogAlert(WatchdogEvent event) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", BOT_NAME);

        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", event.severity().emoji() + " " + event.severity().label() + " — " + event.title());
        embed.addProperty("color", event.severity().color());

        JsonArray fields = new JsonArray();
        for (Map.Entry<String, String> e : event.fields().entrySet()) {
            JsonObject f = new JsonObject();
            f.addProperty("name", e.getKey());
            f.addProperty("value", e.getValue().isBlank() ? "—" : e.getValue());
            f.addProperty("inline", true);
            fields.add(f);
        }
        if (event.actionTaken() != null && !event.actionTaken().isBlank()) {
            JsonObject f = new JsonObject();
            f.addProperty("name", "Action Taken");
            f.addProperty("value", event.actionTaken());
            f.addProperty("inline", false);
            fields.add(f);
        }
        embed.add("fields", fields);
        embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(event.timestamp()));
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Coffees Aero SMP Watchdog");
        embed.add("footer", footer);
        embeds.add(embed);
        payload.add("embeds", embeds);
        return GSON.toJson(payload);
    }

    /** Batches multiple LOW-severity JSON payloads into a single embed. */
    public static String batchLow(List<String> payloads) {
        // Re-extract titles from payloads and merge into one embed's description
        StringBuilder desc = new StringBuilder();
        for (String p : payloads) {
            try {
                JsonObject obj = JsonParser.parseString(p).getAsJsonObject();
                JsonArray embeds = obj.getAsJsonArray("embeds");
                if (embeds != null && embeds.size() > 0) {
                    desc.append(embeds.get(0).getAsJsonObject().get("title").getAsString()).append("\n");
                }
            } catch (Exception ignored) {}
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("username", BOT_NAME);
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", Severity.LOW.emoji() + " LOW — Batch (" + payloads.size() + " events)");
        embed.addProperty("color", Severity.LOW.color());
        embed.addProperty("description", desc.toString().trim());
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Coffees Aero SMP Watchdog");
        embed.add("footer", footer);
        embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()));
        embeds.add(embed);
        payload.add("embeds", embeds);
        return GSON.toJson(payload);
    }

    // ── Admin command log ────────────────────────────────────────────────────

    public static String adminAction(String opName, String command, String target, String time) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", BOT_NAME);
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "🛠️ Admin Command");
        embed.addProperty("color", 0x888888);
        JsonArray fields = new JsonArray();
        for (String[] pair : new String[][]{{"Op", opName}, {"Command", "`" + command + "`"}, {"Target", target}, {"Time", time}}) {
            JsonObject f = new JsonObject();
            f.addProperty("name", pair[0]);
            f.addProperty("value", pair[1]);
            f.addProperty("inline", true);
            fields.add(f);
        }
        embed.add("fields", fields);
        embeds.add(embed);
        payload.add("embeds", embeds);
        return GSON.toJson(payload);
    }

    // ── Daily digest ─────────────────────────────────────────────────────────

    public static String dailyDigest(int logins, int failures, int flaggedIps, int adminActions,
                                     String anomalies, String playerBase) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", BOT_NAME);
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("title", "📊 Daily Security Digest");
        embed.addProperty("color", 0x5865F2);
        JsonArray fields = new JsonArray();
        for (String[] pair : new String[][]{
            {"Successful Logins", String.valueOf(logins)},
            {"Failed Logins",     String.valueOf(failures)},
            {"Flagged IPs",       String.valueOf(flaggedIps)},
            {"Admin Actions",     String.valueOf(adminActions)},
            {"Player Base",       playerBase == null || playerBase.isBlank() ? "—" : playerBase},
            {"Anomalies",         anomalies.isBlank() ? "None" : anomalies}
        }) {
            JsonObject f = new JsonObject();
            f.addProperty("name", pair[0]);
            f.addProperty("value", pair[1]);
            f.addProperty("inline", pair[0].equals("Anomalies") ? false : true);
            fields.add(f);
        }
        embed.add("fields", fields);
        embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now()));
        JsonObject footer = new JsonObject();
        footer.addProperty("text", "Coffees Aero SMP — Daily Report");
        embed.add("footer", footer);
        embeds.add(embed);
        payload.add("embeds", embeds);
        return GSON.toJson(payload);
    }

    // ── Public channel events ─────────────────────────────────────────────────

    public static String publicEmbed(String description, int color) {
        return embed(PUBLIC_BOT, description, color, null, null);
    }

    /** Same shape as {@link #publicEmbed} but carries the watchdog bot identity — for join/leave/
     *  achievement events routed to the ADMIN channel (they must not masquerade as the public bot). */
    public static String watchdogEmbed(String description, int color) {
        return embed(BOT_NAME, description, color, null, null);
    }

    /** Public embed whose plain-text {@code content} @mentions a linked Discord user (mentions inside
     *  embeds never ping — the mention must ride in content, with an explicit allowed_mentions). */
    public static String publicEmbedMention(String description, int color, String discordUserId) {
        return embed(PUBLIC_BOT, description, color, "<@" + discordUserId + ">", discordUserId);
    }

    /**
     * Rich PUBLIC achievement card: player as the author line, frame-flavored emoji/color
     * (task = gold trophy, goal = green target, challenge = purple gem), the advancement's own
     * description as italic body. Linked players get pinged via content (embeds never ping).
     */
    public static String achievementEmbed(String displayName, String title, String description,
                                          String frame, String discordUserId) {
        String f = frame == null ? "TASK" : frame;
        String emoji; int color; String verb;
        switch (f) {
            case "CHALLENGE" -> { emoji = "💎"; color = 0xB57BF0; verb = "completed the challenge"; }
            case "GOAL"      -> { emoji = "🎯"; color = 0x57F287; verb = "reached the goal"; }
            default          -> { emoji = "🏆"; color = 0xFEE75C; verb = "made the advancement"; }
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("username", PUBLIC_BOT);
        if (discordUserId != null && !discordUserId.isBlank()) {
            payload.addProperty("content", "<@" + discordUserId + ">");
            JsonObject allowed = new JsonObject();
            JsonArray users = new JsonArray();
            users.add(discordUserId);
            allowed.add("users", users);
            allowed.add("parse", new JsonArray());
            payload.add("allowed_mentions", allowed);
        }
        JsonObject embed = new JsonObject();
        JsonObject author = new JsonObject();
        author.addProperty("name", "✈ " + displayName + " " + verb);
        embed.add("author", author);
        embed.addProperty("title", emoji + " " + title);
        if (description != null && !description.isBlank())
            embed.addProperty("description", "*" + description + "*");
        embed.addProperty("color", color);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        return GSON.toJson(payload);
    }

    private static String embed(String botName, String description, int color,
                                String content, String allowedUserId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", botName);
        if (content != null) {
            payload.addProperty("content", content);
            JsonObject allowed = new JsonObject();
            JsonArray users = new JsonArray();
            if (allowedUserId != null) users.add(allowedUserId);
            allowed.add("users", users);
            allowed.add("parse", new JsonArray());
            payload.add("allowed_mentions", allowed);
        }
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("description", description);
        embed.addProperty("color", color);
        embeds.add(embed);
        payload.add("embeds", embeds);
        return GSON.toJson(payload);
    }

    public static String chatMessage(String badge, String displayName, String message) {
        return chatMessage(badge, displayName, message, null, null);
    }

    public static String chatMessage(String badge, String displayName, String message, String accountName) {
        return chatMessage(badge, displayName, message, accountName, null);
    }

    /**
     * Per-player chat webhook (1.7.5). Discord renders each line under the PLAYER's name and head
     * instead of one shared bot identity, which is what makes the feed read like the in-game tab
     * list rather than a log file.
     *
     * <p>{@code displayName} is what the server shows (NameMask display names, /namecolor styling
     * stripped by the caller). {@code skinTextures} is the profile's stored base64 "textures" value
     * and {@code accountName} the real Mojang username — see {@link #avatarUrl} for how they pick
     * the head.
     *
     * <p>allowed_mentions is locked to an empty parse list: chat is untrusted player input and must
     * never be able to ping @everyone from in-game.
     */
    public static String chatMessage(String badge, String displayName, String message,
                                     String accountName, String skinTextures) {
        JsonObject payload = new JsonObject();
        String shown = (badge == null || badge.isBlank()) ? displayName : badge + " " + displayName;
        // Discord rejects usernames over 80 chars, and forbids "discord" in them.
        if (shown.length() > 80) shown = shown.substring(0, 80);
        payload.addProperty("username", shown);
        String avatar = avatarUrl(skinTextures, accountName);
        if (avatar != null) payload.addProperty("avatar_url", avatar);
        payload.addProperty("content", message);

        JsonObject mentions = new JsonObject();
        mentions.add("parse", new com.google.gson.JsonArray());
        payload.add("allowed_mentions", mentions);
        return GSON.toJson(payload);
    }

    /**
     * Head render URL for the webhook avatar, preferring the skin the player actually WEARS here.
     *
     * <p>1.7.8 — offline players used to show as Steve. The old code only ever passed the Mojang
     * username, and mc-heads resolves a name through Mojang: an offline name matches no account, so
     * it served the default head (verified 2026-08-05 — an unregistered name returns a 549-byte
     * default, a real one 337 bytes). Worse, an offline player who picks a name that IS a real
     * premium account was shown that stranger's head.
     *
     * <p>{@code skinTextures} is the base64 "textures" property CoffeesAeroSkins stores on every
     * profile (premium and offline alike). Decoding it yields the Mojang CDN skin URL, whose last
     * path segment is the texture hash — and mc-heads renders a head straight from a texture hash,
     * no account lookup involved. So this works for offline players, and for premium players it is
     * strictly better too: it shows the skin they chose with /skin rather than their Mojang one.
     *
     * <p>Falls back to the account name (old behaviour) when no skin is stored, and to null — the
     * webhook's own avatar — when there is nothing usable. Never throws: a malformed textures blob
     * must cost a nice avatar, not a chat message.
     */
    public static String avatarUrl(String skinTextures, String accountName) {
        if (skinTextures != null && !skinTextures.isBlank()) {
            try {
                String json = new String(java.util.Base64.getDecoder().decode(skinTextures),
                    java.nio.charset.StandardCharsets.UTF_8);
                String url = com.google.gson.JsonParser.parseString(json).getAsJsonObject()
                    .getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
                String hash = url.substring(url.lastIndexOf('/') + 1);
                // Hashes are lowercase hex. Anything else means a hand-edited or hostile value, and
                // it would be pasted straight into a URL we hand to Discord — reject it.
                if (hash.matches("[0-9a-fA-F]{32,128}")) {
                    return "https://mc-heads.net/avatar/" + hash + "/64";
                }
            } catch (Exception ignored) { /* fall through to the name */ }
        }
        if (accountName != null && !accountName.isBlank()) {
            String safe = accountName.replaceAll("[^A-Za-z0-9_]", "");
            if (!safe.isEmpty()) return "https://mc-heads.net/avatar/" + safe + "/64";
        }
        return null;
    }
}
