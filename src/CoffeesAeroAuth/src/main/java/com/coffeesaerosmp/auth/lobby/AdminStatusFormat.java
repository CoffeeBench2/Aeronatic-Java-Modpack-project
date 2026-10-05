package com.coffeesaerosmp.auth.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.List;

/**
 * The admin status card's webhook body: Survival and Lobby player counts at a glance.
 *
 * <p>Pure on purpose (no Minecraft classes) so it is unit-tested; {@link AdminStatusCard} does the
 * scheduling and the HTTP. Timestamps use Discord's {@code <t:SECONDS:R>} so every viewer sees
 * "2 minutes ago" in their own clock, which also makes a stalled updater obvious.
 */
public final class AdminStatusFormat {

    public static final int GREEN = 0x3BA55C;
    public static final int RED = 0xED4245;
    /** Discord rejects an embed field value over 1024 characters. Kept clear of it. */
    private static final int FIELD_MAX = 1000;

    private AdminStatusFormat() {}

    /**
     * @param smpUp         the lobby's confirmed view of Survival (SmpLiveness)
     * @param smpOnline     Survival's reported online count, -1 if unknown
     * @param smpMax        Survival's max players, -1 if unknown
     * @param smpNames      the Server List Ping sample (at most ~12 names, not the full list)
     * @param smpDownSince  when Survival was confirmed down (epoch ms); ignored while up
     * @param lobbyNames    every player on the lobby right now
     * @param nowMs         wall clock, epoch ms
     */
    public static String build(boolean smpUp, int smpOnline, int smpMax, List<String> smpNames,
                               long smpDownSince, List<String> lobbyNames, long nowMs) {
        JsonArray fields = new JsonArray();
        if (smpUp) {
            int online = Math.max(smpOnline, smpNames.size());
            fields.add(field("🟢 Survival — " + online + "/" + (smpMax < 0 ? "?" : smpMax),
                names(smpNames, online)));
        } else {
            fields.add(field("🔴 Survival — DOWN",
                "Not answering since <t:" + smpDownSince / 1000 + ":R>. Players are held in the lobby."));
        }
        fields.add(field("🏛 Lobby — " + lobbyNames.size(), names(lobbyNames, lobbyNames.size())));

        JsonObject embed = new JsonObject();
        embed.addProperty("title", "📊 Coffee's Aero SMP — live players");
        embed.addProperty("description", "Updated <t:" + nowMs / 1000 + ":R> · refreshes every minute");
        embed.addProperty("color", smpUp ? GREEN : RED);
        embed.add("fields", fields);
        embed.addProperty("timestamp", Instant.ofEpochMilli(nowMs).toString());

        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject body = new JsonObject();
        body.addProperty("content", "");
        body.add("embeds", embeds);
        JsonObject mentions = new JsonObject();   // a player called "@everyone" must not ping anyone
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);
        return body.toString();
    }

    private static JsonObject field(String name, String value) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", false);
        return f;
    }

    /** "A, B, C" — escaped for markdown, cut before Discord's limit, "+N more" for the rest. */
    static String names(List<String> names, int total) {
        if (total <= 0 && names.isEmpty()) return "nobody online";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String n : names) {
            String esc = escape(n);
            if (sb.length() + esc.length() + 2 > FIELD_MAX - 20) break;
            if (shown > 0) sb.append(", ");
            sb.append(esc);
            shown++;
        }
        int rest = Math.max(total, names.size()) - shown;
        if (rest > 0) sb.append(shown > 0 ? " " : "").append("+").append(rest).append(" more");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replaceAll("([\\\\_*~`|>])", "\\\\$1");
    }
}
