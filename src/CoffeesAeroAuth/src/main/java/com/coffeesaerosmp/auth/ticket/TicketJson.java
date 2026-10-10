package com.coffeesaerosmp.auth.ticket;

import java.time.Instant;

/**
 * Discord payloads for /ticket, built as raw JSON strings like the rest of the bot code. Pure — no
 * Minecraft types — so {@code TicketJsonTest} can parse every payload. {@code origin} is
 * {@code NameApprovalQueue.originTag()}: the lobby shares the bot token and must ignore our buttons.
 */
public final class TicketJson {

    private static final int OPEN_COLOR = 0x5865F2, ACK_COLOR = 0xFEE75C, CLOSED_COLOR = 0x4F545C;

    private TicketJson() {}

    /** Body for POST /channels/{id}/messages. */
    public static String message(Ticket t, String origin) {
        return "{" + body(t, origin) + "}";
    }

    /** Interaction response type 7 (UPDATE_MESSAGE) re-rendering the ticket. */
    public static String update(Ticket t, String origin) {
        return "{\"type\":7,\"data\":{" + body(t, origin) + "}}";
    }

    /** Interaction response type 9 (MODAL) with one paragraph input. Title and label must be ≤ 45 chars. */
    public static String modal(String action, long id, String origin, String title, String field,
                               String label, boolean required) {
        return "{\"type\":9,\"data\":{\"custom_id\":\"" + action + ":" + origin + ":" + id + "\","
            + "\"title\":\"" + esc(title) + "\",\"components\":[{\"type\":1,\"components\":[{"
            + "\"type\":4,\"custom_id\":\"" + field + "\",\"label\":\"" + esc(label) + "\","
            + "\"style\":2,\"max_length\":900,\"required\":" + required + "}]}]}}";
    }

    /** Interaction response type 4, visible only to the clicker. */
    public static String ephemeral(String text) {
        return "{\"type\":4,\"data\":{\"flags\":64,\"content\":\"" + esc(text) + "\"}}";
    }

    private static String body(Ticket t, String origin) {
        int color = switch (t.status()) { case OPEN -> OPEN_COLOR; case ACK -> ACK_COLOR; case CLOSED -> CLOSED_COLOR; };
        String status = switch (t.status()) {
            case OPEN -> "🟦 Open";
            case ACK -> "🟨 Acknowledged";
            case CLOSED -> "⬛ Closed by " + (t.closedBy() == null ? "staff" : t.closedBy());
        };
        StringBuilder fields = new StringBuilder()
            .append(field("Player", t.name(), true))
            .append(',').append(field("Location", "`" + t.location() + "`", true))
            .append(',').append(field("Status", status, true));
        if (t.lastReply() != null && !t.lastReply().isBlank())
            fields.append(',').append(field("Last reply", trim(t.lastReply(), 1000), false));
        String embed = "{\"title\":\"🎫 Ticket #" + t.id() + "\",\"description\":\"" + esc(t.message()) + "\","
            + "\"color\":" + color + ",\"fields\":[" + fields + "],"
            + "\"timestamp\":\"" + Instant.ofEpochMilli(t.createdAt()) + "\"}";
        String components = t.status() == Ticket.Status.CLOSED ? "[]"
            : "[{\"type\":1,\"components\":["
              + button(3, "✅ Acknowledge", "tkack", t.id(), origin, t.status() == Ticket.Status.ACK) + ","
              + button(1, "✉ Reply", "tkreply", t.id(), origin, false) + ","
              + button(4, "🔒 Close", "tkclose", t.id(), origin, false) + "]}]";
        return "\"embeds\":[" + embed + "],\"components\":" + components;
    }

    private static String field(String name, String value, boolean inline) {
        return "{\"name\":\"" + esc(name) + "\",\"value\":\"" + esc(value) + "\",\"inline\":" + inline + "}";
    }

    private static String button(int style, String label, String action, long id, String origin, boolean disabled) {
        return "{\"type\":2,\"style\":" + style + ",\"label\":\"" + esc(label) + "\",\"custom_id\":\""
            + action + ":" + origin + ":" + id + "\",\"disabled\":" + disabled + "}";
    }

    static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '\\' -> b.append("\\\\");
                case '"'  -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> {}
                case '\t' -> b.append(' ');
                default   -> { if (ch < 0x20) b.append(' '); else b.append(ch); }
            }
        }
        return b.toString();
    }
}
