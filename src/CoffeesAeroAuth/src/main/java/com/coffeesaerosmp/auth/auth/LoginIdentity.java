package com.coffeesaerosmp.auth.auth;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, in the LOGIN phase, which uuid a connection plays under ({@code premiumKeepsMojangUuid}).
 *
 * <h2>Why the login phase</h2>
 * The backend is {@code online-mode=false}, so vanilla builds every GameProfile as
 * {@code md5("OfflinePlayer:" + name)}. The gate cookie used to be read in the PLAY phase, but by then
 * {@code PlayerList.placeNewPlayer} has already loaded {@code playerdata/<uuid>.dat} under that
 * name-derived uuid — the identity is fixed. Reading the cookie during login, before
 * {@code startClientVerification}, is the only point where the uuid can still be chosen.
 * {@code mixin/ServerLoginIdentityMixin} does the reading; this class decides and remembers.
 *
 * <h2>The decision</h2>
 * <ul>
 *   <li>valid cookie, premium, cookie name == requested name → the <b>Mojang uuid</b>;</li>
 *   <li>valid cookie, offline → name-derived (unchanged);</li>
 *   <li>missing / invalid / spent cookie → the premium reconnect grace (same name + same IP inside the
 *       window) may still grant the Mojang uuid; otherwise name-derived, and the PLAY-phase checks
 *       (direct-entry refusal, premium-name conflict kick) decide whether that connection may stay.</li>
 * </ul>
 * A cookie whose name differs from the requested name is treated as no cookie: the gate signs the name
 * Mojang authenticated, so a mismatch means the cookie belongs to some other session.
 *
 * <h2>Handing the result to PLAY</h2>
 * 🔴 The cookie nonce is single-use. The PLAY phase must NOT ask for it again — the client would
 * re-present the same cookie and it would be refused as a replay, demoting a premium player to
 * offline. So the verified result is stashed here under the uuid the connection was given, and
 * {@code AuthManager.onPlayerJoin} takes it instead of sending a cookie request.
 *
 * <p>Pure apart from the stash: no {@code net.minecraft} imports, so the decision is unit-tested.
 */
public final class LoginIdentity {

    /** What the login phase established. {@code verified} null = no usable cookie. */
    public record Outcome(CookieAuth.Verified verified, UUID graceMojangUuid, String why) {
        /** The uuid this connection is given. */
        public UUID profileUuid(String requestedName) {
            if (verified != null && verified.premium()) return verified.uuid();
            if (graceMojangUuid != null) return graceMojangUuid;
            return offlineUuid(requestedName);
        }

        public boolean premium() {
            return (verified != null && verified.premium()) || graceMojangUuid != null;
        }
    }

    /** A stashed outcome expires if the player never reaches PLAY (disconnect during config). */
    private static final long STASH_TTL_MS = 120_000;

    private record Stashed(Outcome outcome, long at) {}

    private static final Map<UUID, Stashed> STASH = new ConcurrentHashMap<>();

    private LoginIdentity() {}

    /**
     * @param verified      the verified cookie, or null if it was missing/invalid/expired/replayed
     * @param requestedName the name from the client's hello packet
     * @param graceMojang   the reconnect grace's Mojang uuid for (name, ip), or null; only consulted
     *                      when there is no usable cookie
     */
    public static Outcome decide(CookieAuth.Verified verified, String requestedName, UUID graceMojang,
                                 String noCookieWhy) {
        if (verified != null) {
            if (requestedName != null && requestedName.equals(verified.username())) {
                return new Outcome(verified, null, "cookie");
            }
            // Fall through as if there were no cookie at all.
            noCookieWhy = "cookie name '" + verified.username() + "' != requested '" + requestedName + "'";
        }
        if (graceMojang != null) return new Outcome(null, graceMojang, "reconnect grace (" + noCookieWhy + ")");
        return new Outcome(null, null, noCookieWhy);
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ── login → play hand-off ────────────────────────────────────────────────

    public static void stash(UUID profileUuid, Outcome outcome) {
        long now = System.currentTimeMillis();
        if (STASH.size() > 256) STASH.entrySet().removeIf(e -> now - e.getValue().at() > STASH_TTL_MS);
        STASH.put(profileUuid, new Stashed(outcome, now));
    }

    /** Removes and returns the outcome for this uuid, or null if none / expired. */
    public static Outcome take(UUID profileUuid) {
        Stashed s = STASH.remove(profileUuid);
        if (s == null || System.currentTimeMillis() - s.at() > STASH_TTL_MS) return null;
        return s.outcome();
    }
}
