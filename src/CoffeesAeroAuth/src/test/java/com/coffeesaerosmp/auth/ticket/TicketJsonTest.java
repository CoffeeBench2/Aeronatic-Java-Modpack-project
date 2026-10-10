package com.coffeesaerosmp.auth.ticket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Discord payloads /ticket sends — every one must be valid JSON Discord will accept. */
class TicketJsonTest {

    private static Ticket ticket(Ticket.Status s, String reply, String closedBy) {
        return new Ticket(42, UUID.randomUUID(), "Bob", "My \"base\" got griefed\nat spawn \\ help",
            "overworld 10 64 -20", s, 1_760_000_000_000L, closedBy, reply);
    }

    private static JsonObject parse(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void openTicketPostHasEmbedAndThreeOriginTaggedButtons() {
        JsonObject o = parse(TicketJson.message(ticket(Ticket.Status.OPEN, null, null), "SURVIVAL"));
        JsonObject embed = o.getAsJsonArray("embeds").get(0).getAsJsonObject();
        assertEquals("🎫 Ticket #42", embed.get("title").getAsString());
        assertEquals("My \"base\" got griefed\nat spawn \\ help", embed.get("description").getAsString());
        JsonArray buttons = o.getAsJsonArray("components").get(0).getAsJsonObject().getAsJsonArray("components");
        assertEquals(3, buttons.size());
        assertEquals("tkack:SURVIVAL:42",   buttons.get(0).getAsJsonObject().get("custom_id").getAsString());
        assertEquals("tkreply:SURVIVAL:42", buttons.get(1).getAsJsonObject().get("custom_id").getAsString());
        assertEquals("tkclose:SURVIVAL:42", buttons.get(2).getAsJsonObject().get("custom_id").getAsString());
        assertFalse(buttons.get(0).getAsJsonObject().get("disabled").getAsBoolean());
    }

    @Test
    void acknowledgedTicketDisablesOnlyTheAckButton() {
        JsonObject o = parse(TicketJson.update(ticket(Ticket.Status.ACK, null, null), "SURVIVAL"));
        assertEquals(7, o.get("type").getAsInt());
        JsonArray buttons = o.getAsJsonObject("data").getAsJsonArray("components")
            .get(0).getAsJsonObject().getAsJsonArray("components");
        assertTrue(buttons.get(0).getAsJsonObject().get("disabled").getAsBoolean());
        assertFalse(buttons.get(1).getAsJsonObject().get("disabled").getAsBoolean());
    }

    @Test
    void closedTicketHasNoButtonsAndShowsCloserAndReply() {
        JsonObject o = parse(TicketJson.update(ticket(Ticket.Status.CLOSED, "Ana: fixed it", "Ana"), "SURVIVAL"));
        JsonObject data = o.getAsJsonObject("data");
        assertEquals(0, data.getAsJsonArray("components").size());
        String all = data.getAsJsonArray("embeds").toString();
        assertTrue(all.contains("Closed by Ana"), all);
        assertTrue(all.contains("Ana: fixed it"), all);
    }

    @Test
    void modalsCarryTheirIdAndFitDiscordLimits() {
        JsonObject m = parse(TicketJson.modal("tkreplymodal", 42, "SURVIVAL", "Reply to ticket #42",
            "text", "Your reply (mailed to the player)", true));
        assertEquals(9, m.get("type").getAsInt());
        JsonObject data = m.getAsJsonObject("data");
        assertEquals("tkreplymodal:SURVIVAL:42", data.get("custom_id").getAsString());
        assertTrue(data.get("title").getAsString().length() <= 45);
        JsonObject input = data.getAsJsonArray("components").get(0).getAsJsonObject()
            .getAsJsonArray("components").get(0).getAsJsonObject();
        assertEquals("text", input.get("custom_id").getAsString());
        assertTrue(input.get("label").getAsString().length() <= 45);
    }

    @Test
    void controlCharactersNeverBreakTheJson() {
        Ticket t = new Ticket(1, UUID.randomUUID(), "x", "tab\there\u0001bell\rreturn", "", Ticket.Status.OPEN, 0, null, null);
        assertDoesNotThrow(() -> parse(TicketJson.message(t, "SURVIVAL")));
    }

    @Test
    void ephemeralIsFlagged() {
        JsonObject o = parse(TicketJson.ephemeral("nope \"quoted\""));
        assertEquals(4, o.get("type").getAsInt());
        assertEquals(64, o.getAsJsonObject("data").get("flags").getAsInt());
        assertEquals("nope \"quoted\"", o.getAsJsonObject("data").get("content").getAsString());
    }
}
