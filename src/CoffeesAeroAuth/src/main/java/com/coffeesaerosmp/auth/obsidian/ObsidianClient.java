package com.coffeesaerosmp.auth.obsidian;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Async HTTP client for the Obsidian Local REST API.
 * All writes are fire-and-forget on a virtual thread — never blocks the server tick.
 * If Obsidian is offline or the request fails after one retry, it is silently dropped.
 */
public class ObsidianClient {

    private final String     baseUrl;
    private final String     apiKey;
    private final HttpClient http;

    public ObsidianClient(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey  = apiKey;
        this.http    = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** PUT /vault/{vaultRoot}/{filePath} — creates or replaces the entire file. */
    public void put(String vaultRoot, String filePath, String markdown) {
        sendAsync("PUT", vaultRoot + "/" + filePath, markdown);
    }

    /** POST /vault/{vaultRoot}/{filePath} — appends content to an existing file (creates if absent). */
    public void append(String vaultRoot, String filePath, String content) {
        sendAsync("POST", vaultRoot + "/" + filePath, content);
    }

    /**
     * Synchronous read — only used during /authmod obsidianreport full-sync.
     * Returns null if Obsidian is unreachable or the file does not exist.
     */
    public String read(String vaultRoot, String filePath) {
        try {
            HttpRequest req = buildRequest("GET", vaultRoot + "/" + filePath, null);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Quick reachability probe — logs result but never throws. */
    public boolean ping() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/"))
                .header("Authorization", "Bearer " + apiKey)
                .GET().timeout(Duration.ofSeconds(3)).build();
            int status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            return status < 500;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void sendAsync(String method, String vaultRelPath, String body) {
        CompletableFuture.runAsync(() -> {
            try {
                attempt(method, vaultRelPath, body, 2);
            } catch (Exception ignored) {
                // Silently drop — Obsidian is optional infrastructure
            }
        });
    }

    private void attempt(String method, String path, String body, int attemptsLeft) throws Exception {
        try {
            HttpRequest req = buildRequest(method, path, body);
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() >= 400) {
                CoffeesAeroAuth.LOGGER.debug("[Obsidian] HTTP {} on {} {}", resp.statusCode(), method, path);
            }
        } catch (Exception e) {
            if (attemptsLeft > 1) {
                Thread.sleep(500);
                attempt(method, path, body, attemptsLeft - 1);
            } else {
                throw e; // caller swallows it
            }
        }
    }

    private HttpRequest buildRequest(String method, String vaultRelPath, String body) {
        String encodedPath = Arrays.stream(vaultRelPath.split("/"))
            .map(seg -> URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"))
            .collect(Collectors.joining("/"));

        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/vault/" + encodedPath))
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(10));

        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "text/markdown")
             .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return b.build();
    }
}
