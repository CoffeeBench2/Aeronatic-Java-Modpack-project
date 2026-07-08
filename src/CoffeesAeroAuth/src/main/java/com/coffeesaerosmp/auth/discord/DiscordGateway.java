package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Minimal Discord Gateway (WebSocket) client — receive-only.
 * Handles: opcode 10 Hello, 11 ACK, 1 Heartbeat, 0 Dispatch (READY + MESSAGE_CREATE), 7 Reconnect.
 * Uses Java 11+ HttpClient WebSocket — no external library required.
 */
public class DiscordGateway {

    private static final String  GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    private static final Gson    GSON        = new Gson();
    // GUILD_MESSAGES (512) | MESSAGE_CONTENT (32768)
    private static final int     INTENTS     = 512 | 32768;

    private final MinecraftServer            server;
    private final String                     botToken;
    private final String                     channelId;
    private final String                     allowedRoleId;
    private final BiConsumer<String, String> messageHandler; // (authorName, content) → MC
    private final String                     adminChannelId;  // Feature B: admin console channel
    private final String                     adminRoleId;     // Feature B: role gating console commands
    private final BiConsumer<String, String> adminHandler;    // (authorName, content) → run as command
    private final java.util.function.Consumer<JsonObject> interactionHandler; // INTERACTION_CREATE 'd' → handler

    private volatile WebSocket ws;
    private volatile boolean   connected  = false;
    private volatile int       lastSeq    = -1;
    private ScheduledExecutorService heartbeat;

    public DiscordGateway(MinecraftServer server, String botToken,
                          String channelId, String allowedRoleId,
                          BiConsumer<String, String> messageHandler,
                          String adminChannelId, String adminRoleId,
                          BiConsumer<String, String> adminHandler,
                          java.util.function.Consumer<JsonObject> interactionHandler) {
        this.server         = server;
        this.botToken       = botToken;
        this.channelId      = channelId;
        this.allowedRoleId  = allowedRoleId;
        this.messageHandler = messageHandler;
        this.adminChannelId = adminChannelId == null ? "" : adminChannelId;
        this.adminRoleId    = adminRoleId == null ? "" : adminRoleId;
        this.adminHandler   = adminHandler;
        this.interactionHandler = interactionHandler;
    }

    public void connect() {
        if (botToken == null || botToken.isBlank()) return;
        try {
            HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(GATEWAY_URL), new Listener())
                .exceptionally(ex -> {
                    CoffeesAeroAuth.LOGGER.error("[Discord] Gateway connect failed: {}", ex.getMessage());
                    scheduleReconnect(10);
                    return null;
                });
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Discord] Gateway init error", e);
        }
    }

    public void disconnect() {
        connected = false;
        stopHeartbeat();
        if (ws != null) ws.abort();
    }

    public boolean isConnected() { return connected; }

    // ── Payload handler ───────────────────────────────────────────────────────

    private void handle(String json) {
        try {
            JsonObject p = JsonParser.parseString(json).getAsJsonObject();
            int op = p.get("op").getAsInt();
            if (p.has("s") && !p.get("s").isJsonNull()) lastSeq = p.get("s").getAsInt();

            switch (op) {
                case 10 -> { // Hello
                    int interval = p.getAsJsonObject("d").get("heartbeat_interval").getAsInt();
                    startHeartbeat(interval);
                    identify();
                }
                case 11 -> {} // Heartbeat ACK
                case 1  -> sendHeartbeat();
                case 7  -> reconnect();   // Server-requested reconnect
                case 9  -> { // Invalid session
                    CoffeesAeroAuth.LOGGER.warn("[Discord] Invalid session — reconnecting");
                    scheduleReconnect(5);
                }
                case 0  -> {  // Dispatch
                    String t = p.has("t") && !p.get("t").isJsonNull() ? p.get("t").getAsString() : "";
                    JsonElement d = p.get("d");
                    if (d != null && d.isJsonObject()) dispatch(t, d.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Discord] Payload parse error: {}", e.getMessage());
        }
    }

    private void dispatch(String type, JsonObject d) {
        switch (type) {
            case "READY" -> {
                connected = true;
                CoffeesAeroAuth.LOGGER.info("[Discord] Gateway bot ready.");
                sendPresence();
            }
            case "MESSAGE_CREATE" -> {
                String chan = d.get("channel_id").getAsString();
                JsonObject author = d.getAsJsonObject("author");
                if (author.has("bot") && author.get("bot").getAsBoolean()) return;
                String name    = author.get("username").getAsString();
                String content = d.has("content") && !d.get("content").isJsonNull()
                    ? d.get("content").getAsString() : "";

                // Feature B: admin console channel → run as server command (admin-role gated).
                if (!adminChannelId.isBlank() && adminChannelId.equals(chan)) {
                    if (!hasRole(d, adminRoleId)) return;
                    if (!content.isBlank() && adminHandler != null) {
                        server.execute(() -> adminHandler.accept(name, content));
                    }
                    return;
                }

                // Public chat bridge channel → broadcast to MC chat.
                if (!channelId.equals(chan)) return;
                if (!hasRole(d, allowedRoleId)) return;
                if (!content.isBlank() && messageHandler != null) {
                    server.execute(() -> messageHandler.accept(name, content));
                }
            }
            case "INTERACTION_CREATE" -> {
                if (interactionHandler != null) interactionHandler.accept(d);
            }
        }
    }

    /** True if the message author carries {@code roleId}. Blank roleId = no gating (LOCAL TESTING ONLY). */
    private static boolean hasRole(JsonObject d, String roleId) {
        if (roleId == null || roleId.isBlank()) return true;
        JsonElement member = d.get("member");
        if (member != null && member.isJsonObject()) {
            JsonArray roles = member.getAsJsonObject().getAsJsonArray("roles");
            if (roles != null) for (JsonElement r : roles) {
                if (roleId.equals(r.getAsString())) return true;
            }
        }
        return false;
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    private void startHeartbeat(int intervalMs) {
        stopHeartbeat();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AeroAuth-GW-Heartbeat"); t.setDaemon(true); return t;
        });
        // Initial jitter as recommended by Discord docs
        long jitter = (long)(intervalMs * Math.random());
        heartbeat.scheduleAtFixedRate(this::sendHeartbeat, jitter, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeat != null) heartbeat.shutdownNow();
    }

    private void sendHeartbeat() {
        if (ws == null) return;
        JsonObject p = new JsonObject();
        p.addProperty("op", 1);
        if (lastSeq >= 0) p.addProperty("d", lastSeq);
        else p.add("d", JsonNull.INSTANCE);
        ws.sendText(GSON.toJson(p), true);
    }

    // ── Identify ──────────────────────────────────────────────────────────────

    /** Live "N pilots aboard" bot status. Safe to call anytime; sent when connected. */
    public void updatePresence(int online) {
        presenceText = online + (online == 1 ? " pilot" : " pilots") + " aboard ✈";
        sendPresence();
    }
    private volatile String presenceText = "0 pilots aboard 2708";
    private void sendPresence() {
        if (!connected || ws == null || presenceText == null) return;
        try {
            JsonObject p = new JsonObject(); p.addProperty("op", 3);
            JsonObject d = new JsonObject();
            d.add("since", JsonNull.INSTANCE);
            JsonObject a = new JsonObject(); a.addProperty("name", presenceText); a.addProperty("type", 0);
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray(); arr.add(a);
            d.add("activities", arr);
            d.addProperty("status", "online"); d.addProperty("afk", false);
            p.add("d", d);
            ws.sendText(GSON.toJson(p), true);
        } catch (Exception ignored) {}
    }

    private void identify() {
        JsonObject p = new JsonObject();
        p.addProperty("op", 2);
        JsonObject d = new JsonObject();
        d.addProperty("token", "Bot " + botToken);
        d.addProperty("intents", INTENTS);
        JsonObject props = new JsonObject();
        props.addProperty("os", "linux");
        props.addProperty("browser", "coffees_aero_auth");
        props.addProperty("device", "coffees_aero_auth");
        d.add("properties", props);
        p.add("d", d);
        ws.sendText(GSON.toJson(p), true);
    }

    private void reconnect() {
        connected = false;
        stopHeartbeat();
        if (ws != null) ws.abort();
        scheduleReconnect(2);
    }

    private void scheduleReconnect(int delaySec) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor();
        s.schedule(() -> { connect(); s.shutdown(); }, delaySec, TimeUnit.SECONDS);
    }

    // ── WebSocket Listener ────────────────────────────────────────────────────

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            DiscordGateway.this.ws = ws;
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) { handle(buf.toString()); buf.setLength(0); }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            connected = false;
            stopHeartbeat();
            CoffeesAeroAuth.LOGGER.warn("[Discord] Gateway closed ({}) {}. Reconnecting...", code, reason);
            scheduleReconnect(code == 4000 ? 2 : 10);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable err) {
            CoffeesAeroAuth.LOGGER.error("[Discord] Gateway error: {}", err.getMessage());
        }
    }
}
