package com.coffeesaerosmp.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Single responsibility: query the Mojang profile API for one username.
 *
 * <p>Knows nothing about Velocity, caching, or login flow — it only turns a username into a
 * {@link Result}. Three outcomes are distinguished so callers can apply the correct security
 * policy:</p>
 * <ul>
 *   <li>{@link Status#PREMIUM} — Mojang has a paid account with this name ({@code 200}).</li>
 *   <li>{@link Status#NOT_PREMIUM} — no such account ({@code 204}/{@code 404}).</li>
 *   <li>{@link Status#UNAVAILABLE} — rate-limited, timed out, or errored ({@code 429}/{@code 5xx}/IO).
 *       The caller must NOT assume offline on this result, or a premium name could be impersonated.</li>
 * </ul>
 */
public class MojangApiClient {

    public enum Status { PREMIUM, NOT_PREMIUM, UNAVAILABLE }

    public record Result(Status status, UUID uuid) {
        public static Result premium(UUID uuid) { return new Result(Status.PREMIUM, uuid); }
        public static final Result NOT_PREMIUM = new Result(Status.NOT_PREMIUM, null);
        public static final Result UNAVAILABLE = new Result(Status.UNAVAILABLE, null);
    }

    private static final String PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4))
        .build();

    public CompletableFuture<Result> lookup(String username) {
        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                .uri(URI.create(PROFILE_URL + username))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();
        } catch (Exception malformed) {
            // An illegal username can't be a Mojang account — treat as definitively not premium.
            return CompletableFuture.completedFuture(Result.NOT_PREMIUM);
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                int code = response.statusCode();
                if (code == 200) {
                    try {
                        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
                        String rawId = body.get("id").getAsString();
                        String formatted = rawId.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");
                        return Result.premium(UUID.fromString(formatted));
                    } catch (Exception parseError) {
                        return Result.UNAVAILABLE; // 200 but unparsable — don't trust it
                    }
                }
                if (code == 204 || code == 404) {
                    return Result.NOT_PREMIUM;
                }
                // 429 (rate limit), 5xx, anything else → unavailable, never assume offline.
                return Result.UNAVAILABLE;
            })
            .exceptionally(e -> Result.UNAVAILABLE);
    }
}
