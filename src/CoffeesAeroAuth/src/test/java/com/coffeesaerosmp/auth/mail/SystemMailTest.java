package com.coffeesaerosmp.auth.mail;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The rules a System mail draft must pass. */
class SystemMailTest {

    @Test
    void typesCycleThroughAllFourAndWrap() {
        SystemMail.Type t = SystemMail.Type.WARNING;
        for (int i = 0; i < 4; i++) t = t.next();
        assertEquals(SystemMail.Type.WARNING, t);
        assertEquals(SystemMail.Type.UPDATE, SystemMail.Type.WARNING.next());
    }

    @Test
    void subjectCarriesTheTypeAndFitsTheColumn() {
        String s = SystemMail.Type.PATCH_NOTES.subject("  " + "x".repeat(SystemMail.SUBJECT_MAX) + "  ");
        assertTrue(s.startsWith("📜 Patch notes: "), s);
        assertTrue(s.length() <= 64, "mail.subject is VARCHAR(64), got " + s.length());
    }

    @Test
    void headerIsColouredPerType() {
        assertTrue(SystemMail.Type.WARNING.header().startsWith("§c§l⚠ WARNING"));
        assertTrue(SystemMail.Type.UPDATE.header().startsWith("§6§l🛠 UPDATE"));
    }

    @Test
    void bookTextJoinsPagesAndDropsBlankOnes() {
        assertEquals("Page one\n\nPage three", SystemMail.bookText(Arrays.asList("Page one\r\n", "   ", null, "Page three")));
        assertEquals("", SystemMail.bookText(List.of()));
    }

    @Test
    void draftProblems() {
        var ONE = SystemMail.Audience.ONE;
        var ALL = SystemMail.Audience.ALL;
        assertNotNull(SystemMail.problem("", "body", ALL, null));
        assertNotNull(SystemMail.problem("x".repeat(SystemMail.SUBJECT_MAX + 1), "body", ALL, null));
        assertNotNull(SystemMail.problem("Hi", " ", ALL, null));
        assertNotNull(SystemMail.problem("Hi", "x".repeat(SystemMail.BODY_MAX + 1), ALL, null));
        assertNotNull(SystemMail.problem("Hi", "body", ONE, ""));
        assertNull(SystemMail.problem("Hi", "body", ONE, "Bob"));
        assertNull(SystemMail.problem("Hi", "x".repeat(SystemMail.BODY_MAX), ALL, null));
    }

    @Test
    void audienceCycles() {
        assertEquals(SystemMail.Audience.ONLINE, SystemMail.Audience.ONE.next());
        assertEquals(SystemMail.Audience.ONE, SystemMail.Audience.ALL.next());
    }
}
