package com.coffeesaerosmp.auth.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session tokens for offline players.
 * After a successful /login, a token is bound to the player's UUID + IP.
 * On reconnect from the same IP, the token is accepted and login is skipped.
 * On reconnect from a different IP, the token is invalidated and login is required.
 * Tokens are never written to disk — they die with the server process.
 */
public class SessionTokenManager {

    public enum TokenStatus {
        VALID,       // token exists and IP matches — skip login
        IP_CHANGED,  // token exists but IP differs — invalidate and force login
        NONE         // no token — normal login required
    }

    private record SessionToken(String token, String ip) {}

    private final Map<UUID, SessionToken> tokens = new ConcurrentHashMap<>();
    private static final SecureRandom RNG = new SecureRandom();

    /** Called after every successful /login or /register. */
    public void createToken(UUID uuid, String ip) {
        byte[] bytes = new byte[16];
        RNG.nextBytes(bytes);
        tokens.put(uuid, new SessionToken(Base64.getEncoder().encodeToString(bytes), ip));
    }

    /** Check whether a reconnecting player can skip login. */
    public TokenStatus check(UUID uuid, String ip) {
        SessionToken t = tokens.get(uuid);
        if (t == null) return TokenStatus.NONE;
        return t.ip().equals(ip) ? TokenStatus.VALID : TokenStatus.IP_CHANGED;
    }

    /** Removes the token — forces re-login on next join. */
    public void invalidate(UUID uuid) {
        tokens.remove(uuid);
    }

    public int activeCount() {
        return tokens.size();
    }
}
