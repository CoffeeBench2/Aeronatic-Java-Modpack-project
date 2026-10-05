package com.coffeesaerosmp.auth.mail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The rules a letter and a parcel must pass before anything touches the database. */
class MailRulesTest {

    // ── letters ─────────────────────────────────────────────────────────────

    @Test
    void blankLetterIsRefused() {
        assertNotNull(MailRules.letterProblem("   "));
        assertNotNull(MailRules.letterProblem(null));
    }

    @Test
    void letterLengthLimitIs256() {
        assertNull(MailRules.letterProblem("a".repeat(256)));
        String problem = MailRules.letterProblem("a".repeat(257));
        assertNotNull(problem);
        assertTrue(problem.contains("256"), problem);
        assertTrue(problem.contains("257"), problem);
    }

    @Test
    void previewIsTheFirstLineCappedAt64() {
        assertEquals("hey there", MailRules.preview("hey there\\nsecond line"));
        assertEquals("hey there", MailRules.preview("hey there\nsecond line"));
        String p = MailRules.preview("x".repeat(100));
        assertEquals(64, p.length());
        assertTrue(p.endsWith("…"));
    }

    @Test
    void previewOfBlankIsAPlaceholder() {
        assertEquals("(no message)", MailRules.preview(""));
    }

    // ── what may be mailed ──────────────────────────────────────────────────

    @Test
    void numismaticsAnywhereInTheItemDataIsBlocked() {
        assertTrue(MailRules.containsBlockedSnbt("{id:\"numismatics:spur\",count:3}"));
        // a shulker box holding a coin: the coin is two levels down
        assertTrue(MailRules.containsBlockedSnbt("{id:\"minecraft:shulker_box\",components:{\"minecraft:container\":"
            + "[{slot:0,item:{id:\"numismatics:cog\",count:1}}]}}"));
        assertTrue(MailRules.containsBlockedSnbt("{id:\"numismatics:banking_guide\"}"));
    }

    @Test
    void ordinaryItemsAreAllowed() {
        assertFalse(MailRules.containsBlockedSnbt("{id:\"minecraft:iron_ingot\",count:64}"));
        assertFalse(MailRules.containsBlockedSnbt("{id:\"create:brass_ingot\",count:1}"));
        assertFalse(MailRules.containsBlockedSnbt(null));
    }

    // ── limits ──────────────────────────────────────────────────────────────

    @Test
    void dailyLimitIsReachedAtTheCap() {
        assertFalse(MailRules.limitReached(9, 10));
        assertTrue(MailRules.limitReached(10, 10));
        assertTrue(MailRules.limitReached(11, 10));
        assertFalse(MailRules.limitReached(500, 0));   // 0 = no limit
    }

    // ── the book view ───────────────────────────────────────────────────────

    @Test
    void bookPagesWrapWithoutSplittingWords() {
        List<List<String>> pages = MailRules.bookPages("the quick brown fox jumps over the lazy dog again and again", 19, 13);
        for (List<String> page : pages) {
            assertTrue(page.size() <= 13);
            for (String line : page) assertTrue(line.length() <= 19, line);
        }
        String joined = String.join(" ", pages.stream().flatMap(List::stream).toList());
        assertEquals("the quick brown fox jumps over the lazy dog again and again", joined);
    }

    @Test
    void bookPagesBreakIntoMorePagesWhenLong() {
        List<List<String>> pages = MailRules.bookPages("word ".repeat(200).trim(), 19, 13);
        assertTrue(pages.size() > 1);
    }

    @Test
    void bookPagesHonourNewlines() {
        List<List<String>> pages = MailRules.bookPages("line one\\nline two", 19, 13);
        assertEquals(List.of("line one", "line two"), pages.get(0));
    }

    @Test
    void overlongWordIsHardWrapped() {
        List<List<String>> pages = MailRules.bookPages("a".repeat(40), 19, 13);
        for (String line : pages.get(0)) assertTrue(line.length() <= 19);
    }
}
