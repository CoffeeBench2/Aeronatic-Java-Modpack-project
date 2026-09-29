package com.coffeesaerosmp.auth.admin;

import com.coffeesaerosmp.auth.admin.IdentityGate.Arrival;
import com.coffeesaerosmp.auth.admin.IdentityGate.Stored;
import com.coffeesaerosmp.auth.admin.IdentityGate.Verdict;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Who may enter a profile. Identity here is {@code md5("OfflinePlayer:" + name)}, so whoever holds a
 * Mojang name reaches the profile filed under it — these tests pin who is let through, who must prove
 * themselves with the old password, and who is refused. See {@code planning/store-identity-risk.md}.
 */
class IdentityGateTest {

    private static final UUID A = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID B = UUID.fromString("99999999-8888-4777-8666-555555555555");
    private static final long HOUR = 3600;

    private static Stored premium(String link)           { return new Stored("PREMIUM", link, null, false, 50 * HOUR); }
    private static Stored offline(boolean pw, long secs) { return new Stored("OFFLINE", null, null, pw, secs); }

    // ── new players ──────────────────────────────────────────────────────────

    @Test
    void brandNewPlayerIsAlwaysLetIn() {
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.premium(A), null, true));
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.offline(), null, true));
    }

    // ── Gate A: linked profiles ──────────────────────────────────────────────

    @Test
    void theLinkedOwnerIsLetIn() {
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.premium(A), premium(A.toString()), true));
        // case and whitespace from MySQL must not turn the owner into a suspect
        assertEquals(Verdict.ALLOW,
            IdentityGate.decide(Arrival.premium(A), premium(" " + A.toString().toUpperCase() + " "), true));
    }

    /** 🔴 The takeover: a different Mojang account on a linked profile is a different human. */
    @Test
    void aDifferentMojangAccountIsRefused() {
        assertEquals(Verdict.DENY_MISMATCH, IdentityGate.decide(Arrival.premium(B), premium(A.toString()), true));
    }

    /** Linked-but-OFFLINE rows exist only by hand; the link still wins over the account type. */
    @Test
    void theLinkIsCheckedWhateverTheAccountType() {
        Stored s = new Stored("OFFLINE", A.toString(), null, true, 10 * HOUR);
        assertEquals(Verdict.DENY_MISMATCH, IdentityGate.decide(Arrival.premium(B), s, true));
        assertEquals(Verdict.ALLOW,         IdentityGate.decide(Arrival.premium(A), s, true));
    }

    // ── Gate D: unlinked premium ─────────────────────────────────────────────

    /** The 169 whose names still resolve: only the owner can pass Mojang auth, so bind as before. */
    @Test
    void anUnlinkedPremiumProfileIsBoundByItsNextLogin() {
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.premium(A), premium(null), true));
    }

    // ── holds ────────────────────────────────────────────────────────────────

    @Test
    void aHeldProfileRefusesEveryone() {
        Stored held = new Stored("PREMIUM", A.toString(), "name released 2026-09-06", false, 48 * HOUR);
        assertEquals(Verdict.DENY_HELD, IdentityGate.decide(Arrival.premium(A), held, true));
        assertEquals(Verdict.DENY_HELD, IdentityGate.decide(Arrival.premium(B), held, true));
        assertEquals(Verdict.DENY_HELD, IdentityGate.decide(Arrival.offline(),  held, true));
    }

    @Test
    void aBlankHoldIsNoHold() {
        Stored s = new Stored("PREMIUM", null, "  ", false, HOUR);
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.premium(A), s, true));
    }

    // ── Gate B: offline → premium ────────────────────────────────────────────

    /** The real owner who bought the game and a stranger who bought the name look identical; the password decides. */
    @Test
    void anInvestedOfflineProfileWithAPasswordMustBeClaimed() {
        assertEquals(Verdict.CLAIM_REQUIRED, IdentityGate.decide(Arrival.premium(A), offline(true, 10 * HOUR), true));
        // even a short one: they chose a password, so it can be proven, so prove it
        assertEquals(Verdict.CLAIM_REQUIRED, IdentityGate.decide(Arrival.premium(A), offline(true, 60), true));
    }

    @Test
    void anInvestedOfflineProfileWithoutAPasswordCannotBeSelfClaimed() {
        assertEquals(Verdict.DENY_UNCLAIMABLE, IdentityGate.decide(Arrival.premium(A), offline(false, 2 * HOUR), true));
    }

    /** Nothing to steal and no way to prove it: refusing would only lock a real player out of an empty profile. */
    @Test
    void anEmptyPasswordlessOfflineProfileIsSimplyUpgraded() {
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.premium(A), offline(false, HOUR - 1), true));
    }

    @Test
    void offlineArrivalsKeepThePasswordFlow() {
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.offline(), offline(true, 10 * HOUR), true));
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.offline(), premium(A.toString()), true));
    }

    // ── no live source ───────────────────────────────────────────────────────

    /**
     * A claim writes the link, and the stored state may be a stale boot-time copy — with the database down
     * there is nothing to prove against, so the answer is "not now", never "come in".
     */
    @Test
    void aClaimIsNeverDecidedWithoutTheDatabase() {
        assertEquals(Verdict.DENY_UNVERIFIABLE, IdentityGate.decide(Arrival.premium(A), offline(true, 10 * HOUR), false));
        assertEquals(Verdict.DENY_UNVERIFIABLE, IdentityGate.decide(Arrival.premium(A), offline(false, 2 * HOUR), false));
    }

    /** A known mismatch is still a mismatch from a cached copy — a link is never removed, only ever added. */
    @Test
    void aCachedMismatchIsStillRefusedWithTheDatabaseDown() {
        assertEquals(Verdict.DENY_MISMATCH, IdentityGate.decide(Arrival.premium(B), premium(A.toString()), false));
    }

    // ── premium without a Mojang uuid (legacy forwarding paths) ──────────────

    @Test
    void premiumWithoutAnIdentityCannotEnterALinkedOrClaimableProfile() {
        assertEquals(Verdict.DENY_UNVERIFIABLE, IdentityGate.decide(Arrival.premium(null), premium(A.toString()), true));
        assertEquals(Verdict.DENY_UNVERIFIABLE, IdentityGate.decide(Arrival.premium(null), offline(true, 10 * HOUR), true));
        // an unlinked premium profile behaves as it always has
        assertEquals(Verdict.ALLOW, IdentityGate.decide(Arrival.premium(null), premium(null), true));
    }

    @Test
    void onlyDenialsBlockEntry() {
        assertFalse(Verdict.ALLOW.denies());
        assertFalse(Verdict.CLAIM_REQUIRED.denies());
        assertTrue(Verdict.DENY_MISMATCH.denies());
        assertTrue(Verdict.DENY_HELD.denies());
        assertTrue(Verdict.DENY_UNCLAIMABLE.denies());
        assertTrue(Verdict.DENY_UNVERIFIABLE.denies());
    }
}
