package com.coffeesaerosmp.auth.display;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StaffBadgesTest {

    @Test
    void resolvesRankCaseInsensitively() {
        StaffBadges b = new StaffBadges("MrCoffeeBench", "Alice, Bob", "");
        assertEquals("§4[OWNER] ", b.badgeFor("mrcoffeebench"));
        assertEquals("§c[ADMIN] ", b.badgeFor("ALICE"));
        assertEquals("§c[ADMIN] ", b.badgeFor("bob"));
    }

    @Test
    void unlistedPlayerGetsNoBadge() {
        StaffBadges b = new StaffBadges("MrCoffeeBench", "", "");
        assertEquals("", b.badgeFor("Steve"));
    }

    @Test
    void ownerWinsWhenListedTwice() {
        StaffBadges b = new StaffBadges("Coffee", "Coffee", "Coffee");
        assertEquals("§4[OWNER] ", b.badgeFor("Coffee"));
    }

    @Test
    void handlesEmptyAndBlankConfig() {
        StaffBadges b = new StaffBadges("", "  ", null);
        assertEquals("", b.badgeFor("Anyone"));
    }

    @Test
    void ignoresStrayWhitespaceAndEmptyEntries() {
        StaffBadges b = new StaffBadges("", " Alice ,, Bob ,", "");
        assertEquals("§c[ADMIN] ", b.badgeFor("Alice"));
        assertEquals("§c[ADMIN] ", b.badgeFor("Bob"));
    }

    @Test
    void nullUsernameIsSafe() {
        StaffBadges b = new StaffBadges("Coffee", "", "");
        assertEquals("", b.badgeFor(null));
    }

    @Test
    void isStaffMatchesBadgePresence() {
        StaffBadges b = new StaffBadges("Coffee", "", "Dave");
        assertTrue(b.isStaff("Coffee"));
        assertTrue(b.isStaff("dave"));
        assertFalse(b.isStaff("Steve"));
    }
}
