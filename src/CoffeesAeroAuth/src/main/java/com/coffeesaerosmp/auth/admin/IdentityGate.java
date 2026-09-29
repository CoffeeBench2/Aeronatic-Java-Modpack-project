package com.coffeesaerosmp.auth.admin;

import java.util.UUID;

/**
 * Decides whether an arriving login may enter the profile filed under its name.
 *
 * <h2>Why a gate is needed at all</h2>
 * Identity on this server is {@code md5("OfflinePlayer:" + name)}, so whoever holds a Mojang name reaches
 * the profile filed under it — a stranger buying a still-free offline player's name, or buying a premium
 * name that was released after a rename. {@link IdentityLink} made that <i>detectable</i>; this makes it
 * <i>refusable</i>.
 *
 * <h2>Lock, don't move</h2>
 * A refused profile is left exactly where it is. The DB row is not the account — builds, claims and
 * balances live in world and mod files keyed by the same uuid — so "give the newcomer a fresh profile"
 * would mean re-keying all of it (the 2–3 day {@link AccountTransfer} surface). Refusing the login costs
 * nothing and loses nothing; a held name is resolved by staff ({@code /aeroid}). Owner decision
 * 2026-09-29.
 *
 * <h2>Why this is its own class</h2>
 * Pure — no {@code net.minecraft} imports, no mod state — for the same reason as {@link IdentityLink}:
 * {@link AccountTransfer} cannot even be class-loaded in a unit test.
 */
public final class IdentityGate {

    /** Below this, a passwordless offline profile has nothing worth stealing. Same threshold
     *  {@link AccountTransfer} uses for "auto-created, safe to discard". */
    static final long EMPTY_PROFILE_MAX_PLAYTIME = 3600;

    public enum Verdict {
        /** Proceed as before. For a premium arrival this includes binding an unlinked profile. */
        ALLOW,
        /** Offline profile, premium arrival: the old password must be given once, then the link is bound. */
        CLAIM_REQUIRED,
        /** The profile is linked to a DIFFERENT Mojang account. */
        DENY_MISMATCH,
        /** Staff have put this profile on an identity hold. */
        DENY_HELD,
        /** Invested offline profile, no password on file — nothing the arrival could prove. */
        DENY_UNCLAIMABLE,
        /** The answer depends on state we cannot read right now (database down, or no Mojang uuid). */
        DENY_UNVERIFIABLE;

        public boolean denies() {
            return this != ALLOW && this != CLAIM_REQUIRED;
        }
    }

    /** Who is arriving. {@code mojangUuid} comes from the gate's HMAC-signed cookie; null when unknown. */
    public record Arrival(boolean premium, UUID mojangUuid) {
        public static Arrival premium(UUID mojangUuid) { return new Arrival(true, mojangUuid); }
        public static Arrival offline()                { return new Arrival(false, null); }
    }

    /** What the profile already holds. */
    public record Stored(String accountType, String mojangLink, String hold,
                         boolean hasPassword, long playtimeSeconds) {}

    private IdentityGate() {}

    /**
     * @param stored   the existing profile, or null when this login is creating it
     * @param dbBacked whether {@code stored} came from a live database read rather than a cached copy
     */
    public static Verdict decide(Arrival arrival, Stored stored, boolean dbBacked) {
        if (stored == null) return Verdict.ALLOW;                    // nothing to take over
        if (!isBlank(stored.hold())) return Verdict.DENY_HELD;       // held means nobody, either way
        if (!arrival.premium()) return Verdict.ALLOW;                // the password flow is the gate

        // A link is only ever added, never removed, so a cached one is still trustworthy evidence —
        // this branch does not need the database.
        if (!isBlank(stored.mojangLink())) {
            if (arrival.mojangUuid() == null) return Verdict.DENY_UNVERIFIABLE;
            return switch (IdentityLink.classify(stored.mojangLink(), arrival.mojangUuid())) {
                case ALREADY_OURS -> Verdict.ALLOW;
                case MISMATCH, BIND -> Verdict.DENY_MISMATCH;        // BIND is unreachable with a link present
            };
        }

        // Unlinked premium profile: the gate forces Mojang auth for a name Mojang still resolves, so only
        // its owner can be here. rememberMojangUuid binds it.
        if ("PREMIUM".equalsIgnoreCase(String.valueOf(stored.accountType()).trim())) return Verdict.ALLOW;

        // Unlinked offline profile, premium arrival (Gate B). The real owner who bought the game and a
        // stranger who bought the name are indistinguishable without a secret.
        if (!stored.hasPassword() && stored.playtimeSeconds() < EMPTY_PROFILE_MAX_PLAYTIME) {
            return Verdict.ALLOW;                                    // empty, unprovable: nothing to protect
        }
        if (arrival.mojangUuid() == null || !dbBacked) return Verdict.DENY_UNVERIFIABLE;
        return stored.hasPassword() ? Verdict.CLAIM_REQUIRED : Verdict.DENY_UNCLAIMABLE;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
