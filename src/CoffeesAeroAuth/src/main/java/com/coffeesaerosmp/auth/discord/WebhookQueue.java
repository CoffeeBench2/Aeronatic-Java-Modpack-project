package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
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
        // Tick 4x/second and let the backoff gate decide. The old fixed 1/second drain ignored 429s
        // entirely, so during any burst it re-sent into a live rate limit every second and lost the
        // payload each time (the 2026-08-04 log wall). Polling faster + gating on nextSendAtMs is
        // both quicker when idle and correct when limited.
        // NOTE THE WRAPPERS. ScheduledExecutorService.scheduleAtFixedRate CANCELS a repeating task
        // permanently the first time it throws, and does so silently — the exception is parked in a
        // Future nobody reads. That is a bridge that "works and then just stops", with no error in
        // the log, until the next server restart. Every scheduled body here is therefore wrapped so
        // a single bad payload can never end the schedule.
        scheduler.scheduleAtFixedRate(guard("drain", this::drainOne), 250, 250, TimeUnit.MILLISECONDS);
        // Flush LOW batch every 30 seconds
        scheduler.scheduleAtFixedRate(guard("flushLow", this::flushLow), 30, 30, TimeUnit.SECONDS);
    }

    /** Wraps a repeating task so a throw is logged instead of silently killing the schedule. */
    private static Runnable guard(String name, Runnable body) {
        return () -> {
            try {
                body.run();
            } catch (Throwable t) {
                CoffeesAeroAuth.LOGGER.error("[Discord] {} tick failed (schedule kept alive)", name, t);
            }
        };
    }

    public void enqueue(String url, String json, Severity severity) {
        if (url == null || url.isBlank() || json == null) return;
        // Batch-wrapping is a WATCHDOG-channel treatment only: batchLow() stamps the AeroGuard
        // identity + "LOW — Batch" embed, which used to swallow public chat (content-only payloads
        // have no embed title to extract) and leak watchdog styling into the public channel.
        //
        // The config read is guarded: ModConfigSpec.get() throws IllegalStateException before the
        // config is loaded, and an enqueue that throws would propagate into whatever game event
        // called it (this is the same failure shape that made aero_cam_sync crash clients on join).
        boolean watchdogBound;
        try {
            watchdogBound = url.equals(
                com.coffeesaerosmp.auth.config.AuthConfig.DISCORD_WEBHOOK_WATCHDOG.get());
        } catch (Exception e) {
            watchdogBound = false;
        }
        if (severity == Severity.LOW && watchdogBound) {
            if (lowBatch.size() < MAX_QUEUED) lowBatch.add(new Entry(url, json, severity));
            return;
        }
        // Bounded: a long backoff plus a busy chat minute used to grow this without limit. Dropping
        // the OLDEST keeps the feed current — a 10-minute-old chat line arriving late is worse than
        // it not arriving — and the drop is logged so it is never silent.
        if (queued.get() >= MAX_QUEUED) {
            Entry dropped = immediateQueue.poll();
            if (dropped != null) {
                queued.decrementAndGet();
                CoffeesAeroAuth.LOGGER.warn(
                    "[Discord] Queue full ({}), dropped the oldest pending message.", MAX_QUEUED);
            }
        }
        immediateQueue.add(new Entry(url, json, severity));
        queued.incrementAndGet();
    }

    public void stop() {
        flushLow();
        // Shutdown flush must NOT go through drainOne(): that is now gated on nextSendAtMs and
        // returns immediately while backed off, so the old `while (!isEmpty()) drainOne()` would
        // spin forever and hang the server stop. Send straight out, bounded, and accept that a
        // rate-limited tail is lost — we are shutting down.
        Entry e;
        int sent = 0;
        while ((e = immediateQueue.poll()) != null && sent++ < 50) {
            DiscordWebhook.sendForRetry(e.url(), e.json());
        }
        queued.set(immediateQueue.size());
        if (scheduler != null) scheduler.shutdownNow();
    }

    /** Wall-clock gate: nothing is sent before this. Set from Discord's own retry_after on a 429. */
    private volatile long nextSendAtMs = 0L;
    /** Guards against two overlapping in-flight sends when a response is slow. */
    private final java.util.concurrent.atomic.AtomicBoolean inFlight =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    /** When the current in-flight send started, for the stuck-latch watchdog. 0 = nothing in flight. */
    private volatile long inFlightSinceMs = 0L;
    /** Tracks immediateQueue size — ConcurrentLinkedQueue.size() is an O(n) traversal. */
    private final java.util.concurrent.atomic.AtomicInteger queued =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Longest a single send may sit in flight before the latch is force-released. */
    private static final long STUCK_SEND_MS = 30_000L;
    /** Ceiling on a Discord-supplied Retry-After, so a long ban can't park the feed until restart. */
    private static final long MAX_BACKOFF_MS = 60_000L;
    /** Backpressure ceiling per queue. */
    private static final int  MAX_QUEUED = 500;

    private void drainOne() {
        long now = System.currentTimeMillis();
        if (now < nextSendAtMs) return;                             // still backed off
        if (immediateQueue.isEmpty()) return;

        if (!inFlight.compareAndSet(false, true)) {
            // A send is already out. If it has been out longer than any request can legitimately
            // take (connect 5s + request 10s), the completion callback is never coming and the
            // latch would otherwise stay true forever — one lost callback silences the bridge for
            // the rest of the server's life. Force the latch open and try again next tick.
            long since = inFlightSinceMs;
            if (since > 0 && now - since > STUCK_SEND_MS) {
                CoffeesAeroAuth.LOGGER.warn(
                    "[Discord] A webhook send has been in flight {}ms with no completion — "
                    + "releasing the latch so the queue keeps draining.", now - since);
                inFlight.set(false);
            }
            return;
        }
        inFlightSinceMs = now;

        Entry e = immediateQueue.poll();
        if (e == null) { inFlight.set(false); return; }
        queued.decrementAndGet();

        DiscordWebhook.sendForRetry(e.url(), e.json()).whenComplete((waitMs, err) -> {
            try {
                if (err == null && waitMs != null && waitMs > 0) {
                    // Rate limited: hold the gate AND put the payload back at the FRONT so the
                    // message is delivered late rather than silently dropped, which is what the
                    // old fire-and-forget path did to every chat line during a burst.
                    //
                    // CAPPED. Discord (and Cloudflare, on a 1015 ban) can answer with a Retry-After
                    // measured in minutes or hours. Honouring that literally parks chat until the
                    // next restart while joins/leaves keep flowing, which reads to players as
                    // "chat broke". Re-probing every MAX_BACKOFF_MS at worst costs one 429.
                    nextSendAtMs = System.currentTimeMillis() + Math.min(waitMs, MAX_BACKOFF_MS);
                    requeueFront(e);
                } else {
                    // Discord's webhook budget is ~5 requests / 2s. Pace at 400ms so a normal chat
                    // burst drains promptly without walking into a limit in the first place.
                    nextSendAtMs = System.currentTimeMillis() + 400L;
                }
            } catch (Throwable t) {
                CoffeesAeroAuth.LOGGER.error("[Discord] send completion failed", t);
            } finally {
                inFlightSinceMs = 0L;
                inFlight.set(false);
            }
        });
    }

    /** ConcurrentLinkedQueue has no addFirst, so rebuild with the retried entry ahead of the rest. */
    private synchronized void requeueFront(Entry e) {
        java.util.List<Entry> rest = new ArrayList<>();
        Entry x;
        while ((x = immediateQueue.poll()) != null) rest.add(x);
        immediateQueue.add(e);
        immediateQueue.addAll(rest);
        queued.set(immediateQueue.size());
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
                DiscordWebhook.sendForRetry(url, AlertFormatter.batchLow(payloads));
            });
    }
}
