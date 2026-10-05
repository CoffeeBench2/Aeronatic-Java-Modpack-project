package com.coffeesaerosmp.auth.lobby;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The admin status card: what one glance at the channel must tell an admin. */
class AdminStatusFormatTest {

    private static final long NOW = 1_780_000_000_000L;

    private static JsonObject embed(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private static String field(JsonObject embed, int i, String key) {
        return embed.getAsJsonArray("fields").get(i).getAsJsonObject().get(key).getAsString();
    }

    @Test
    void survivalUpShowsCountMaxAndNames() {
        JsonObject e = embed(AdminStatusFormat.build(true, 2, 20, List.of("Alex", "Steve"), 0L, List.of("Kai"), NOW));
        assertEquals("🟢 Survival — 2/20", field(e, 0, "name"));
        assertTrue(field(e, 0, "value").contains("Alex"));
        assertTrue(field(e, 0, "value").contains("Steve"));
        assertEquals("🏛 Lobby — 1", field(e, 1, "name"));
        assertTrue(field(e, 1, "value").contains("Kai"));
        assertEquals(AdminStatusFormat.GREEN, e.get("color").getAsInt());
    }

    @Test
    void survivalDownSaysDownWithRelativeTime() {
        long since = NOW - 120_000;
        JsonObject e = embed(AdminStatusFormat.build(false, -1, -1, List.of(), since, List.of(), NOW));
        assertEquals("🔴 Survival — DOWN", field(e, 0, "name"));
        assertTrue(field(e, 0, "value").contains("<t:" + since / 1000 + ":R>"));
        assertEquals(AdminStatusFormat.RED, e.get("color").getAsInt());
    }

    @Test
    void emptyWorldsSayNobodyOnline() {
        JsonObject e = embed(AdminStatusFormat.build(true, 0, 20, List.of(), 0L, List.of(), NOW));
        assertEquals("nobody online", field(e, 0, "value"));
        assertEquals("nobody online", field(e, 1, "value"));
    }

    @Test
    void sampleSmallerThanCountAddsMore() {
        // A Server List Ping samples at most 12 players; the rest must not silently disappear.
        JsonObject e = embed(AdminStatusFormat.build(true, 15, 20, List.of("A", "B", "C"), 0L, List.of(), NOW));
        assertTrue(field(e, 0, "value").endsWith("+12 more"), field(e, 0, "value"));
    }

    @Test
    void hugeListStaysUnderDiscordFieldLimit() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 200; i++) names.add("Player_Number_" + i);
        JsonObject e = embed(AdminStatusFormat.build(true, 200, 200, names, 0L, names, NOW));
        assertTrue(field(e, 0, "value").length() <= 1024);
        assertTrue(field(e, 1, "value").length() <= 1024);
        assertTrue(field(e, 1, "value").contains("more"));
    }

    @Test
    void descriptionCarriesUpdatedTimestamp() {
        JsonObject e = embed(AdminStatusFormat.build(true, 0, 20, List.of(), 0L, List.of(), NOW));
        assertTrue(e.get("description").getAsString().contains("<t:" + NOW / 1000 + ":R>"));
    }

    @Test
    void namesAreEscapedIntoValidJsonAndMarkdownSafe() {
        String json = AdminStatusFormat.build(true, 1, 20, List.of("we_ird\"name*"), 0L, List.of(), NOW);
        String v = field(embed(json), 0, "value");
        assertTrue(v.contains("we\\_ird\"name\\*"), v);   // _ and * escaped so Discord doesn't italicise
    }

    @Test
    void noMentionsAreEverPinged() {
        String json = AdminStatusFormat.build(true, 0, 20, List.of(), 0L, List.of(), NOW);
        assertEquals(0, JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());
    }
}
