package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One ordered background thread for every blocking write the mod does — MySQL upserts, audit-log
 * appends, trusted-IP inserts. These used to run on whatever thread called them (usually the
 * SERVER thread, on every login/leave), so a slow disk or a MySQL latency spike stalled the tick.
 * A single thread keeps per-key ordering (last save wins) without any locking gymnastics.
 */
public final class AsyncIo {

    private static volatile ExecutorService writer = newWriter();

    private AsyncIo() {}

    private static ExecutorService newWriter() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AeroAuth-IO-Writer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Enqueue a blocking write. Never throws; failures are the task's responsibility to log. */
    public static void submit(Runnable task) {
        try {
            writer.execute(() -> {
                try { task.run(); }
                catch (Throwable t) {
                    CoffeesAeroAuth.LOGGER.warn("[AsyncIo] background write failed: {}", t.toString());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // Shutting down — run inline so stop-time writes (playtime, stats) still land.
            task.run();
        }
    }

    /** Drains pending writes at server stop (bounded), then re-arms for a possible next start. */
    public static void drain(long timeoutSeconds) {
        ExecutorService old = writer;
        old.shutdown();
        try {
            if (!old.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                CoffeesAeroAuth.LOGGER.warn("[AsyncIo] {}s drain timeout — remaining writes dropped.", timeoutSeconds);
                old.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writer = newWriter();
    }
}
