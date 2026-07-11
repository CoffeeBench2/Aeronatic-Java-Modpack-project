package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.watchdog.Severity;

import java.util.*;
import java.util.concurrent.*;

/**
 * Rate-limited dispatch queue for Discord webhooks.
 * - Non-LOW events: max 1 send/second (queued if burst).
 * - LOW events: batched into a single message every 30 seconds.
 * - IPs are never included in public-channel payloads — that's the caller's responsibility.
 */
public class WebhookQueue {

    private record Entry(String url, String json, Severity severity) {}

    private final Queue<Entry>  immediateQueue = new ConcurrentLinkedQueue<>();
    private final List<Entry>   lowBatch       = Collections.synchronizedList(new ArrayList<>());

    private ScheduledExecutorService scheduler;

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AeroAuth-Discord");
            t.setDaemon(true);
            return t;
        });
        // Drain one non-LOW item per second
        scheduler.scheduleAtFixedRate(this::drainOne, 1, 1, TimeUnit.SECONDS);
        // Flush LOW batch every 30 seconds
        scheduler.scheduleAtFixedRate(this::flushLow, 30, 30, TimeUnit.SECONDS);
    }

    public void enqueue(String url, String json, Severity severity) {
        if (url == null || url.isBlank() || json == null) return;
        // Batch-wrapping is a WATCHDOG-channel treatment only: batchLow() stamps the AeroGuard
        // identity + "LOW — Batch" embed, which used to swallow public chat (content-only payloads
        // have no embed title to extract) and leak watchdog styling into the public channel.
        boolean watchdogBound = url.equals(
            com.coffeesaerosmp.auth.config.AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get());
        if (severity == Severity.LOW && watchdogBound) lowBatch.add(new Entry(url, json, severity));
        else immediateQueue.add(new Entry(url, json, severity));
    }

    public void stop() {
        flushLow();
        while (!immediateQueue.isEmpty()) drainOne();
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void drainOne() {
        Entry e = immediateQueue.poll();
        if (e != null) DiscordWebhook.send(e.url(), e.json());
    }

    private void flushLow() {
        if (lowBatch.isEmpty()) return;
        List<Entry> batch;
        synchronized (lowBatch) {
            batch = new ArrayList<>(lowBatch);
            lowBatch.clear();
        }
        // Group by URL and send one batched embed per URL
        batch.stream()
            .collect(java.util.stream.Collectors.groupingBy(Entry::url))
            .forEach((url, entries) -> {
                List<String> payloads = entries.stream().map(Entry::json).toList();
                DiscordWebhook.send(url, AlertFormatter.batchLow(payloads));
            });
    }
}
