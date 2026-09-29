package com.coffeesaerosmp.auth.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreshStartRulesTest {

    @Test
    void refusesWhenTheProfileDoesNotExist() {
        String why = FreshStartRules.refusalReason(false, true, false);
        assertNotNull(why);
        assertTrue(why.toLowerCase().contains("no profile"), why);
    }

    @Test
    void refusesWhenTheDatabaseIsDown() {
        String why = FreshStartRules.refusalReason(true, false, false);
        assertNotNull(why);
        assertTrue(why.toLowerCase().contains("mysql"), why);
    }

    @Test
    void refusesWhenTheTargetIsStillOnline() {
        String why = FreshStartRules.refusalReason(true, true, true);
        assertNotNull(why);
        assertTrue(why.toLowerCase().contains("online"), why);
    }

    @Test
    void allowsWhenTheProfileExistsTheDbIsUpAndTheyAreOffline() {
        assertNull(FreshStartRules.refusalReason(true, true, false));
    }

    /**
     * A missing profile is reported BEFORE the database state. A typo is far more likely than an
     * outage, and telling an admin "MySQL is down" for a misspelled name sends them chasing an
     * incident that is not happening.
     */
    @Test
    void theMissingProfileMessageWinsOverTheDbMessage() {
        String why = FreshStartRules.refusalReason(false, false, false);
        assertTrue(why.toLowerCase().contains("no profile"), why);
    }

    /** Online is reported last: it is the most recoverable of the three. */
    @Test
    void theDbMessageWinsOverTheOnlineMessage() {
        String why = FreshStartRules.refusalReason(true, false, true);
        assertTrue(why.toLowerCase().contains("mysql"), why);
    }

    // ── backup folder naming ────────────────────────────────────────────────

    @Test
    void theBackupFolderNameIsSortableAndCarriesTheName() {
        String f = FreshStartRules.backupFolderName("Bronze", 1_700_000_000_000L);
        assertTrue(f.startsWith("Bronze-"), f);
        assertTrue(f.matches("Bronze-\\d{8}-\\d{6}"), f);
    }

    @Test
    void theBackupFolderNameSanitisesPathSeparatorsAndDriveColons() {
        String f = FreshStartRules.backupFolderName("bad/name:here", 1_700_000_000_000L);
        assertFalse(f.contains("/"), f);
        assertFalse(f.contains("\\"), f);
        assertFalse(f.contains(":"), f);
        assertTrue(f.matches("[A-Za-z0-9_-]+-\\d{8}-\\d{6}"), f);
    }

    /**
     * A name consisting entirely of unsafe characters must still produce a usable directory, not an
     * empty one that collides with every other such wipe.
     */
    @Test
    void anAllUnsafeNameStillYieldsADistinctFolder() {
        String f = FreshStartRules.backupFolderName("../..", 1_700_000_000_000L);
        assertFalse(f.contains(".."), f);
        assertTrue(f.matches("[A-Za-z0-9_-]+-\\d{8}-\\d{6}"), f);
    }

    @Test
    void twoWipesAtDifferentTimesDoNotCollide() {
        String a = FreshStartRules.backupFolderName("Bronze", 1_700_000_000_000L);
        String b = FreshStartRules.backupFolderName("Bronze", 1_700_000_060_000L);
        assertNotEquals(a, b);
    }
}
