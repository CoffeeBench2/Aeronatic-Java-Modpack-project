package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Thin wrapper around Discord's webhook HTTP API. Fire-and-forget, async. */
public final class DiscordWebhook {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private DiscordWebhook() {}

    /**
     * Sends a JSON payload to a webhook URL asynchronously.
     * Silently no-ops if url is blank. Logs warnings on HTTP errors but never throws.
     */
    public static void send(String webhookUrl, String jsonPayload) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(10))
                .build();
            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 429) {
                        CoffeesAeroAuth.LOGGER.warn("[Discord] Webhook rate limited — response: {}", resp.body());
                    } else if (resp.statusCode() >= 400) {
                        CoffeesAeroAuth.LOGGER.warn("[Discord] Webhook HTTP {}: {}", resp.statusCode(), resp.body());
                    }
                })
                .exceptionally(ex -> {
                    CoffeesAeroAuth.LOGGER.warn("[Discord] Webhook send failed: {}", ex.getMessage());
                    return null;
                });
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Discord] Webhook exception: {}", e.getMessage());
        }
    }
}
