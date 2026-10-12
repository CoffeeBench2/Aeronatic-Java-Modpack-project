package com.coffeesaerosmp.auth.discord.expert;

import com.coffeesaerosmp.auth.discord.expert.LiveIntent.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiveIntentTest {

    /** phrase → expected kind ("-" = not a live question) and subject ("" = none). */
    static final String[][] CASES = {
        {"who's online?", "WHO_ONLINE", ""},
        {"who is on rn", "WHO_ONLINE", ""},
        {"list players", "WHO_ONLINE", ""},
        {"is Steve_99 online?", "PLAYER_SEEN", "steve_99"},
        {"is coffee on", "PLAYER_SEEN", "coffee"},
        {"when was MrCoffeeBench last on", "PLAYER_SEEN", "mrcoffeebench"},
        {"when did jairo481 last play", "PLAYER_SEEN", "jairo481"},
        {"last seen Marsenator", "PLAYER_SEEN", "marsenator"},
        {"when is the next restart", "NEXT_RESTART", ""},
        {"restart when?", "NEXT_RESTART", ""},
        {"how do i craft andesite alloy?", "RECIPE", "andesite alloy"},
        {"how to make a brass ingot", "RECIPE", "brass ingot"},
        {"recipe for the mechanical press", "RECIPE", "mechanical press"},
        {"how are precision mechanisms made", "RECIPE", "precision mechanisms"},
        {"what is create aeronautics", "MOD_INFO", "create aeronautics"},
        {"is there sophisticated backpacks", "MOD_INFO", "sophisticated backpacks"},
        {"what does the sable mod do", "MOD_INFO", "sable"},
        // Parsed, but the server finds no item of that name and returns null, so the rules answer:
        {"how do i get spurs", "RECIPE", "spurs"},
        // Not live at all:
        {"is pvp on", "-", ""},
        {"is the server online", "-", ""},
        {"is the end open", "-", ""},
        {"how do i claim land please", "-", ""},
        {"is anyone online", "WHO_ONLINE", ""},
        {"anyone playing right now", "WHO_ONLINE", ""},
        {"my game crashed", "-", ""},
    };

    @Test
    void parsesRealPhrasings() {
        List<String> wrong = new ArrayList<>();
        for (String[] c : CASES) {
            var i = LiveIntent.parse(c[0]);
            String kind = i == null ? "-" : i.kind().name();
            String subj = i == null || i.subject() == null ? "" : i.subject();
            if (!kind.equals(c[1]) || !subj.equals(c[2])) {
                wrong.add("'" + c[0] + "' -> " + kind + "/" + subj + " (want " + c[1] + "/" + c[2] + ")");
            }
        }
        assertTrue(wrong.isEmpty(), String.join(" | ", wrong));
    }

    @Test
    void neverTreatsCommonWordsAsPlayers() {
        for (String w : new String[]{"server", "anyone", "the", "nether", "staff", "voice"}) {
            var i = LiveIntent.parse("is " + w + " online");
            assertTrue(i == null || i.kind() != Kind.PLAYER_SEEN, w);
        }
    }
}
