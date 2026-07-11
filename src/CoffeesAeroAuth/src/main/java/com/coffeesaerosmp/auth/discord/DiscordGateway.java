package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Minimal Discord Gateway (WebSocket) client.
 * Handles: opcode 10 Hello, 11 ACK, 1 Heartbeat, 0 Dispatch (READY + MESSAGE_CREATE +
 * INTERACTION_CREATE), 7 Reconnect.
 *
 * <p><b>Send discipline (the 4002 fix, 1.6.10):</b> every outbound frame — identify, heartbeat,
 * presence — is executed on ONE dedicated sender thread. The previous design sent from whatever
 * thread was handy (the WebSocket listener thread inside its own callback, plus a separate
 * heartbeat scheduler); interleaved {@code sendText} calls corrupted frames and Discord closed the
 * session with 4002 "Error while decoding payload" in a tight reconnect loop. Presence updates are
 * additionally coalesced and rate-limited, the last outbound frame is logged on any 4002 so the
 * offending payload is visible in the server log, reconnects back off exponentially, and presence
 * auto-suppresses after three consecutive presence-adjacent 4002s instead of looping forever.</p>
 */
public class DiscordGateway {

    private static final String  GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";
    // serializeNulls is LOAD-BEARING: default Gson strips JsonNull entries, which silently removed
    // the REQUIRED-but-nullable "since" from op-3 presence (and "d" from pre-seq heartbeats) —
    // Discord answers a missing required field with close 4002 "Error while decoding payload".
    // THIS was the 1.6.7/1.6.8 presence 4002 loop all along (proven by the frame log on 07-11).
    private static final Gson    GSON        = new GsonBuilder().serializeNulls().create();
    // GUILD_MESSAGES (512) | MESSAGE_CONTENT (32768)
    private static final int     INTENTS     = 512 | 32768;
    private static final long    PRESENCE_MIN_INTERVAL_MS = 15_000;
    private static final int     PRESENCE_4002_SUPPRESS_AFTER = 3;

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
    private volatile String    applicationId = null;   // from READY — needed to register slash commands
    private volatile Runnable  readyCallback = null;   // invoked once per READY (slash registration etc.)

    /** Single thread that owns EVERY outbound frame + the heartbeat schedule. */
    private ScheduledExecutorService sender;
    private ScheduledFuture<?>       heartbeatTask;

    // ── 4002 forensics / backoff state ────────────────────────────────────────
    private volatile String  lastSentFrame      = "";
    private volatile long    sessionStartedAt   = 0;
    private volatile int     consecutive4002    = 0;
    private volatile boolean presenceSuppressed = false;
    private volatile int     reconnectDelaySec  = 5;
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    // ── Presence coalescing ───────────────────────────────────────────────────
    private volatile String presenceDesired = "0 pilots aboard ✈";
    private volatile String presenceSent    = null;
    private volatile long   presenceSentAt  = 0;
    private volatile boolean presenceFlushQueued = false;

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

    /** Application id from READY, or null before the first READY. */
    public String getApplicationId() { return applicationId; }

    /** Runs on every READY (sender thread) — used to (re)register slash commands. */
    public void setReadyCallback(Runnable cb) { this.readyCallback = cb; }

    public void connect() {
        if (botToken == null || botToken.isBlank()) return;
        try {
            if (sender == null || sender.isShutdown()) {
                sender = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "AeroAuth-GW-Sender"); t.setDaemon(true); return t;
                });
            }
            HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(GATEWAY_URL), new Listener())
                .exceptionally(ex -> {
                    CoffeesAeroAuth.LOGGER.error("[Discord] Gateway connect failed: {}", ex.getMessage());
                    scheduleReconnect();
                    return null;
                });
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Discord] Gateway init error", e);
        }
    }

    public void disconnect() {
        connected = false;
        stopHeartbeat();
        if (sender != null) sender.shutdownNow();
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
                case 1  -> submitHeartbeat();
                case 7  -> reconnect();   // Server-requested reconnect
                case 9  -> { // Invalid session
                    CoffeesAeroAuth.LOGGER.warn("[Discord] Invalid session — reconnecting");
                    scheduleReconnect();
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
                sessionStartedAt = System.currentTimeMillis();
                try {
                    if (d.has("application") && d.getAsJsonObject("application").has("id"))
                        applicationId = d.getAsJsonObject("application").get("id").getAsString();
                } catch (Exception ignored) {}
                CoffeesAeroAuth.LOGGER.info("[Discord] Gateway bot ready.");
                if (com.coffeesaerosmp.auth.config.AuthConfig.DISCORD_BOT_PRESENCE.get()) requestPresenceFlush();
                Runnable cb = readyCallback;
                if (cb != null && sender != null) sender.execute(() -> {
                    try { cb.run(); } catch (Exception e) {
                        CoffeesAeroAuth.LOGGER.warn("[Discord] ready callback error: {}", e.getMessage());
                    }
                });
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

    // ── Heartbeat (runs entirely on the sender thread) ────────────────────────

    private void startHeartbeat(int intervalMs) {
        stopHeartbeat();
        if (sender == null) return;
        long jitter = (long)(intervalMs * Math.random());   // initial jitter per Discord docs
        heartbeatTask = sender.scheduleAtFixedRate(this::sendHeartbeatNow, jitter, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) { heartbeatTask.cancel(false); heartbeatTask = null; }
    }

    private void submitHeartbeat() {
        if (sender != null) sender.execute(this::sendHeartbeatNow);
    }

    /** Sender thread only. */
    private void sendHeartbeatNow() {
        JsonObject p = new JsonObject();
        p.addProperty("op", 1);
        if (lastSeq >= 0) p.addProperty("d", lastSeq);
        else p.add("d", JsonNull.INSTANCE);
        sendNow(GSON.toJson(p));
    }

    // ── Presence (coalesced + rate-limited, sender thread only) ───────────────

    /** Live "N pilots aboard" bot status. Safe to call from any thread; coalesced on the sender. */
    public void updatePresence(int online) {
        if (!com.coffeesaerosmp.auth.config.AuthConfig.DISCORD_BOT_PRESENCE.get()) return;
        presenceDesired = online + (online == 1 ? " pilot" : " pilots") + " aboard ✈";
        requestPresenceFlush();
    }

    private void requestPresenceFlush() {
        if (sender == null || presenceSuppressed) return;
        sender.execute(this::flushPresence);
    }

    /** Sender thread only. Sends at most one op-3 per {@link #PRESENCE_MIN_INTERVAL_MS}. */
    private void flushPresence() {
        if (!connected || ws == null || presenceSuppressed) return;
        String desired = presenceDesired;
        if (desired == null || desired.equals(presenceSent)) return;
        long wait = presenceSentAt + PRESENCE_MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) {
            if (!presenceFlushQueued) {
                presenceFlushQueued = true;
                sender.schedule(() -> { presenceFlushQueued = false; flushPresence(); },
                    wait, TimeUnit.MILLISECONDS);
            }
            return;
        }
        try {
            JsonObject p = new JsonObject(); p.addProperty("op", 3);
            JsonObject d = new JsonObject();
            d.add("since", JsonNull.INSTANCE);
            JsonObject a = new JsonObject(); a.addProperty("name", desired); a.addProperty("type", 0);
            JsonArray arr = new JsonArray(); arr.add(a);
            d.add("activities", arr);
            d.addProperty("status", "online"); d.addProperty("afk", false);
            p.add("d", d);
            sendNow(GSON.toJson(p));
            presenceSent   = desired;
            presenceSentAt = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    // ── Identify ──────────────────────────────────────────────────────────────

    private void identify() {
        if (sender == null) return;
        sender.execute(() -> {
            JsonObject p = new JsonObject();
            p.addProperty("op", 2);
            JsonObject d = new JsonObject();
            d.addProperty("token", botToken);
            d.addProperty("intents", INTENTS);
            JsonObject props = new JsonObject();
            props.addProperty("os", "linux");
            props.addProperty("browser", "coffees_aero_auth");
            props.addProperty("device", "coffees_aero_auth");
            d.add("properties", props);
            p.add("d", d);
            sendNow(GSON.toJson(p));
        });
    }

    // ── The one true send ─────────────────────────────────────────────────────

    /** Sender thread only — the single place any frame leaves this process. */
    private void sendNow(String json) {
        WebSocket sock = ws;
        if (sock == null) return;
        lastSentFrame = json;
        try {
            sock.sendText(json, true).join();
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[Discord] send failed: {}", e.getMessage());
        }
    }

    // ── Reconnect ─────────────────────────────────────────────────────────────

    private void reconnect() {
        connected = false;
        stopHeartbeat();
        if (ws != null) ws.abort();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true)) return;
        int delay = reconnectDelaySec;
        reconnectDelaySec = Math.min(reconnectDelaySec * 2, 60);   // exponential backoff, 60s cap
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor();
        s.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
            s.shutdown();
        }, delay, TimeUnit.SECONDS);
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
            presenceSent = null;   // presence is per-session — a reconnected session must re-send it

            long sessionMs = sessionStartedAt > 0 ? System.currentTimeMillis() - sessionStartedAt : 0;
            if (sessionMs > 120_000) {          // stable session — forgive past sins
                consecutive4002 = 0;
                reconnectDelaySec = 5;
            }
            if (code == 4002) {
                consecutive4002++;
                // The whole point of this log line: the exact frame Discord refused to decode.
                CoffeesAeroAuth.LOGGER.warn("[Discord] Gateway closed (4002) {} — last outbound frame: {}",
                    reason, lastSentFrame.length() > 300 ? lastSentFrame.substring(0, 300) + "…" : lastSentFrame);
                if (!presenceSuppressed && consecutive4002 >= PRESENCE_4002_SUPPRESS_AFTER
                        && lastSentFrame.contains("\"op\":3")) {
                    presenceSuppressed = true;
                    CoffeesAeroAuth.LOGGER.error(
                        "[Discord] {} consecutive 4002 closes right after a presence frame — presence "
                        + "auto-disabled for this run. Report the frame above.", consecutive4002);
                }
            } else {
                consecutive4002 = 0;
                CoffeesAeroAuth.LOGGER.warn("[Discord] Gateway closed ({}) {}. Reconnecting...", code, reason);
            }
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable err) {
            CoffeesAeroAuth.LOGGER.error("[Discord] Gateway error: {}", err.getMessage());
        }
    }
}
