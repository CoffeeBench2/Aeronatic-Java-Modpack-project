package com.coffeesaerosmp.auth.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The login-phase uuid decision ({@code premiumKeepsMojangUuid}).
 *
 * <p>Every wrong answer here is a split identity: a premium player on a name-derived uuid lands in an empty
 * account, and an offline player on a Mojang uuid would wear somebody else's. Neither throws anything.
 */
class LoginIdentityTest {

    private static final UUID MOJANG = UUID.fromString("98b33d4e-7c8a-4f0e-9a3e-3b9f1f1b2c11");
    private static final UUID GRACE  = UUID.fromString("0fef03b3-fa42-4ac2-80b9-d00d80ad303b");

    private static CookieAuth.Verified premium(String name) { return new CookieAuth.Verified(true, MOJANG, name); }
    private static CookieAuth.Verified offline(String name) {
        return new CookieAuth.Verified(false, LoginIdentity.offlineUuid(name), name);
    }

    @Test
    @DisplayName("offlineUuid is vanilla's md5(\"OfflinePlayer:\"+name)")
    void offlineUuidMatchesVanilla() {
        // MrCoffeeBench's live row: the name-derived uuid in ops.json and every S2 table.
        assertEquals(UUIDUtil.expectedOfflineUUID("MrCoffeeBench"), LoginIdentity.offlineUuid("MrCoffeeBench"));
        assertEquals(3, LoginIdentity.offlineUuid("x").version());
    }

    @Test
    @DisplayName("verified premium cookie for the same name -> Mojang uuid")
    void premiumCookieGivesMojangUuid() {
        LoginIdentity.Outcome o = LoginIdentity.decide(premium("Steve"), "Steve", null, "");
        assertTrue(o.premium());
        assertEquals(MOJANG, o.profileUuid("Steve"));
        assertNotNull(o.verified());
    }

    @Test
    @DisplayName("verified OFFLINE cookie -> name-derived uuid, never the cookie's uuid field")
    void offlineCookieGivesNameUuid() {
        LoginIdentity.Outcome o = LoginIdentity.decide(offline("Alex"), "Alex", null, "");
        assertFalse(o.premium());
        assertEquals(LoginIdentity.offlineUuid("Alex"), o.profileUuid("Alex"));
    }

    @Test
    @DisplayName("cookie signed for another name is ignored: no Mojang uuid, no verified cookie handed on")
    void foreignCookieIsNoCookie() {
        LoginIdentity.Outcome o = LoginIdentity.decide(premium("Steve"), "Mallory", null, "");
        assertFalse(o.premium());
        assertNull(o.verified(), "PLAY must not see Steve's cookie for Mallory");
        assertEquals(LoginIdentity.offlineUuid("Mallory"), o.profileUuid("Mallory"));
        assertTrue(o.why().contains("Steve"));
    }

    @Test
    @DisplayName("names are compared exact-case: md5 is case-sensitive, so 'steve' != 'Steve'")
    void caseMismatchIsNoCookie() {
        LoginIdentity.Outcome o = LoginIdentity.decide(premium("Steve"), "steve", null, "");
        assertFalse(o.premium());
    }

    @Test
    @DisplayName("no cookie but a reconnect-grace hit -> the grace's Mojang uuid")
    void graceGivesMojangUuid() {
        LoginIdentity.Outcome o = LoginIdentity.decide(null, "Steve", GRACE, "no cookie");
        assertTrue(o.premium());
        assertNull(o.verified());
        assertEquals(GRACE, o.profileUuid("Steve"));
        assertTrue(o.why().contains("grace"));
    }

    @Test
    @DisplayName("a valid cookie wins over the grace")
    void cookieBeatsGrace() {
        LoginIdentity.Outcome o = LoginIdentity.decide(premium("Steve"), "Steve", GRACE, "");
        assertEquals(MOJANG, o.profileUuid("Steve"));
    }

    @Test
    @DisplayName("no cookie, no grace -> name-derived, offline")
    void nothingGivesOffline() {
        LoginIdentity.Outcome o = LoginIdentity.decide(null, "Steve", null, "no cookie");
        assertFalse(o.premium());
        assertEquals(LoginIdentity.offlineUuid("Steve"), o.profileUuid("Steve"));
        assertEquals("no cookie", o.why());
    }

    @Test
    @DisplayName("stash hands the outcome to PLAY exactly once")
    void stashIsSingleUse() {
        UUID id = UUID.randomUUID();
        LoginIdentity.Outcome o = LoginIdentity.decide(null, "Steve", null, "no cookie");
        LoginIdentity.stash(id, o);
        assertSame(o, LoginIdentity.take(id));
        assertNull(LoginIdentity.take(id), "a second take must not replay the first login's result");
        assertNull(LoginIdentity.take(UUID.randomUUID()));
    }
}
