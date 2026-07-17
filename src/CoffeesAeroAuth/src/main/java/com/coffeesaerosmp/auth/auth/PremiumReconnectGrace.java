package com.coffeesaerosmp.auth.auth;

import com.coffeesaerosmp.auth.config.AuthConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixes the premium-demoted-to-OFFLINE reconnect bug (2026-07-17): the gate cookie is single-use
 * (replay nonce) with a short expiry, so a client that reconnects DIRECTLY to the backend — which
 * is exactly what every launcher's reconnect does after a freeze mass-kick — re-presents the spent
 * cookie, gets {@code Cookie REJECTED}, and a verified premium player lands in the offline lobby.
 *
 * <p>Remedy: every successful premium cookie verification records (username → Mojang UUID, IP).
 * When a later cookie is missing/rejected, the resolver may still grant PREMIUM iff the same
 * username reconnects from the SAME IP within the grace window (config, default 10 min; the window
 * restarts on logout so it covers kicks and crashes). The cookie itself stays single-use — this
 * adds no crypto surface; an impersonator would need the victim's live IP within minutes of their
 * disconnect. In-memory only: a server restart clears it, and the normal gate path still works.</p>
 */
public final class PremiumReconnectGrace {

    private record Entry(UUID mojangUuid, String ip, long expiresAt) {}

    private static final Map<String, Entry> entries = new ConcurrentHashMap<>();   // lowercase name →

    private PremiumReconnectGrace() {}

    private static long graceMs() {
        return AuthConfig.PREMIUM_RECONNECT_GRACE_MINUTES.get() * 60_000L;
    }

    /** Record/refresh after a successful premium cookie verification (gate-verified identity). */
    public static void record(String username, UUID mojangUuid, String ip) {
        if (graceMs() <= 0 || username == null || ip == null) return;
        entries.put(username.toLowerCase(java.util.Locale.ROOT),
            new Entry(mojangUuid, ip, System.currentTimeMillis() + graceMs()));
    }

    /** Restart the window from logout/kick so the grace covers the reconnect that follows. */
    public static void touch(String username) {
        if (username == null) return;
        entries.computeIfPresent(username.toLowerCase(java.util.Locale.ROOT),
            (k, e) -> new Entry(e.mojangUuid(), e.ip(), System.currentTimeMillis() + graceMs()));
    }

    /**
     * The gate-verified Mojang UUID if {@code username} reconnecting from {@code ip} is within an
     * unexpired grace window from the SAME IP — otherwise {@code null} (caller resolves OFFLINE).
     */
    public static UUID check(String username, String ip) {
        if (graceMs() <= 0 || username == null || ip == null) return null;
        Entry e = entries.get(username.toLowerCase(java.util.Locale.ROOT));
        if (e == null) return null;
        if (System.currentTimeMillis() > e.expiresAt()) {
            entries.remove(username.toLowerCase(java.util.Locale.ROOT), e);
            return null;
        }
        return e.ip().equals(ip) ? e.mojangUuid() : null;
    }
}
