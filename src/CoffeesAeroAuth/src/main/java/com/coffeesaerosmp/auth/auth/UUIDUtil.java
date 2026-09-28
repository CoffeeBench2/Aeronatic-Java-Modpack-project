package com.coffeesaerosmp.auth.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class UUIDUtil {

    private UUIDUtil() {}

    /*
     * 🔴 isPremiumUUID(uuid) -> uuid.version() == 4 WAS HERE AND WAS DELETED on 2026-09-29.
     *
     * Its javadoc described the OLD topology: "when behind Velocity + AuthVelocity, premium players
     * keep their real v4 UUID". That stopped being true when the transfer gate replaced the proxy —
     * the client now connects DIRECTLY to an online-mode=false backend, so no Mojang identity is
     * forwarded and the server mints EVERY uuid as the v3 md5("OfflinePlayer:" + name). Measured
     * across all 403 rows: 283/283 premium and 120/120 offline are v3, so the method could only ever
     * return false.
     *
     * It had four callers and every one silently took the offline branch:
     *   - PlayerAuthEvents  a documented premium exemption from junk-name validation that therefore
     *                       never applied once
     *   - ProfileStore x2   a "prefer the premium row" tie-break for duplicate names that degenerated
     *                       into playtime/last-seen
     *   - ProfileCommands   a diagnostic that printed "v3 offline" for literally every account, in the
     *                       one command an admin uses to untangle duplicates
     *
     * Deleted rather than renamed so no new caller can reintroduce it. The authoritative test is
     * PlayerProfile#isPremium(), which reads players.account_type.
     *
     * expectedOfflineUUID below is the SOUND half of this class and is still used — it derives the
     * uuid, rather than trying to read an identity back out of one.
     */

    /**
     * Compute what offline UUID vanilla would assign for a given username.
     * Used to verify that an offline player's UUID matches their claimed username
     * and has not been spoofed.
     */
    public static UUID expectedOfflineUUID(String username) {
        return UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * True when {@code candidate} is the offline UUID vanilla would mint for {@code username} —
     * i.e. the connection is the SAME HUMAN as the premium account of that name, arriving without a
     * verified identity, not somebody impersonating them.
     *
     * <p>WHY THIS EXISTS (2026-08-08). A premium player is resolved OFFLINE by five separate paths:
     * cookie timeout, missing {@code AERO_GATE_SECRET}, invalid cookie, absent cookie, and direct
     * connect. When that happens the backend (which is {@code online-mode=false}) assigns them
     * {@code md5("OfflinePlayer:" + name)} instead of their real Mojang UUID — a DIFFERENT UUID for
     * the same person. The anti-spoof check then saw their own name owned by their own premium
     * profile under another UUID and kicked them as an impersonator of themselves:
     * <em>"The name X is reserved by (or too close to) a verified player (X)"</em>.
     *
     * <p>That is the "I'm premium but it says I'm offline" report. The name is only impersonated if
     * the offline UUID does NOT derive from that same name — a real impostor picks someone else's
     * name, so their offline UUID derives from the name they chose, which still matches. The
     * distinguishing fact is therefore whether the ACCOUNT NAMES agree, which is what the caller
     * checks by passing the premium owner's own username here.
     */
    public static boolean isSelfOfflineAlias(UUID candidate, String username) {
        if (candidate == null || username == null || username.isBlank()) return false;
        return expectedOfflineUUID(username).equals(candidate);
    }
}
