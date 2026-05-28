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

    public static String dailyDigest(int logins, int failures, int flaggedIps, int adminActions, String anomalies) {
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
        JsonObject payload = new JsonObject();
        payload.addProperty("username", PUBLIC_BOT);
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();
        embed.addProperty("description", description);
        embed.addProperty("color", color);
        embeds.add(embed);
        payload.add("embeds", embeds);
        return GSON.toJson(payload);
    }

    public static String chatMessage(String badge, String displayName, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", PUBLIC_BOT);
        payload.addProperty("content", badge + " **" + displayName + "**: " + message);
        return GSON.toJson(payload);
    }
}
