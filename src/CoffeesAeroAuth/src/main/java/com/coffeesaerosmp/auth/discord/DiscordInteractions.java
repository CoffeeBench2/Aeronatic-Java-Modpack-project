package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

/**
 * Handles Discord component/modal interactions (Feature A: name-approval buttons).
 *
 * <p>Flow: an approval request is posted with ✅ Approve / ❌ Reject buttons whose {@code custom_id}
 * carries the player's login name. A click arrives here as an INTERACTION_CREATE:</p>
 * <ul>
 *   <li>{@code nameapprove:<mcName>} (button) → {@code adminApprove} on the server thread, then UPDATE the
 *       message to "approved by &lt;user&gt;".</li>
 *   <li>{@code namereject:<mcName>} (button) → open a MODAL asking for the required reason.</li>
 *   <li>{@code rejectmodal:<mcName>} (modal submit) → {@code adminReject(reason)}, then UPDATE the message.</li>
 * </ul>
 *
 * <p>Every action is gated on the configured admin role and audited via the watchdog. We must respond to
 * each interaction within 3 seconds (Discord requirement) — the UPDATE/MODAL response is sent immediately.</p>
 */
public class DiscordInteractions {

    private final MinecraftServer server;
    private final DiscordRest rest;
    private final String adminRoleId;

    public DiscordInteractions(MinecraftServer server, DiscordRest rest, String adminRoleId) {
        this.server = server;
        this.rest = rest;
        this.adminRoleId = adminRoleId == null ? "" : adminRoleId;
    }

    /** Called by DiscordGateway on INTERACTION_CREATE with the raw {@code d} payload. */
    public void handle(JsonObject d) {
        try {
            int type = d.get("type").getAsInt();           // 2 = slash command, 3 = component, 5 = modal submit
            String id = d.get("id").getAsString();
            String token = d.get("token").getAsString();
            if (!d.has("data") || !d.get("data").isJsonObject()) return;
            JsonObject data = d.getAsJsonObject("data");

            // Slash commands are PUBLIC (everyone may /uptime and /link themselves) — handled
            // before the admin-role gate below, which only guards moderation buttons/modals.
            if (type == 2) {
                handleSlash(d, data, id, token);
                return;
            }

            String customId = data.has("custom_id") ? data.get("custom_id").getAsString() : "";

            String clicker = "an admin";
            boolean authorized = adminRoleId.isBlank();    // blank = no gating (LOCAL TESTING ONLY)
            if (d.has("member") && d.get("member").isJsonObject()) {
                JsonObject member = d.getAsJsonObject("member");
                if (member.has("user") && member.getAsJsonObject("user").has("username"))
                    clicker = member.getAsJsonObject("user").get("username").getAsString();
                if (!authorized && member.has("roles"))
                    for (JsonElement r : member.getAsJsonArray("roles"))
                        if (adminRoleId.equals(r.getAsString())) { authorized = true; break; }
            }
            if (!authorized) {
                rest.respondInteraction(id, token,
                    "{\"type\":4,\"data\":{\"flags\":64,\"content\":\"You don't have permission to do that.\"}}");
                return;
            }

            if (type == 3 && customId.startsWith("nameapprove:")) {
                doApprove(customId.substring("nameapprove:".length()), clicker, id, token);
            } else if (type == 3 && customId.startsWith("namereject:")) {
                openRejectModal(customId.substring("namereject:".length()), id, token);
            } else if (type == 5 && customId.startsWith("rejectmodal:")) {
                String mcName = customId.substring("rejectmodal:".length());
                doReject(mcName, extractModalValue(data, "reason"), clicker, id, token);
            } else if (type == 3 && customId.startsWith("wdban:")) {
                doWatchdogBan(customId.substring("wdban:".length()), clicker, id, token);
            } else if (type == 3 && customId.startsWith("wdunban:")) {
                doWatchdogUnban(customId.substring("wdunban:".length()), false, clicker, id, token);
            } else if (type == 3 && customId.startsWith("wdunbansub:")) {
                doWatchdogUnban(customId.substring("wdunbansub:".length()), true, clicker, id, token);
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Discord] interaction handling error: {}", e.getMessage());
        }
    }

    // ── Slash commands (/uptime, /leaderboard, /link) ─────────────────────────

    /** Global command definitions — bulk-PUT on every READY (idempotent). */
    public static final String GLOBAL_COMMANDS_JSON = "["
        + "{\"name\":\"uptime\",\"type\":1,\"description\":\"How long has Coffees Aero SMP been running?\"},"
        + "{\"name\":\"leaderboard\",\"type\":1,\"description\":\"Top 10 pilots by playtime\","
        +  "\"options\":[{\"type\":3,\"name\":\"board\",\"required\":false,"
        +   "\"description\":\"Which ranking to show (default: this season)\",\"choices\":["
        +    "{\"name\":\"Most hours flown (this season)\",\"value\":\"playtime\"},"
        +    "{\"name\":\"Most hours flown (all time)\",\"value\":\"alltime\"},"
        +    "{\"name\":\"Longest-serving (oldest members)\",\"value\":\"oldest\"}]}]},"
        + "{\"name\":\"link\",\"type\":1,\"description\":\"Link your Discord to your Minecraft account\","
        +  "\"options\":[{\"type\":3,\"name\":\"code\",\"required\":true,"
        +   "\"description\":\"The code from /discord link in-game\"}]}"
        + "]";

    private void handleSlash(JsonObject d, JsonObject data, String id, String token) {
        String name = data.has("name") ? data.get("name").getAsString() : "";
        switch (name) {
            case "uptime" -> server.execute(() ->
                rest.respondInteraction(id, token,
                    "{\"type\":4,\"data\":{\"embeds\":[" + uptimeEmbedJson(server) + "]}}"));
            case "leaderboard" -> {
                String board = extractOption(data, "board");
                server.execute(() ->
                    rest.respondInteraction(id, token,
                        "{\"type\":4,\"data\":{\"embeds\":[" + leaderboardEmbedJson(server, board) + "]}}"));
            }
            case "link" -> {
                String code = extractOption(data, "code");
                String userId = interactionUserId(d);
                server.execute(() -> {
                    String result = com.coffeesaerosmp.auth.discord.LinkManager.completeLink(code, userId);
                    rest.respondInteraction(id, token,
                        "{\"type\":4,\"data\":{\"flags\":64,\"content\":\"" + esc(result) + "\"}}");
                });
            }
        }
    }

    /**
     * Uptime since the configured SMP launch date — deliberately NOT since-last-boot (the panel
     * restarts the server several times a day). Current process session shown as a second line.
     */
    public static String uptimeEmbedJson(MinecraftServer server) {
        String sinceStr = com.coffeesaerosmp.auth.config.AuthConfig.DISCORD_SMP_LAUNCH_DATE.get();
        long days;
        String pretty;
        try {
            java.time.LocalDate since = java.time.LocalDate.parse(sinceStr.trim());
            days = java.time.temporal.ChronoUnit.DAYS.between(since, java.time.LocalDate.now(java.time.ZoneOffset.UTC));
            pretty = since.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM uuuu", java.util.Locale.ENGLISH));
        } catch (Exception e) {
            days = -1;
            pretty = sinceStr;
        }
        long sessionMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        long sh = sessionMs / 3_600_000, sm = (sessionMs % 3_600_000) / 60_000;
        // Public slash command — hidden ops must not be counted (see HiddenOps / TabListManager).
        int online = server.getPlayerList().getPlayerCount()
            - com.coffeesaerosmp.auth.display.HiddenOps.hiddenCount(server.getPlayerList().getPlayers());
        String desc = "🛫 **" + com.coffeesaerosmp.auth.config.AuthConfig.SERVER_DISPLAY_NAME.get()
            + "** has been flying since **" + pretty + "**"
            + (days >= 0 ? " — **" + days + " day" + (days == 1 ? "" : "s") + "** and counting!" : "!")
            + "\nCurrent session: " + sh + "h " + sm + "m • Pilots aboard: " + online;
        return "{\"description\":\"" + esc(desc) + "\",\"color\":5793266}";
    }

    // ── /leaderboard ──────────────────────────────────────────────────────────

    private static final int LEADERBOARD_SIZE = 10;
    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    /**
     * Top 10 card, by playtime (default) or by how long ago the player joined ({@code oldest}).
     * Both rankings print BOTH numbers, since "who has been here longest" and "who has played most"
     * are the two things people actually argue about.
     *
     * <p>Reads {@link com.coffeesaerosmp.auth.db.ProfileStore#getAll()}, which is the in-memory cache
     * (every profile is loaded at boot) — NOT a query. A slash command must be answered inside 3
     * seconds and this runs on the server thread, so a synchronous remote-MySQL call here would be
     * the 07-18 freeze vector all over again.</p>
     *
     * <p>Names are printed as PLAIN TEXT, never as {@code <@id>} mentions: a public leaderboard that
     * pings ten people every time someone runs it is a good way to get the bot muted, and unlinked
     * players have no snowflake to mention anyway. Display names (not account usernames) are used,
     * matching NameMask everywhere else.</p>
     */
    public static String leaderboardEmbedJson(MinecraftServer server, String board) {
        boolean byAge   = "oldest".equalsIgnoreCase(board);
        boolean allTime = "alltime".equalsIgnoreCase(board);
        var store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) {
            return "{\"description\":\"" + esc("⚠️ Profiles aren't loaded yet — try again in a moment.")
                + "\",\"color\":16776960}";
        }

        record Row(String name, long seconds, long joined) {}
        java.util.List<Row> rows = new java.util.ArrayList<>();
        for (var p : store.getAll()) {
            if (p == null || p.getUUID() == null) continue;
            // Default board is THIS SEASON: lifetime minus the total frozen at the last rollover.
            // total_playtime is deliberately never zeroed (milestones, /uptime and account history
            // are lifetime facts), so the season board is a subtraction, not a reset. clamped at 0
            // because an admin /authmod setplaytime below the frozen figure would otherwise go
            // negative and sort to the bottom of a DESC board as if they had never played.
            long secs = (byAge || allTime)
                ? p.totalPlaytimeSeconds
                : Math.max(0L, p.totalPlaytimeSeconds - p.season1PlaytimeSeconds);
            // Add the live session so the card is current while someone is playing. Gated on the
            // player actually being ONLINE: a session that was never closed (hard restart, crash)
            // leaves a stale sessionStartEpoch that would otherwise inflate them by days.
            if (p.sessionStartEpoch > 0 && server.getPlayerList().getPlayer(p.getUUID()) != null) {
                secs += (System.currentTimeMillis() - p.sessionStartEpoch) / 1000;
            }
            String name = (p.displayName != null && !p.displayName.isBlank()) ? p.displayName : p.username;
            if (name == null || name.isBlank()) continue;
            if (!byAge && secs <= 0) continue;          // don't pad the hours board with 0h ghosts
            rows.add(new Row(name, secs, p.joinDate));
        }
        // Explicit type witness on the reversed() branch: Comparator.comparingLong(Row::seconds) is
        // the receiver of a call, not a poly expression, so javac has no target type to infer T from.
        java.util.Comparator<Row> order = byAge
            ? java.util.Comparator.comparingLong(Row::joined)                  // earliest first
            : java.util.Comparator.<Row>comparingLong(Row::seconds).reversed();
        rows.sort(order);
        if (byAge) rows.removeIf(r -> r.joined() <= 0);  // unknown join date can't be ranked by age

        String season = "Season " + com.coffeesaerosmp.auth.db.SeasonMigration.CURRENT_SEASON;
        String title = byAge   ? "🏆 Longest-serving pilots"
                     : allTime ? "🏆 Top pilots by hours flown (all time)"
                               : "🏆 Top pilots by hours flown — " + season;
        if (rows.isEmpty()) {
            return "{\"title\":\"" + esc(title) + "\",\"description\":\""
                + esc(byAge || allTime
                    ? "Nobody has logged any flight time yet."
                    : "🛫 " + season + " has just begun — nobody has logged any flight time yet. "
                      + "Use `/leaderboard board:Most hours flown (all time)` for the lifetime board.")
                + "\",\"color\":5793266}";
        }

        StringBuilder desc = new StringBuilder();
        int shown = Math.min(LEADERBOARD_SIZE, rows.size());
        for (int i = 0; i < shown; i++) {
            Row r = rows.get(i);
            String rank = i < MEDALS.length ? MEDALS[i] : "`" + (i + 1) + ".`";
            desc.append(rank).append(" **").append(r.name()).append("** — ")
                .append(formatPlaytime(r.seconds()));
            if (r.joined() > 0) {
                // <t:unix:D> renders in each viewer's own locale and timezone.
                desc.append(" · joined <t:").append(r.joined() / 1000L).append(":D>");
            }
            desc.append('\n');
        }
        // Wording matters here: on the season board `rows` is only the people who have flown SINCE
        // the rollover, so "N pilots on the roster" would understate the roster rather than describe it.
        desc.append("\n*")
            .append(byAge   ? rows.size() + " pilots on the roster · sorted by join date"
                  : allTime ? rows.size() + " pilots on the roster · sorted by lifetime playtime"
                            : rows.size() + " pilot" + (rows.size() == 1 ? " has" : "s have")
                              + " flown this season · " + season)
            .append('*');

        return "{\"title\":\"" + esc(com.coffeesaerosmp.auth.config.AuthConfig.SERVER_DISPLAY_NAME.get()
                + " — " + title)
            + "\",\"description\":\"" + esc(desc.toString()) + "\",\"color\":5793266}";
    }

    private static String formatPlaytime(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private static String extractOption(JsonObject data, String optionName) {
        try {
            for (JsonElement o : data.getAsJsonArray("options")) {
                JsonObject obj = o.getAsJsonObject();
                if (optionName.equals(obj.get("name").getAsString())) return obj.get("value").getAsString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Discord user id — {@code member.user.id} in a guild, {@code user.id} in a DM. */
    private static String interactionUserId(JsonObject d) {
        try {
            if (d.has("member") && d.getAsJsonObject("member").has("user"))
                return d.getAsJsonObject("member").getAsJsonObject("user").get("id").getAsString();
            if (d.has("user")) return d.getAsJsonObject("user").get("id").getAsString();
        } catch (Exception ignored) {}
        return "";
    }

    private void doApprove(String mcName, String clicker, String id, String token) {
        server.execute(() -> {
            boolean ok = CoffeesAeroAuth.APPROVAL_QUEUE != null && CoffeesAeroAuth.APPROVAL_QUEUE.adminApprove(mcName);
            String msg = ok ? "✅ **" + mcName + "** approved by " + clicker
                            : "⚠️ **" + mcName + "** is no longer in the queue.";
            if (CoffeesAeroAuth.WATCHDOG != null)
                CoffeesAeroAuth.WATCHDOG.recordNameChange("DISCORD_APPROVE by=" + clicker + " player=" + mcName + " ok=" + ok);
            rest.respondInteraction(id, token, updateMessageJson(msg, ok ? 0x57F287 : 0xFEE75C));
        });
    }

    private void doReject(String mcName, String reason, String clicker, String id, String token) {
        String safe = (reason == null || reason.isBlank()) ? "No reason given" : reason;
        server.execute(() -> {
            boolean ok = CoffeesAeroAuth.APPROVAL_QUEUE != null && CoffeesAeroAuth.APPROVAL_QUEUE.adminReject(mcName, safe);
            String msg = ok ? "❌ **" + mcName + "** rejected by " + clicker + "\nReason: " + safe
                            : "⚠️ **" + mcName + "** is no longer in the queue.";
            if (CoffeesAeroAuth.WATCHDOG != null)
                CoffeesAeroAuth.WATCHDOG.recordNameChange("DISCORD_REJECT by=" + clicker + " player=" + mcName + " reason=" + safe + " ok=" + ok);
            rest.respondInteraction(id, token, updateMessageJson(msg, ok ? 0xED4245 : 0xFEE75C));
        });
    }

    // ── Feature B: watchdog moderation buttons ────────────────────────────────

    private void doWatchdogBan(String ip, String clicker, String id, String token) {
        server.execute(() -> {
            boolean ok = CoffeesAeroAuth.WATCHDOG != null;
            if (ok) {
                CoffeesAeroAuth.WATCHDOG.getIpBanManager().ban(ip, 60 * 60 * 1000L);
                CoffeesAeroAuth.WATCHDOG.recordModAction("DISCORD_BAN by=" + clicker + " ip=" + ip);
            }
            rest.respondInteraction(id, token, updateMessageJson(
                ok ? "⛔ **" + ip + "** banned for 1 hour by " + clicker
                   : "⚠️ Watchdog is unavailable.", ok ? 0xED4245 : 0xFEE75C));
        });
    }

    private void doWatchdogUnban(String key, boolean subnet, String clicker, String id, String token) {
        server.execute(() -> {
            boolean ok = CoffeesAeroAuth.WATCHDOG != null;
            if (ok) {
                if (subnet) CoffeesAeroAuth.WATCHDOG.getIpBanManager().clearByKey("subnet:" + key);
                else        CoffeesAeroAuth.WATCHDOG.getIpBanManager().clearBan(key);
                CoffeesAeroAuth.WATCHDOG.recordModAction("DISCORD_UNBAN by=" + clicker
                    + " key=" + (subnet ? "subnet:" + key : key));
            }
            rest.respondInteraction(id, token, updateMessageJson(
                ok ? "✅ **" + (subnet ? key + ".*" : key) + "** unbanned by " + clicker
                   : "⚠️ Watchdog is unavailable.", ok ? 0x57F287 : 0xFEE75C));
        });
    }

    private void openRejectModal(String mcName, String id, String token) {
        String modal = "{\"type\":9,\"data\":{"
            + "\"custom_id\":\"rejectmodal:" + esc(mcName) + "\","
            + "\"title\":\"Reject " + esc(mcName) + "\","
            + "\"components\":[{\"type\":1,\"components\":[{"
            + "\"type\":4,\"custom_id\":\"reason\",\"label\":\"Reason (shown to the player)\","
            + "\"style\":2,\"min_length\":1,\"max_length\":200,\"required\":true}]}]}}";
        rest.respondInteraction(id, token, modal);
    }

    private static String extractModalValue(JsonObject data, String fieldCustomId) {
        try {
            for (JsonElement row : data.getAsJsonArray("components"))
                for (JsonElement comp : row.getAsJsonObject().getAsJsonArray("components")) {
                    JsonObject c = comp.getAsJsonObject();
                    if (fieldCustomId.equals(c.get("custom_id").getAsString())) return c.get("value").getAsString();
                }
        } catch (Exception ignored) {}
        return "";
    }

    /** type 7 UPDATE_MESSAGE — replace embed + clear buttons so it can't be clicked twice. */
    private static String updateMessageJson(String description, int color) {
        return "{\"type\":7,\"data\":{\"components\":[],\"embeds\":[{"
            + "\"description\":\"" + esc(description) + "\",\"color\":" + color + "}]}}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
