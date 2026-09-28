package com.coffeesaerosmp.auth.admin;

import com.coffeesaerosmp.auth.admin.IdentityLink.LinkAction;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decision that decides whether a profile gets claimed, kept, or reported as a possible takeover.
 *
 * <p>Identity on this server is {@code md5("OfflinePlayer:" + name)}, so whoever holds a Mojang name
 * inherits the profile under it. Until 2026-09-29 the link column was overwritten unconditionally, which
 * meant a takeover destroyed its own evidence. These tests pin the replacement.
 */
class LinkClassifyTest {

    private static final UUID A = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID B = UUID.fromString("99999999-8888-4777-8666-555555555555");

    @Test
    void unlinkedProfileIsClaimed() {
        assertEquals(LinkAction.BIND, IdentityLink.classify(null, A));
        assertEquals(LinkAction.BIND, IdentityLink.classify("", A));
        assertEquals(LinkAction.BIND, IdentityLink.classify("   ", A));
    }

    @Test
    void sameAccountLoggingInAgainIsANoOp() {
        assertEquals(LinkAction.ALREADY_OURS, IdentityLink.classify(A.toString(), A));
    }

    /**
     * 🔴 The takeover case. A Mojang uuid never changes, so a different one arriving for the same profile
     * means a different human — proof, not a heuristic.
     */
    @Test
    void differentMojangAccountIsAMismatch() {
        assertEquals(LinkAction.MISMATCH, IdentityLink.classify(A.toString(), B));
    }

    /**
     * MySQL may return hex in either case. A case-sensitive compare would report every login of a
     * legitimately linked player as a takeover — i.e. it would alert on all 107 linked accounts.
     */
    @Test
    void comparisonIsCaseInsensitive() {
        assertEquals(LinkAction.ALREADY_OURS,
                     IdentityLink.classify(A.toString().toUpperCase(), A));
        assertEquals(LinkAction.ALREADY_OURS,
                     IdentityLink.classify(A.toString().toLowerCase(), A));
    }

    /** Stored values have been written by several code paths over time; tolerate stray whitespace. */
    @Test
    void storedValueIsTrimmedBeforeComparing() {
        assertEquals(LinkAction.ALREADY_OURS, IdentityLink.classify("  " + A + "  ", A));
    }

    /**
     * Never bind an absent identity. A null arriving uuid would otherwise classify as BIND against an
     * empty column and write nothing useful while reporting success.
     */
    @Test
    void absentArrivingIdentityNeverBinds() {
        assertEquals(LinkAction.MISMATCH, IdentityLink.classify(null, null));
        assertEquals(LinkAction.MISMATCH, IdentityLink.classify(A.toString(), null));
    }

    /** Garbage in the column must not read as "ours" and must not be silently overwritten. */
    @Test
    void unparseableStoredValueIsAMismatchNotABind() {
        assertEquals(LinkAction.MISMATCH, IdentityLink.classify("not-a-uuid", A));
    }

    /** An offline uuid stored in the link column is still someone else's identity, not a free slot. */
    @Test
    void aV3UuidInTheLinkColumnIsAMismatch() {
        UUID v3 = UUID.nameUUIDFromBytes("OfflinePlayer:Bob".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(3, v3.version(), "sanity: this is the name-derived shape");
        assertEquals(LinkAction.MISMATCH, IdentityLink.classify(v3.toString(), A));
    }

    /** The three outcomes must be exhaustive — every input maps to exactly one. */
    @Test
    void everyInputIsClassified() {
        String[] stored = {null, "", "  ", A.toString(), B.toString(), "junk"};
        for (String s : stored) {
            assertNotNull(IdentityLink.classify(s, A), "unclassified stored=" + s);
        }
    }
}
