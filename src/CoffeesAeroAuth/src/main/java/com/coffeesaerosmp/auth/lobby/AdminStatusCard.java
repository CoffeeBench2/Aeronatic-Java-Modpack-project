package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.coffeesaerosmp.auth.util.Repeating.guard;

/**
 * The admin "live players" card: ONE message in the admin status channel, edited every minute.
 *
 * <h2>Why the lobby runs it</h2>
 * The lobby already watches Survival by Server List Ping ({@link SmpLiveness}) and knows its own
 * players, so it has both numbers — and it keeps reporting while Survival is down, which is
 * exactly when the card matters most.
 *
 * <h2>Edit, never repost</h2>
 * The message id is saved to {@code <dataDir>/admin-status.json}. If it is lost, the card ADOPTS
 * the oldest message this webhook posted in the channel (the one "on top"), found with the bot
 * token. Only if there is none is a new message posted. A 404 on edit (someone deleted it) clears
 * the id, so the next tick recreates it.
 *
 * <h2>Never near the tick loop</h2>
 * Own daemon thread. The only server-thread work is copying the lobby's player names. Any HTTP
 * failure — including a 429 — is logged when the state changes and skipped until the next tick;
 * nothing is retried in a loop.
 */
public final class AdminStatusCard {

    private AdminStatusCard() {}

    private static final String API = "https://discord.com/api/v10";
    private static final Pattern WEBHOOK = Pattern.compile("/webhooks/(\\d+)/([^/?]+)");

    private static ScheduledExecutorService exec;
    private static MinecraftServer server;
    private static String webhookUrl, webhookId, channelId, botToken;
    private static Path idFile;
    private static String messageId;
    private static String lastProblem = "";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Lobby role only, and only with a webhook configured. {@code envWebhook} wins over the config. */
    public static synchronized void start(MinecraftServer srv, Path dataDir, String envWebhook, String bot) {
        if (exec != null || !LobbyHandoff.isLobbyRole()) return;
        String url;
        int interval;
        try {
            url = envWebhook != null && !envWebhook.isBlank() ? envWebhook : AuthConfig.ADMIN_STATUS_WEBHOOK.get();
            channelId = AuthConfig.ADMIN_STATUS_CHANNEL_ID.get().trim();
            interval = AuthConfig.ADMIN_STATUS_INTERVAL_SECONDS.get();
        } catch (Exception e) { return; }
        if (url == null || url.isBlank()) return;
        Matcher m = WEBHOOK.matcher(url);
        if (!m.find()) {
            CoffeesAeroAuth.LOGGER.warn("[AdminStatus] adminStatusWebhook is not a Discord webhook URL — card disabled.");
            return;
        }
        server = srv;
        webhookUrl = url.trim().replaceAll("\\?.*$", "");
        webhookId = m.group(1);
        botToken = bot == null ? "" : bot.trim();
        idFile = dataDir.resolve("admin-status.json");
        messageId = loadId();

        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AeroLobby-AdminStatus");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(guard("admin-status", AdminStatusCard::tick), 10, interval, TimeUnit.SECONDS);
        CoffeesAeroAuth.LOGGER.info("[AdminStatus] Live player card every {}s.", interval);
    }

    public static synchronized void stop() {
        if (exec != null) exec.shutdownNow();
        exec = null;
        server = null;
    }

    private static void tick() {
        MinecraftServer srv = server;
        if (srv == null) return;
        List<String> lobby;
        try {
            lobby = srv.submit(() -> {
                List<String> n = new ArrayList<>();
                for (ServerPlayer p : srv.getPlayerList().getPlayers()) n.add(p.getGameProfile().getName());
                return n;
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            problem("lobby player list unavailable (server busy)");
            return;
        }
        boolean up = SmpLiveness.isUp();
        long now = System.currentTimeMillis();
        long since = SmpLiveness.lastChangeMs() > 0 ? SmpLiveness.lastChangeMs() : now;  // 0 = never changed
        String body = AdminStatusFormat.build(up, SmpLiveness.onlinePlayers(), SmpLiveness.maxPlayers(),
            SmpLiveness.sampleNames(), since, lobby, now);

        if (messageId == null) messageId = adopt();
        if (messageId == null) {
            messageId = create(body);
            if (messageId != null) saveId(messageId);
            return;
        }
        HttpResponse<String> r = send("PATCH", webhookUrl + "/messages/" + messageId, body, false);
        if (r == null) return;
        if (r.statusCode() == 404) {
            CoffeesAeroAuth.LOGGER.info("[AdminStatus] Card {} is gone — a new one will be posted.", messageId);
            messageId = null;
            saveId("");
        } else if (r.statusCode() >= 400) {
            problem("edit HTTP " + r.statusCode());
        } else {
            ok();
        }
    }

    /** The oldest message this webhook posted in the channel, via the bot. Null if none / no bot. */
    private static String adopt() {
        if (botToken.isEmpty() || channelId.isEmpty()) return null;
        HttpResponse<String> r = send("GET", API + "/channels/" + channelId + "/messages?after=0&limit=50", null, true);
        if (r == null || r.statusCode() != 200) {
            if (r != null) problem("bot could not read the channel (HTTP " + r.statusCode() + ")");
            return null;
        }
        String oldest = null;
        long oldestId = Long.MAX_VALUE;
        for (JsonElement el : JsonParser.parseString(r.body()).getAsJsonArray()) {
            JsonObject msg = el.getAsJsonObject();
            if (msg.has("webhook_id") && webhookId.equals(msg.get("webhook_id").getAsString())) {
                long id = Long.parseLong(msg.get("id").getAsString());
                if (id < oldestId) { oldestId = id; oldest = msg.get("id").getAsString(); }
            }
        }
        if (oldest != null) {
            CoffeesAeroAuth.LOGGER.info("[AdminStatus] Adopted the existing card {} at the top of the channel.", oldest);
            saveId(oldest);
        }
        return oldest;
    }

    private static String create(String body) {
        HttpResponse<String> r = send("POST", webhookUrl + "?wait=true", body, false);
        if (r == null) return null;
        if (r.statusCode() >= 300) { problem("post HTTP " + r.statusCode()); return null; }
        String id = JsonParser.parseString(r.body()).getAsJsonObject().get("id").getAsString();
        CoffeesAeroAuth.LOGGER.info("[AdminStatus] Posted a new card {}.", id);
        ok();
        return id;
    }

    private static HttpResponse<String> send(String method, String url, String body, boolean bot) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                             : HttpRequest.BodyPublishers.ofString(body));
            if (bot) b.header("Authorization", "Bot " + botToken);
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 429) { problem("rate limited by Discord — skipping this minute"); return null; }
            return r;
        } catch (Exception e) {
            problem("Discord unreachable (" + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    /** Logs a problem once per distinct problem, never every minute. */
    private static void problem(String what) {
        if (!what.equals(lastProblem)) CoffeesAeroAuth.LOGGER.warn("[AdminStatus] {}", what);
        lastProblem = what;
    }

    private static void ok() {
        if (!lastProblem.isEmpty()) CoffeesAeroAuth.LOGGER.info("[AdminStatus] Card updating again.");
        lastProblem = "";
    }

    private static String loadId() {
        try {
            if (!Files.exists(idFile)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(idFile)).getAsJsonObject();
            String id = o.has("messageId") ? o.get("messageId").getAsString() : "";
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveId(String id) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("messageId", id);
            Files.createDirectories(idFile.getParent());
            Files.writeString(idFile, o.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            problem("could not save the card id: " + e.getMessage());
        }
    }
}
