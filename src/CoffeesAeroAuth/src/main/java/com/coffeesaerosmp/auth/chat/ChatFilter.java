package com.coffeesaerosmp.auth.chat;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.coffeesaerosmp.auth.util.AsyncIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Word filter for public chat — censors profanity, blocks hate speech, alerts staff.
 *
 * <h2>Why the matching is not a plain {@code contains}</h2>
 *
 * A literal substring test fails in both directions on day one. It misses {@code f u c k},
 * {@code f.u.c.k}, {@code fuuuck} and {@code h1tler}; and it fires on {@code classic},
 * {@code grass} and {@code assassin} — the Scunthorpe problem, which is how filters end up hated
 * and then switched off.
 *
 * <p>So each word compiles to a pattern that is deliberate about both:
 * <pre>
 *   fuck  →  (?&lt;![a-z0-9])f+[\W_]{0,3}u+[\W_]{0,3}c+[\W_]{0,3}k+(?![a-z0-9])
 * </pre>
 * <ul>
 *   <li>{@code x+} on every letter absorbs {@code fuuuck}.</li>
 *   <li>{@code [\W_]{0,3}} between letters absorbs spacing and punctuation — <b>bounded</b>, because
 *       an unbounded gap would happily match across half a sentence.</li>
 *   <li>The lookarounds require a word boundary at each end, which is what keeps {@code ass} out of
 *       {@code classic}. This is the part a naive filter leaves out.</li>
 * </ul>
 *
 * <p>Leet substitution is handled by normalising the INPUT ({@code 4→a}, {@code 1→i}, {@code $→s},
 * …) rather than by widening every pattern. 🔑 <b>That normalisation is strictly length-preserving,
 * one character to one character</b> — which is what lets a match found in the normalised string be
 * used as an index into the ORIGINAL string, so censoring can star out exactly the right characters
 * without re-finding anything.
 *
 * <h2>Two severities, because "hitler" and "fuck" are not the same problem</h2>
 *
 * {@link Action#CENSOR} stars the word and lets the message through — swearing is rudeness.
 * {@link Action#BLOCK} drops the message entirely and raises a HIGH watchdog alert — slurs and
 * hate terms are an incident. Treating them identically would either nuke chat over "shit" or
 * shrug at a slur.
 *
 * <p>Rules live in {@code chat_filter.json} beside the other auth data and are edited in-game with
 * {@code /authmod filter}, so tuning the list never needs a restart.
 */
public final class ChatFilter {

    private ChatFilter() {}

    /** What to do with a message containing the word. */
    public enum Action { CENSOR, BLOCK }

    /** The result of screening one message. */
    public record Result(Action action, String word, String text) {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_GAP = 3;

    /** word (lowercase) → action. Insertion-ordered so the file stays readable. */
    private static final Map<String, Action> rules = new LinkedHashMap<>();
    /** word → compiled matcher. Rebuilt whenever the rules change; never edited piecemeal. */
    private static final Map<String, Pattern> compiled = new LinkedHashMap<>();

    private static Path file;

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Load the word list, seeding a starter list on first run. Called once at server start. */
    public static void initialize(Path dataDir) {
        file = dataDir == null ? null : dataDir.resolve("chat_filter.json");
        boolean loaded = false;
        try {
            if (file != null && Files.exists(file)) {
                Map<String, String> raw = GSON.fromJson(Files.readString(file),
                    new TypeToken<LinkedHashMap<String, String>>() {}.getType());
                if (raw != null) {
                    for (Map.Entry<String, String> e : raw.entrySet()) {
                        try {
                            rules.put(normalizeWord(e.getKey()), Action.valueOf(e.getValue()));
                        } catch (IllegalArgumentException ignored) {
                            CoffeesAeroAuth.LOGGER.warn("[ChatFilter] Unknown action '{}' for '{}' — skipped.",
                                e.getValue(), e.getKey());
                        }
                    }
                    loaded = true;
                }
            }
        } catch (Exception ex) {
            CoffeesAeroAuth.LOGGER.warn("[ChatFilter] load failed, seeding defaults: {}", ex.getMessage());
        }
        if (!loaded || rules.isEmpty()) {
            seedDefaults();
            save();
        }
        recompile();
        CoffeesAeroAuth.LOGGER.info("[ChatFilter] {} rule(s) active ({} block, {} censor).",
            rules.size(), count(Action.BLOCK), count(Action.CENSOR));
    }

    private static long count(Action a) {
        return rules.values().stream().filter(v -> v == a).count();
    }

    /**
     * The starter list. Deliberately short — it exists so the feature does something on the first
     * boot, not to be exhaustive. Every server has its own line and admins extend it in-game.
     */
    private static void seedDefaults() {
        // ⚠ "chink" is deliberately NOT seeded despite being a slur: "a chink in the armour" is
        // ordinary English, and a false BLOCK costs a dropped message AND a HIGH staff alert. Words
        // whose innocent use is common belong on an admin's explicit decision, not in a default.
        for (String w : new String[]{
                "hitler", "heilhitler", "nazi", "nigger", "nigga",
                "faggot", "kike", "tranny"}) {
            rules.put(w, Action.BLOCK);
        }
        for (String w : new String[]{
                "fuck", "shit", "bitch", "cunt", "asshole",
                "dick", "whore", "slut", "bastard", "pussy"}) {
            rules.put(w, Action.CENSOR);
        }
    }

    private static void save() {
        if (file == null) return;
        Map<String, String> out = new LinkedHashMap<>();
        rules.forEach((k, v) -> out.put(k, v.name()));
        AsyncIo.submit(() -> {
            try {
                Files.writeString(file, GSON.toJson(out));
            } catch (Exception ex) {
                CoffeesAeroAuth.LOGGER.warn("[ChatFilter] save failed: {}", ex.getMessage());
            }
        });
    }

    // ── Rule management ───────────────────────────────────────────────────────

    /**
     * Outcome of an add: the key actually stored (the normalised form, which is what an admin needs
     * to see — type {@code F.U.C.K} and it is stored as {@code fuck}), and the action it replaced.
     */
    public record AddResult(String key, Action previous) {}

    /**
     * Adds a word, or changes the action of one already listed (which is what "editing" a rule
     * means here — there is nothing else about a rule to edit).
     *
     * @return null if the word normalises to nothing (e.g. pure punctuation); otherwise the stored
     *         key and the action it previously had, which is null when the word is new
     */
    public static synchronized AddResult add(String word, Action action) {
        String key = normalizeWord(word);
        if (key.isEmpty()) return null;
        Action previous = rules.put(key, action);
        recompile();
        save();
        return new AddResult(key, previous);
    }

    /** @return true if the word was listed and is now gone */
    public static synchronized boolean remove(String word) {
        if (rules.remove(normalizeWord(word)) == null) return false;
        recompile();
        save();
        return true;
    }

    /** Every rule, BLOCK first then alphabetical — the order an admin wants to read them in. */
    public static synchronized List<Map.Entry<String, Action>> list() {
        List<Map.Entry<String, Action>> out = new ArrayList<>(rules.entrySet());
        out.sort(Comparator
            .comparing((Map.Entry<String, Action> e) -> e.getValue() == Action.BLOCK ? 0 : 1)
            .thenComparing(Map.Entry::getKey));
        return out;
    }

    public static int size() { return rules.size(); }

    // ── Screening ─────────────────────────────────────────────────────────────

    /**
     * Screens one message.
     *
     * @return null when the message is clean; otherwise the action to take, the word that tripped
     *         it, and — for {@link Action#CENSOR} — the message with the offending runs starred out
     */
    public static synchronized Result check(String message) {
        if (message == null || message.isEmpty() || compiled.isEmpty()) return null;
        String normalized = normalize(message);

        // BLOCK wins over CENSOR regardless of position: a message containing both a slur and a
        // swear word is a slur incident, and censoring it would publish the slur with stars in the
        // wrong place. So the block pass runs first and completely.
        for (Map.Entry<String, Pattern> e : compiled.entrySet()) {
            if (rules.get(e.getKey()) != Action.BLOCK) continue;
            if (e.getValue().matcher(normalized).find()) {
                return new Result(Action.BLOCK, e.getKey(), message);
            }
        }

        StringBuilder text = new StringBuilder(message);
        String hit = null;
        for (Map.Entry<String, Pattern> e : compiled.entrySet()) {
            if (rules.get(e.getKey()) != Action.CENSOR) continue;
            Matcher m = e.getValue().matcher(normalized);
            while (m.find()) {
                if (hit == null) hit = e.getKey();
                // Safe because normalize() is length-preserving: indices into the normalised
                // string address exactly the same characters in the original.
                for (int i = m.start(); i < m.end(); i++) text.setCharAt(i, '*');
            }
        }
        return hit == null ? null : new Result(Action.CENSOR, hit, text.toString());
    }

    // ── Matching internals ────────────────────────────────────────────────────

    private static void recompile() {
        compiled.clear();
        for (String word : rules.keySet()) {
            try {
                compiled.put(word, Pattern.compile(buildRegex(word)));
            } catch (Exception ex) {
                CoffeesAeroAuth.LOGGER.warn("[ChatFilter] could not compile '{}': {}", word, ex.getMessage());
            }
        }
    }

    /**
     * Common word endings allowed after a filtered word.
     *
     * <p>🔴 WITHOUT THIS THE FILTER MISSES THE COMMONEST FORM OF THE WORD. A bare
     * {@code (?![a-z0-9])} at the end refuses any trailing letter, so {@code fuck} matched and
     * <b>{@code fucking} did not</b> — and "fucking" is by far the more common of the two. Caught by
     * the test suite, not by reading the regex.
     *
     * <p>It is a fixed short list rather than a free {@code [a-z]*} because an open-ended tail is
     * what re-opens the Scunthorpe problem from the other side: {@code ass} would swallow
     * {@code assume} and {@code assassin}. With this list they stay clean ({@code ume} and
     * {@code assin} are not endings) while {@code shits}, {@code dicks}, {@code bitching} and
     * {@code fucker} are all caught. The leading {@code (?<![a-z0-9])} is what keeps {@code ass} out
     * of {@code grass} and {@code classic}, and it is unchanged.
     */
    private static final String SUFFIXES = "(?:s|es|ed|er|ers|ing|in|y|ies)?";

    /** Letters separated by a bounded run of punctuation/space, anchored at both word boundaries. */
    private static String buildRegex(String word) {
        StringBuilder sb = new StringBuilder("(?<![a-z0-9])");
        for (int i = 0; i < word.length(); i++) {
            if (i > 0) sb.append("[\\W_]{0,").append(MAX_GAP).append('}');
            sb.append(Pattern.quote(String.valueOf(word.charAt(i)))).append('+');
        }
        return sb.append(SUFFIXES).append("(?![a-z0-9])").toString();
    }

    /**
     * Strips everything that is not a letter or digit, and lowercases. Used for RULE keys only.
     *
     * <p>⚠ The emptiness guard is on the ORIGINAL, not on the result. Leet folding maps punctuation
     * into letters ({@code !} → {@code i}), so {@code "!!!"} would otherwise normalise to the
     * perfectly valid-looking rule {@code "iii"} and silently start censoring the middle of words.
     * Requiring a letter or digit in the input rejects punctuation-only patterns while still
     * accepting {@code H1TLER} and {@code $hit}.
     */
    private static String normalizeWord(String word) {
        if (word == null) return "";
        if (word.chars().noneMatch(Character::isLetterOrDigit)) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : normalize(word).toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Lowercase + leet-speak fold.
     *
     * <p>⚠ MUST stay strictly one-character-in, one-character-out. {@link #check} relies on match
     * indices from the normalised string pointing at the same characters in the original in order
     * to censor them; collapsing or dropping a single character would misalign every star after it.
     */
    private static String normalize(String s) {
        char[] out = s.toLowerCase(Locale.ROOT).toCharArray();
        for (int i = 0; i < out.length; i++) {
            out[i] = switch (out[i]) {
                case '4', '@' -> 'a';
                case '3'      -> 'e';
                case '1', '!', '|' -> 'i';
                case '0'      -> 'o';
                case '5', '$' -> 's';
                case '7'      -> 't';
                default       -> out[i];
            };
        }
        return new String(out);
    }
}
