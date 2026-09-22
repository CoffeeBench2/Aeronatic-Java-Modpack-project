package com.coffeesaerosmp.auth.admin;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The decision half of {@code /authmod freshstart}: when to refuse, and what to call the backup.
 *
 * <p>Pure, so every refusal path is unit-tested rather than discovered in production on a real
 * player's account. This is the only feature in the mod that deletes player data.
 */
public final class FreshStartRules {

    /** UTC so backup folders sort correctly regardless of which server wrote them. */
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private FreshStartRules() {}

    /**
     * Why this wipe must not run, or null if it may.
     *
     * <p>Order is deliberate. A missing profile is reported FIRST because a typo is far more likely
     * than an outage, and "MySQL is DOWN" in response to a misspelled name sends an admin chasing an
     * incident that is not happening. "Still online" comes last because it is the most trivially
     * recoverable of the three.
     *
     * @param profileExists does a profile exist for the resolved uuid
     * @param dbUp          is MySQL available
     * @param stillOnline   is the target still in the player list
     */
    public static String refusalReason(boolean profileExists, boolean dbUp, boolean stillOnline) {
        if (!profileExists) {
            return "No profile for that name — check the spelling. Nothing was changed.";
        }
        if (!dbUp) {
            // A wipe that clears the files but cannot clear the profile columns leaves a
            // half-wiped account, and the flat-file fallback would then disagree with the database.
            return "MySQL is DOWN. Refusing — a wipe now would clear the files but not the profile "
                 + "columns, leaving a half-wiped account. Retry when DB: UP.";
        }
        if (stillOnline) {
            // Vanilla holds a logged-in player's .dat open and rewrites it on disconnect, so
            // anything deleted underneath them is silently restored moments later.
            return "Target is still ONLINE. They hold their playerdata open and rewrite it on "
                 + "disconnect, which would undo the wipe.";
        }
        return null;
    }

    /**
     * Sortable, name-carrying backup folder: {@code Bronze-20261122-031000}.
     *
     * <p>Every character outside {@code [A-Za-z0-9_-]} is replaced, which matters more than it
     * looks: this string becomes a directory name built from an admin-supplied argument, so a name
     * containing {@code ../} or a drive colon must not be able to steer the write anywhere.
     */
    public static String backupFolderName(String playerName, long epochMillis) {
        String safe = playerName == null ? "" : playerName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) safe = "unnamed";       // never produce a bare timestamp folder
        return safe + "-" + STAMP.format(Instant.ofEpochMilli(epochMillis));
    }
}
