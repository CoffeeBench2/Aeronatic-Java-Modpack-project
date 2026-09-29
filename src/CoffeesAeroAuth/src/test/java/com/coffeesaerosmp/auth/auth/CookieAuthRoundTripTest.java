package com.coffeesaerosmp.auth.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip tests for the lobby → SMP handoff cookie.
 *
 * <p>These exist because a mismatch between {@code sign} and {@code verify} <b>fails silently in
 * production</b>: the SMP would simply treat every handoff as an unverified connection and demote
 * premium players to OFFLINE. There is no crash and no error to grep for — just players quietly
 * losing their accounts' premium status. The wire format is therefore pinned by test, not by
 * comments.
 *
 * <p>{@link CookieAuth} imports no Minecraft classes, which is what makes this testable at all —
 * the same rule the display-core tests follow.
 */
class CookieAuthRoundTripTest {

    // 64 hex chars = 32 bytes. ⚠️ An ODD-length string makes hexToBytes return empty, which disables
    // signing entirely — the first draft of this test used a 65-char secret and every round-trip
    // failed on a null cookie. That is hexToBytes failing CLOSED, which is correct, but it also means
    // a typo'd AERO_GATE_SECRET silently disables gate auth in production. Hence assertSecretsParsed.
    private static final byte[] SECRET = CookieAuth.hexToBytes(
        "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
    private static final byte[] OTHER_SECRET = CookieAuth.hexToBytes(
        "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100");

    private static final long TTL = 30_000L;

    @Test
    @DisplayName("the test secrets themselves parse — guards against a silently disabled fixture")
    void assertSecretsParsed() {
        assertEquals(32, SECRET.length, "SECRET must be 32 bytes or every other test passes vacuously");
        assertEquals(32, OTHER_SECRET.length, "OTHER_SECRET must be 32 bytes");
        assertTrue(new CookieAuth(SECRET).enabled());
    }

    @Test
    @DisplayName("malformed hex fails closed rather than producing a weak key")
    void malformedHexFailsClosed() {
        assertEquals(0, CookieAuth.hexToBytes("abc").length, "odd length -> empty");
        assertEquals(0, CookieAuth.hexToBytes("").length);
        assertEquals(0, CookieAuth.hexToBytes(null).length);
        assertFalse(new CookieAuth(CookieAuth.hexToBytes("abc")).enabled(),
            "a typo'd AERO_GATE_SECRET must disable signing, never sign with a short key");
    }

    @Test
    @DisplayName("a signed premium cookie verifies back to the same identity")
    void premiumRoundTrip() {
        CookieAuth signer = new CookieAuth(SECRET);
        CookieAuth backend = new CookieAuth(SECRET);   // separate instance = separate nonce set

        UUID mojang = UUID.fromString("98b33d4e-05ec-44d5-983e-8dbf64391504");
        byte[] cookie = signer.sign(true, mojang, "MrCoffeeBench", TTL);
        assertNotNull(cookie, "signing must succeed when a secret is configured");

        CookieAuth.Verified v = backend.verify(cookie);
        assertNotNull(v, "a freshly signed cookie must verify");
        assertTrue(v.premium());
        assertEquals(mojang, v.uuid(), "the MOJANG uuid must survive — SkinsHook fetches by it");
        assertEquals("MrCoffeeBench", v.username());
    }

    @Test
    @DisplayName("the offline flag survives the round trip")
    void offlineRoundTrip() {
        CookieAuth signer = new CookieAuth(SECRET);
        CookieAuth backend = new CookieAuth(SECRET);

        UUID local = UUID.fromString("2d1532de-e268-3de7-8ce3-a2bca1e6a874");
        CookieAuth.Verified v = backend.verify(signer.sign(false, local, "Aero_Lalalalala1", TTL));

        assertNotNull(v);
        assertFalse(v.premium(), "an offline handoff must NOT arrive as premium");
        assertEquals(local, v.uuid());
    }

    @Test
    @DisplayName("a cookie is single-use — replay is rejected")
    void replayRejected() {
        CookieAuth signer = new CookieAuth(SECRET);
        CookieAuth backend = new CookieAuth(SECRET);

        byte[] cookie = signer.sign(true, UUID.randomUUID(), "Replayer", TTL);
        assertNotNull(backend.verify(cookie), "first use must succeed");
        assertNull(backend.verify(cookie), "second use of the same nonce must be refused");
    }

    @Test
    @DisplayName("two cookies for the same player differ — the nonce is fresh per call")
    void nonceIsFresh() {
        CookieAuth signer = new CookieAuth(SECRET);
        UUID id = UUID.randomUUID();

        byte[] a = signer.sign(true, id, "Same", TTL);
        byte[] b = signer.sign(true, id, "Same", TTL);
        assertFalse(java.util.Arrays.equals(a, b),
            "identical cookies would make the second handoff a replay and fail");
    }

    @Test
    @DisplayName("an expired cookie is rejected")
    void expiryEnforced() throws Exception {
        CookieAuth signer = new CookieAuth(SECRET);
        CookieAuth backend = new CookieAuth(SECRET);

        byte[] cookie = signer.sign(true, UUID.randomUUID(), "Slowpoke", 1L);
        Thread.sleep(5);
        assertNull(backend.verify(cookie), "a cookie past its expiry must not verify");
    }

    @Test
    @DisplayName("a cookie signed with a different secret is rejected")
    void wrongSecretRejected() {
        CookieAuth signer = new CookieAuth(OTHER_SECRET);
        CookieAuth backend = new CookieAuth(SECRET);

        assertNull(backend.verify(signer.sign(true, UUID.randomUUID(), "Impostor", TTL)),
            "mismatched AERO_GATE_SECRET must fail closed, never fall through to premium");
    }

    @Test
    @DisplayName("any tampered byte invalidates the cookie")
    void tamperRejected() {
        CookieAuth signer = new CookieAuth(SECRET);
        CookieAuth backend = new CookieAuth(SECRET);

        byte[] cookie = signer.sign(false, UUID.randomUUID(), "Tamperer", TTL);
        cookie[1] ^= 0x01;                       // flip the premium flag
        assertNull(backend.verify(cookie), "flipping premium must break the HMAC, not escalate");
    }

    @Test
    @DisplayName("signing is disabled without a secret, and never emits an unsigned cookie")
    void disabledWithoutSecret() {
        CookieAuth none = new CookieAuth(new byte[0]);
        assertFalse(none.enabled());
        assertNull(none.sign(true, UUID.randomUUID(), "NoSecret", TTL));
    }

    @Test
    @DisplayName("non-ASCII usernames survive the length prefix")
    void unicodeUsername() {
        CookieAuth signer = new CookieAuth(SECRET);
        CookieAuth backend = new CookieAuth(SECRET);

        // Multi-byte UTF-8: the length prefix counts BYTES, not characters. If sign() ever wrote a
        // character count this test is what catches it.
        String name = "Ünïcødé_Ω";
        CookieAuth.Verified v = backend.verify(signer.sign(true, UUID.randomUUID(), name, TTL));
        assertNotNull(v);
        assertEquals(name, v.username());
    }

    @Test
    @DisplayName("null inputs are refused rather than signed")
    void nullInputsRefused() {
        CookieAuth signer = new CookieAuth(SECRET);
        assertNull(signer.sign(true, null, "NoUuid", TTL));
        assertNull(signer.sign(true, UUID.randomUUID(), null, TTL));
    }
}
