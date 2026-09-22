package com.coffeesaerosmp.auth.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies the gate-signed auth cookie produced by the Velocity AeroGate plugin.
 *
 * <p>Wire format (matches AeroGate's CookieSigner): a body followed by a 32-byte HMAC-SHA256.</p>
 * <pre>
 *   version(1) premium(1) uuidHi(8) uuidLo(8) expiry(8) nonce(16) nameLen(2) name(nameLen) || mac(32)
 * </pre>
 *
 * <p>A cookie is trusted only if: the HMAC matches (constant-time), it has not expired, and its
 * nonce has not been seen before (single-use). The short expiry + single-use nonce defeat replay of
 * a stolen cookie. If no shared secret is configured the verifier is disabled and every cookie is
 * rejected — the gate path then resolves OFFLINE (never premium).</p>
 */
public final class CookieAuth {

    private static final int MAC_LEN = 32;
    private static final int MIN_BODY = 1 + 1 + 16 + 8 + 16 + 2; // + name(>=0)
    private static final byte VERSION = 1;

    private final byte[] secret; // empty => disabled
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>(); // nonceHex -> expiry

    public CookieAuth(byte[] secret) {
        this.secret = secret == null ? new byte[0] : secret;
    }

    public boolean enabled() {
        return secret.length > 0;
    }

    public record Verified(boolean premium, UUID uuid, String username) {}

    /** Returns the verified payload, or {@code null} if missing/invalid/expired/replayed/disabled. */
    public Verified verify(byte[] cookie) {
        if (!enabled() || cookie == null || cookie.length < MIN_BODY + MAC_LEN) return null;
        try {
            int bodyLen = cookie.length - MAC_LEN;
            byte[] body = Arrays.copyOfRange(cookie, 0, bodyLen);
            byte[] mac = Arrays.copyOfRange(cookie, bodyLen, cookie.length);
            if (!MessageDigest.isEqual(mac, hmac(body))) return null; // constant-time compare

            int i = 0;
            if (body[i++] != VERSION) return null;
            boolean premium = body[i++] != 0;
            long hi = readLong(body, i); i += 8;
            long lo = readLong(body, i); i += 8;
            long expiry = readLong(body, i); i += 8;
            byte[] nonce = Arrays.copyOfRange(body, i, i + 16); i += 16;
            int nameLen = ((body[i] & 0xFF) << 8) | (body[i + 1] & 0xFF); i += 2;
            if (nameLen < 0 || i + nameLen > body.length) return null;
            String username = new String(body, i, nameLen, StandardCharsets.UTF_8);

            long now = System.currentTimeMillis();
            if (now > expiry) return null; // expired

            String nonceHex = toHex(nonce);
            cleanup(now);
            if (seenNonces.putIfAbsent(nonceHex, expiry) != null) return null; // replay

            return new Verified(premium, new UUID(hi, lo), username);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Signs a cookie in the SAME wire format {@link #verify} accepts, for the lobby → SMP handoff.
     *
     * <p>The standalone lobby is a second consumer of the gate's identity decision: it verifies the
     * gate's cookie, then has to hand the player to the SMP with that decision intact. Re-signing
     * with the same shared secret means the SMP verifies it through the identical code path it
     * already uses for the gate — no second trust mechanism, no SMP-side change.
     *
     * <p>🔴 <b>{@code uuid} MUST be the MOJANG uuid for a premium player, not {@code player.getUUID()}.</b>
     * On an offline-mode backend those differ (98b33d4e… vs 2d1532de… for the same account), and the
     * receiving side feeds this value to {@code SkinsHook.applyPremium} to fetch the real skin. Sign
     * the local one and every transferred premium player arrives with a broken skin. For an OFFLINE
     * player there is no Mojang uuid and the local one is correct.
     *
     * <p>A fresh 16-byte nonce is generated per call, so each handoff cookie is single-use exactly
     * like the gate's. {@code ttlMillis} should be long enough to cover a client disconnect and
     * reconnect but no longer — a transfer takes seconds.
     *
     * @return the cookie bytes, or {@code null} if signing is disabled or the inputs are unusable.
     */
    public byte[] sign(boolean premium, UUID uuid, String username, long ttlMillis) {
        if (!enabled() || uuid == null || username == null) return null;
        byte[] name = username.getBytes(StandardCharsets.UTF_8);
        if (name.length > 0xFFFF) return null;                 // cannot express the length in 2 bytes
        try {
            byte[] nonce = new byte[16];
            new java.security.SecureRandom().nextBytes(nonce);

            byte[] body = new byte[MIN_BODY + name.length];
            int i = 0;
            body[i++] = VERSION;
            body[i++] = (byte) (premium ? 1 : 0);
            i = writeLong(body, i, uuid.getMostSignificantBits());
            i = writeLong(body, i, uuid.getLeastSignificantBits());
            i = writeLong(body, i, System.currentTimeMillis() + ttlMillis);
            System.arraycopy(nonce, 0, body, i, 16); i += 16;
            body[i++] = (byte) ((name.length >> 8) & 0xFF);    // big-endian, matching verify()
            body[i++] = (byte) (name.length & 0xFF);
            System.arraycopy(name, 0, body, i, name.length);

            byte[] mac = hmac(body);
            byte[] out = new byte[body.length + MAC_LEN];
            System.arraycopy(body, 0, out, 0, body.length);
            System.arraycopy(mac, 0, out, body.length, MAC_LEN);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Big-endian, mirroring {@link #readLong}. Returns the new offset. */
    private static int writeLong(byte[] b, int o, long v) {
        for (int k = 7; k >= 0; k--) b[o + (7 - k)] = (byte) ((v >>> (k * 8)) & 0xFF);
        return o + 8;
    }

    private byte[] hmac(byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(secret, "HmacSHA256"));
        return m.doFinal(data);
    }

    private void cleanup(long now) {
        if (seenNonces.size() > 2048) seenNonces.entrySet().removeIf(e -> e.getValue() < now);
    }

    private static long readLong(byte[] b, int o) {
        long v = 0;
        for (int k = 0; k < 8; k++) v = (v << 8) | (b[o + k] & 0xFF);
        return v;
    }

    private static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return s.toString();
    }

    public static byte[] hexToBytes(String s) {
        if (s == null) return new byte[0];
        s = s.trim();
        if (s.isEmpty() || (s.length() & 1) == 1) return new byte[0];
        byte[] b = new byte[s.length() / 2];
        try {
            for (int i = 0; i < s.length(); i += 2) {
                b[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) | Character.digit(s.charAt(i + 1), 16));
            }
        } catch (Exception e) {
            return new byte[0];
        }
        return b;
    }
}
