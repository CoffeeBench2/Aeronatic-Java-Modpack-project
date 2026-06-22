package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Bot-authenticated Discord REST (v10) — the half webhooks can't do: posting messages that carry
 * interactive components (buttons) and responding to interactions.
 *
 * <ul>
 *   <li>{@link #postMessage} — {@code POST /channels/{id}/messages} with a bot token (needed for components).</li>
 *   <li>{@link #respondInteraction} — {@code POST /interactions/{id}/{token}/callback} (no bot auth; the
 *       interaction token in the URL authorizes it). Used to ACK clicks, update the message, or open a modal.</li>
 * </ul>
 *
 * Fire-and-forget + async; never throws. Discord interaction callbacks MUST be sent within 3 seconds.
 */
public class DiscordRest {

    private static final String API = "https://discord.com/api/v10";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String botToken;

    public DiscordRest(String botToken) {
        this.botToken = botToken == null ? "" : botToken;
    }

    public boolean isConfigured() { return !botToken.isBlank(); }

    /** Posts a message (JSON body may include {@code embeds} and {@code components}) to a channel. */
    public void postMessage(String channelId, String jsonBody) {
        if (botToken.isBlank() || channelId == null || channelId.isBlank()) return;
        send(HttpRequest.newBuilder()
            .uri(URI.create(API + "/channels/" + channelId + "/messages"))
            .header("Authorization", "Bot " + botToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(Duration.ofSeconds(10))
            .build(), "postMessage");
    }

    /**
     * Responds to an interaction. {@code callbackJson} is a full interaction-response object, e.g.
     * {@code {"type":7,"data":{...}}} (UPDATE_MESSAGE), {@code {"type":9,"data":{...}}} (MODAL).
     */
    public void respondInteraction(String interactionId, String interactionToken, String callbackJson) {
        if (interactionId == null || interactionToken == null) return;
        send(HttpRequest.newBuilder()
            .uri(URI.create(API + "/interactions/" + interactionId + "/" + interactionToken + "/callback"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(callbackJson))
            .timeout(Duration.ofSeconds(5))
            .build(), "respondInteraction");
    }

    private void send(HttpRequest req, String what) {
        try {
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400) {
                        CoffeesAeroAuth.LOGGER.warn("[Discord] {} HTTP {}: {}", what, resp.statusCode(), resp.body());
                    }
                })
                .exceptionally(ex -> {
                    CoffeesAeroAuth.LOGGER.warn("[Discord] {} failed: {}", what, ex.getMessage());
                    return null;
                });
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Discord] {} exception: {}", what, e.getMessage());
        }
    }
}
