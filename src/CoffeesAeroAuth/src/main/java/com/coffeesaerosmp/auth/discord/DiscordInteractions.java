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
            int type = d.get("type").getAsInt();           // 3 = component, 5 = modal submit
            String id = d.get("id").getAsString();
            String token = d.get("token").getAsString();
            if (!d.has("data") || !d.get("data").isJsonObject()) return;
            JsonObject data = d.getAsJsonObject("data");
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
