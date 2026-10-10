package com.coffeesaerosmp.auth.mail;

import java.util.List;

/**
 * Rules for staff "System" mail ({@code /mail system}): the four mail types and the limits a draft must
 * pass before it can be sent. Pure — no Minecraft types — so {@code SystemMailTest} covers it without a game.
 * Sent under {@link MailService#SYSTEM}, never the staff member's own name.
 */
public final class SystemMail {

    /** Long enough for real patch notes; the mail body column is TEXT since 1.13.27. */
    public static final int BODY_MAX = 8000;
    public static final int SUBJECT_MAX = 48;   // + the type prefix stays inside mail.subject VARCHAR(64)

    private SystemMail() {}

    public enum Type {
        WARNING("⚠", "Warning", 'c'),
        UPDATE("🛠", "Update", '6'),
        PATCH_NOTES("📜", "Patch notes", 'b'),
        ANNOUNCEMENT("📢", "Announcement", 'a');

        public final String icon;
        public final String label;
        /** Minecraft colour code (the character after §). */
        public final char color;

        Type(String icon, String label, char color) {
            this.icon = icon;
            this.label = label;
            this.color = color;
        }

        public Type next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** What the mailbox shows, e.g. "⚠ Warning: Griefing near spawn". */
        public String subject(String subject) {
            return icon + " " + label + ": " + subject.trim();
        }

        /** First lines of the book. */
        public String header() {
            return "§" + color + "§l" + icon + " " + label.toUpperCase(java.util.Locale.ROOT) + "§r\n\n";
        }
    }

    /** Who a System mail goes to. */
    public enum Audience {
        ONE("One player"), ONLINE("Everyone online"), ALL("Every player");

        public final String label;

        Audience(String label) {
            this.label = label;
        }

        public Audience next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Book & Quill / written book pages → one body. Blank pages dropped, pages separated by a blank line. */
    public static String bookText(List<String> pages) {
        StringBuilder b = new StringBuilder();
        for (String p : pages) {
            if (p == null) continue;
            String t = p.replace("\r", "").strip();
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append("\n\n");
            b.append(t);
        }
        return b.toString();
    }

    /** Null when the draft can be sent, otherwise the reason it cannot (shown to the staff member). */
    public static String problem(String subject, String body, Audience audience, String playerName) {
        if (subject == null || subject.isBlank()) return "Set a subject first.";
        if (subject.strip().length() > SUBJECT_MAX)
            return "Subject is too long (" + subject.strip().length() + "/" + SUBJECT_MAX + ").";
        if (body == null || body.isBlank()) return "Put a Book & Quill with the message in the book slot.";
        if (body.length() > BODY_MAX) return "The book is too long (" + body.length() + "/" + BODY_MAX + " characters).";
        if (audience == Audience.ONE && (playerName == null || playerName.isBlank())) return "Pick the player first.";
        return null;
    }
}
