package com.coffeesaerosmp.velocity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single responsibility: cache definitive Mojang lookups to minimise API calls and survive
 * short Mojang rate-limit / outage windows.
 *
 * <p>Only definitive results ({@link MojangApiClient.Status#PREMIUM} and
 * {@link MojangApiClient.Status#NOT_PREMIUM}) are stored — an {@code UNAVAILABLE} result is never
 * cached, because caching "we don't know" would defeat the retry. Entries expire after
 * {@link #TTL_MILLIS} (1 hour) so a name that changes premium status on Mojang is eventually
 * re-checked.</p>
 */
public class PremiumNameCache {

    public static final long TTL_MILLIS = 60 * 60 * 1000L; // 1 hour

    private record Entry(MojangApiClient.Result result, long storedAt) {}

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** Returns a non-expired cached result for the name, or empty if absent/stale. */
    public Optional<MojangApiClient.Result> get(String username) {
        String key = username.toLowerCase();
        Entry e = cache.get(key);
        if (e == null) return Optional.empty();
        if (System.currentTimeMillis() - e.storedAt() > TTL_MILLIS) {
            cache.remove(key, e);
            return Optional.empty();
        }
        return Optional.of(e.result());
    }

    /** Stores only definitive results; UNAVAILABLE is ignored. */
    public void put(String username, MojangApiClient.Result result) {
        if (result.status() == MojangApiClient.Status.UNAVAILABLE) return;
        cache.put(username.toLowerCase(), new Entry(result, System.currentTimeMillis()));
    }

    /** Drops expired entries; safe to call periodically. */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().storedAt() > TTL_MILLIS);
    }

    public int size() { return cache.size(); }
}
