package com.coffeesaerosmp.auth.admin;

import java.util.UUID;

/**
 * Decides whether an arriving Mojang identity may claim a profile.
 *
 * <h2>Why this is its own class</h2>
 * Deliberately pure — <b>no {@code net.minecraft} imports, no mod state</b> — for the same reason
 * {@code PlayerDisplay} is: it makes the decision unit-testable without a server.
 * {@link AccountTransfer} cannot be loaded in a plain unit test at all, because its static initialiser
 * touches {@code LevelResource}, so a test of the rule living there would fail with
 * {@code NoClassDefFoundError} before reaching a single assertion.
 *
 * <h2>What the rule protects</h2>
 * Identity on this server is {@code md5("OfflinePlayer:" + name)}, so whoever holds a Mojang name
 * inherits the profile filed under it — a stranger buying a still-free offline player's name, or buying
 * a premium name that was released after a rename. A Mojang uuid never changes, so a <i>different</i>
 * one arriving for an already-linked profile is proof of a different human, not a heuristic.
 *
 * <p>Until 2026-09-29 the link column was overwritten unconditionally, so a takeover erased the only
 * evidence it had occurred. See {@code planning/store-identity-risk.md}.
 */
public final class IdentityLink {

    /** What to do with an arriving Mojang uuid, given whatever is already stored on the profile. */
    public enum LinkAction {
        /** Nothing stored yet — claim the profile for this Mojang account. */
        BIND,
        /** Already stored and it matches. Nothing to do. */
        ALREADY_OURS,
        /** Already stored and it is a DIFFERENT Mojang account. Do not touch it; report it. */
        MISMATCH
    }

    private IdentityLink() {}

    /**
     * @param stored   the profile's current {@code players.mojang_uuid}, possibly null/blank
     * @param arriving the gate-verified Mojang uuid from this login's HMAC-signed cookie
     */
    public static LinkAction classify(String stored, UUID arriving) {
        // Never bind an absent identity: without this, a null arriving uuid against an empty column
        // classifies as BIND and we would report a successful claim having written nothing meaningful.
        if (arriving == null) return LinkAction.MISMATCH;
        if (stored == null || stored.isBlank()) return LinkAction.BIND;
        // Case-insensitive and trimmed: a uuid's text form is hex, MySQL may return either case, and
        // several code paths have written this column over time. A strict compare would report every
        // login of a legitimately linked player as a takeover.
        return stored.trim().equalsIgnoreCase(arriving.toString())
            ? LinkAction.ALREADY_OURS
            : LinkAction.MISMATCH;
    }
}
