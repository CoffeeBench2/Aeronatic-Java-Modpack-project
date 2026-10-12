package com.coffeesaerosmp.auth.discord.expert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a Discord message (or a knowledge-base phrase) into comparable tokens. The SAME pipeline runs on both
 * sides, so "teleporting", "TP" and "teleport" all meet at {@code teleport}.
 *
 * <p>Steps: lowercase → strip Discord markup / urls → drop apostrophes ({@code can't} → {@code cant}) → split on
 * anything that isn't a letter or digit → slang/synonym map → light suffix stemming → stop-word removal.
 * Pure and deterministic; unit-tested.
 */
public final class Normalizer {

    private Normalizer() {}

    /** Player shorthand → the word the knowledge base uses. Applied per token, before stemming. */
    static final Map<String, String> SYNONYMS = Map.ofEntries(
        Map.entry("tp", "teleport"), Map.entry("tpa", "tpa"), Map.entry("teleportation", "teleport"),
        Map.entry("ip", "address"), Map.entry("addr", "address"), Map.entry("srv", "server"), Map.entry("svr", "server"),
        Map.entry("sever", "server"), Map.entry("servr", "server"), Map.entry("smp", "server"),
        Map.entry("u", "you"), Map.entry("ur", "your"), Map.entry("r", "are"), Map.entry("y", "why"),
        Map.entry("mem", "ram"), Map.entry("memory", "ram"), Map.entry("allocate", "ram"), Map.entry("allocation", "ram"),
        Map.entry("gigs", "gb"), Map.entry("gig", "gb"),
        Map.entry("cracked", "offline"), Map.entry("nonpremium", "offline"), Map.entry("tlauncher", "offline"),
        Map.entry("sklauncher", "offline"), Map.entry("pirated", "offline"), Map.entry("noprem", "offline"),
        Map.entry("prem", "premium"), Map.entry("paid", "premium"), Map.entry("bought", "premium"),
        Map.entry("mc", "minecraft"), Map.entry("modpack", "pack"), Map.entry("mrpack", "pack"),
        Map.entry("dc", "discord"), Map.entry("disc", "discord"),
        Map.entry("pwd", "password"), Map.entry("pass", "password"), Map.entry("passwd", "password"),
        Map.entry("pw", "password"), Map.entry("pasword", "password"), Map.entry("passowrd", "password"),
        Map.entry("dl", "download"), Map.entry("instal", "install"),
        Map.entry("cant", "cannot"), Map.entry("cnt", "cannot"), Map.entry("unable", "cannot"),
        Map.entry("wont", "cannot"), Map.entry("doesnt", "not"), Map.entry("dont", "not"), Map.entry("isnt", "not"),
        Map.entry("didnt", "not"), Map.entry("aint", "not"),
        Map.entry("plz", ""), Map.entry("pls", ""), Map.entry("please", ""), Map.entry("bro", ""), Map.entry("bruh", ""),
        Map.entry("guys", ""), Map.entry("hey", ""), Map.entry("yo", ""), Map.entry("hi", ""), Map.entry("hello", ""),
        Map.entry("coffeebot", ""), Map.entry("bot", ""),
        Map.entry("ms", "ping"), Map.entry("latency", "ping"), Map.entry("laggy", "lag"), Map.entry("lagy", "lag"),
        Map.entry("stutter", "lag"), Map.entry("freeze", "lag"), Map.entry("frozen", "lag"),
        Map.entry("fps", "fps"), Map.entry("framerate", "fps"),
        Map.entry("coins", "spur"), Map.entry("coin", "spur"), Map.entry("money", "spur"), Map.entry("currency", "spur"),
        Map.entry("cash", "spur"),
        Map.entry("airship", "ship"), Map.entry("airships", "ship"), Map.entry("zeppelin", "ship"),
        Map.entry("blimp", "ship"), Map.entry("plane", "ship"), Map.entry("aeronautics", "ship"),
        Map.entry("chunk", "claim"), Map.entry("chunks", "claim"), Map.entry("land", "claim"),
        Map.entry("grave", "corpse"), Map.entry("gravestone", "corpse"), Map.entry("tombstone", "corpse"),
        Map.entry("body", "corpse"), Map.entry("loot", "item"),
        Map.entry("mailbox", "mail"), Map.entry("inbox", "mail"),
        Map.entry("lvl", "level"), Map.entry("lv", "level"), Map.entry("xp", "level"),
        Map.entry("hrs", "hour"), Map.entry("hr", "hour"), Map.entry("playtime", "playtime"),
        Map.entry("shaderpack", "shader"), Map.entry("bsl", "shader"), Map.entry("complementary", "shader"),
        Map.entry("iris", "shader"), Map.entry("dh", "distant"), Map.entry("lod", "distant"),
        Map.entry("staff", "admin"), Map.entry("mod", "mod"), Map.entry("mods", "mod"), Map.entry("moderator", "admin"),
        Map.entry("owner", "admin"), Map.entry("op", "admin"), Map.entry("ops", "admin"),
        Map.entry("kicked", "kick"), Map.entry("disconnected", "kick"), Map.entry("dc'd", "kick"),
        Map.entry("crashed", "crash"), Map.entry("crashing", "crash"), Map.entry("crashes", "crash"),
        Map.entry("closes", "crash"), Map.entry("closing", "crash"),
        Map.entry("vc", "voice"), Map.entry("mic", "voice"), Map.entry("proximity", "voice"),
        Map.entry("cf", "curseforge"), Map.entry("modrinth", "modrinth")
    );

    /**
     * Words that carry no topic on their own. Removed from both sides. "my", "me", "many" and "much" are KEPT on
     * purpose: they are what separates "my level" from "how do levels work" and "how many players". So are the
     * particles of phrasal verbs (in, on, up, out, off) and "who"/"where": without them "log in", "set up",
     * "pick up" and "who owns" collapsed to one generic word (KnowledgeBase now refuses such phrases at load).
     */
    static final Set<String> STOP = Set.of(
        "a", "an", "the", "to", "of", "at", "for", "and", "or", "is", "are", "be", "been", "was",
        "were", "it", "its", "i", "im", "you", "your", "we", "our", "us", "they", "them", "this",
        "that", "these", "those", "do", "does", "did", "doing", "can", "could", "should", "would", "will", "shall",
        "how", "what", "whats", "when", "which", "whom", "why", "there", "here", "any", "some", "so",
        "just", "really", "very", "also", "too", "get", "got", "gets", "getting", "have", "has", "had", "with",
        "from", "about", "if", "then", "than", "like", "ok", "okay", "know", "anyone", "someone", "somebody",
        "anybody", "help", "question", "pls", "please", "now", "still", "again", "hmm", "um", "uh",
        "lol", "lmao", "idk", "tell", "say", "said", "need", "want", "wanna", "gonna", "way", "thing",
        "by", "as", "into", "all");

    /** Full pipeline. Never null. */
    public static List<String> tokens(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String s = raw.toLowerCase(Locale.ROOT)
            .replaceAll("<@[!&]?\\d+>", " ")
            .replaceAll("<#\\d+>", " ")
            .replaceAll("<a?:\\w+:\\d+>", " ")
            .replaceAll("https?://\\S+", " ")
            .replace("'", "").replace("’", "")
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
        if (s.isEmpty()) return out;
        for (String t : s.split(" ")) {
            String m = synonym(t);
            if (m.isEmpty()) continue;
            for (String part : m.split(" ")) {
                String st = stem(part);
                if (!st.isEmpty() && !STOP.contains(st) && !STOP.contains(part)) out.add(st);
            }
        }
        return out;
    }

    /** Exact synonym, else a synonym key one typo away ("memmory" → memory → ram) for words of 5+ letters. */
    static String synonym(String t) {
        String exact = SYNONYMS.get(t);
        if (exact != null) return exact;
        if (t.length() >= 5) {
            for (var e : SYNONYMS.entrySet()) {
                if (e.getKey().length() >= 5 && nearlyEqual(t, e.getKey())) return e.getValue();
            }
        }
        return t;
    }

    /**
     * Deliberately light: trims common English inflections so "claims/claimed/claiming" meet at "claim". Short
     * words are left alone, which keeps "ram", "end", "gb" and "ping" intact.
     */
    static String stem(String w) {
        if (w.length() <= 3 || w.chars().allMatch(Character::isDigit)) return w;
        if (w.endsWith("ies") && w.length() > 5) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("ing") && w.length() > 5) return unsuffix(w.substring(0, w.length() - 3));
        if (w.endsWith("ed") && w.length() > 4) return unsuffix(w.substring(0, w.length() - 2));
        if (w.endsWith("es") && w.length() > 4 && (w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes")))
            return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && w.length() > 3) return w.substring(0, w.length() - 1);
        return w;
    }

    /** Undouble ("logg" → log) OR restore a silent e ("tim" → time), never both ("logging" must not become "loge"). */
    private static String unsuffix(String w) {
        String u = undouble(w);
        return u.length() != w.length() ? u : silentE(w);
    }

    /** "timed" → "tim" → "time", "closed" → "clos" → "close": a short consonant-vowel-consonant stem had an e. */
    private static String silentE(String w) {
        int n = w.length();
        if (n < 3 || n > 4) return w;
        char a = w.charAt(n - 3), b = w.charAt(n - 2), c = w.charAt(n - 1);
        boolean cvc = !vowel(a) && vowel(b) && !vowel(c) && "wxy".indexOf(c) < 0;
        return cvc ? w + "e" : w;
    }

    private static boolean vowel(char c) { return "aeiou".indexOf(c) >= 0; }

    /** "logged" → "logg" → "log"; "stopped" → "stop". */
    private static String undouble(String w) {
        int n = w.length();
        if (n >= 3 && w.charAt(n - 1) == w.charAt(n - 2) && "aeiou".indexOf(w.charAt(n - 1)) < 0
            && w.charAt(n - 1) != 'l' && w.charAt(n - 1) != 's') return w.substring(0, n - 1);
        if (n >= 3 && w.endsWith("ll") ) return w;
        return w;
    }

    /** Edit distance ≤ 1 (one insert, delete or substitution), for typo tolerance on longer words. */
    static boolean nearlyEqual(String a, String b) {
        if (a.equals(b)) return true;
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1 || Math.min(la, lb) < 5) return false;
        // Typos almost never hit the first letter, and allowing it made "launcher" equal "tlauncher".
        if (a.charAt(0) != b.charAt(0)) return false;
        int i = 0, j = 0, edits = 0;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++edits > 1) return false;
            if (la > lb) i++;
            else if (lb > la) j++;
            else { i++; j++; }
        }
        return edits + (la - i) + (lb - j) <= 1;
    }
}
