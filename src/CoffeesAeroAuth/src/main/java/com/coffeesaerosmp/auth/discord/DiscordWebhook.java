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
     * Sends a JSON payload and DISCARDS the rate-limit answer.
     *
     * @deprecated This is the duplicate transport path that caused the 2026-08-08 bridge outage:
     *     it throws away the {@code retry_after} from {@link #sendForRetry}, so a payload that meets
     *     a 429 is gone with no retry and no warning. Nothing may call it — post through
     *     {@link WebhookQueue#enqueue}, which owns backoff, retry and ordering for the whole bridge.
     *     Kept only so any out-of-tree caller fails loudly at compile time rather than silently
     *     losing messages at runtime.
     */
    @Deprecated(forRemoval = true)
    public static void send(String webhookUrl, String jsonPayload) {
        sendForRetry(webhookUrl, jsonPayload);
    }

    /**
     * As {@link #send}, but completes with the number of MILLISECONDS the caller must wait before
     * its next request, or 0 when the send succeeded.
     *
     * <p>WHY THIS EXISTS (1.7.5): the old fire-and-forget send logged a 429 and then DROPPED the
     * message, and {@link WebhookQueue} kept draining on a fixed 1/second timer regardless. Discord
     * allows ~5 requests per 2s per webhook, so any burst (a busy chat minute, AeroKit alerts) put
     * the queue into a state where it re-sent into a live rate limit every single second — that is
     * the wall of "Webhook rate limited — retry_after: 1.0" in the 2026-08-04 log, and every one of
     * those lines is a chat message the community server never received.
     *
     * <p>Discord returns {@code retry_after} in SECONDS as a float in the JSON body (and in the
     * {@code Retry-After} header). We prefer the header, fall back to parsing the body, and floor
     * at 1s so a malformed response can never spin.
     */
    public static java.util.concurrent.CompletableFuture<Long> sendForRetry(String webhookUrl, String jsonPayload) {
        if (webhookUrl == null || webhookUrl.isBlank() || jsonPayload == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(0L);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(Duration.ofSeconds(10))
                .build();
            return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 429) {
                        long waitMs = parseRetryAfterMs(resp);
                        // Deliberately DEBUG, not WARN: being rate limited is now handled, not an
                        // incident. The queue re-sends the payload after the wait.
                        CoffeesAeroAuth.LOGGER.debug("[Discord] Rate limited — backing off {}ms.", waitMs);
                        return waitMs;
                    }
                    if (resp.statusCode() >= 400) {
                        CoffeesAeroAuth.LOGGER.warn("[Discord] Webhook HTTP {}: {}", resp.statusCode(), resp.body());
                    }
                    return 0L;
                })
                .exceptionally(ex -> {
                    CoffeesAeroAuth.LOGGER.warn("[Discord] Webhook send failed: {}", ex.getMessage());
                    return 0L;      // network blip: drop it rather than retry-loop forever
                });
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Discord] Webhook exception: {}", e.getMessage());
            return java.util.concurrent.CompletableFuture.completedFuture(0L);
        }
    }

    private static long parseRetryAfterMs(HttpResponse<String> resp) {
        try {
            var header = resp.headers().firstValue("Retry-After");
            if (header.isPresent()) {
                double secs = Double.parseDouble(header.get().trim());
                return Math.max(1000L, (long) (secs * 1000.0) + 250L);
            }
        } catch (Exception ignored) {}
        try {
            String body = resp.body();
            int i = body.indexOf("\"retry_after\"");
            if (i >= 0) {
                int colon = body.indexOf(':', i);
                int end = colon + 1;
                while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '.')) end++;
                double secs = Double.parseDouble(body.substring(colon + 1, end).trim());
                return Math.max(1000L, (long) (secs * 1000.0) + 250L);
            }
        } catch (Exception ignored) {}
        return 2000L;
    }
}
