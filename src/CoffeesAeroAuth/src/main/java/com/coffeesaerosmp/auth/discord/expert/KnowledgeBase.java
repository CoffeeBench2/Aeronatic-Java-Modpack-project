package com.coffeesaerosmp.auth.discord.expert;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CoffeeBot's knowledge: a list of production rules ("topics"). A topic fires when EVERY one of its {@code when}
 * groups is satisfied by at least one phrase in the question — e.g. {@code [["join","connect"],["hash mismatch"]]}
 * means "(join OR connect) AND hash mismatch". That AND-of-ORs is what lets the bot tell "can't join, hash
 * mismatch" from "can't join, timed out" instead of keyword-matching "join" for both.
 *
 * <p>Loaded from the bundled {@code coffeebot/kb.json}; an optional {@code config/coffeebot-kb.json} on the server
 * adds topics or replaces bundled ones by id, so the owner can teach the bot without a new jar
 * ({@code /aerobot reload}). Phrases are normalised with {@link Normalizer} at load time.
 */
public final class KnowledgeBase {

    /** One rule. {@code when} and {@code boost} hold already-normalised token phrases. */
    public record Topic(String id, String title, List<List<List<String>>> when, List<List<String>> boost,
                        List<List<String>> not, String answer, String unlinked, String see, List<String> related,
                        int priority, boolean personal, List<List<String>> rawWhen, String ask, List<Option> options) {}

    /**
     * A follow-up choice offered after the answer — the troubleshooting dialogue. The player replies with the
     * number and gets {@code topic}'s answer, which may ask its own follow-up: a decision tree, walked one
     * question at a time.
     */
    public record Option(String label, String topic) {}

    private final List<Topic> topics;
    private final Map<String, Topic> byId;

    private KnowledgeBase(List<Topic> topics) {
        this.topics = Collections.unmodifiableList(topics);
        Map<String, Topic> m = new LinkedHashMap<>();
        for (Topic t : topics) m.put(t.id(), t);
        this.byId = Collections.unmodifiableMap(m);
    }

    public List<Topic> topics() { return topics; }
    public Topic get(String id) { return byId.get(id); }
    public int size() { return topics.size(); }

    public static final String RESOURCE = "/assets/coffees_aero_auth/coffeebot/kb.json";

    /** Bundled KB, then the override file if present (same id = replace, new id = add). */
    public static KnowledgeBase load(Path override) throws Exception {
        Map<String, Topic> merged = new LinkedHashMap<>();
        try (InputStream in = KnowledgeBase.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing " + RESOURCE);
            for (Topic t : parse(new InputStreamReader(in, StandardCharsets.UTF_8))) merged.put(t.id(), t);
        }
        if (override != null && Files.isRegularFile(override)) {
            try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                for (Topic t : parse(r)) merged.put(t.id(), t);
            }
        }
        return new KnowledgeBase(new ArrayList<>(merged.values()));
    }

    /** For tests. */
    static KnowledgeBase of(Reader json) {
        return new KnowledgeBase(parse(json));
    }

    static List<Topic> parse(Reader json) {
        JsonObject root = JsonParser.parseReader(json).getAsJsonObject();
        List<Topic> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("topics")) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            List<List<List<String>>> when = new ArrayList<>();
            List<List<String>> rawWhen = new ArrayList<>();
            for (JsonElement g : o.getAsJsonArray("when")) {
                List<List<String>> group = new ArrayList<>();
                List<String> rawGroup = new ArrayList<>();
                for (JsonElement p : g.getAsJsonArray()) {
                    List<String> toks = Normalizer.tokens(p.getAsString());
                    if (toks.isEmpty()) {
                        errors.add(id + ": phrase '" + p.getAsString() + "' normalises to nothing (all stop words?)");
                        continue;
                    }
                    String collapse = collapsed(p.getAsString(), toks);
                    if (collapse != null) { errors.add(id + ": " + collapse); continue; }
                    group.add(toks);
                    rawGroup.add(p.getAsString());
                }
                if (group.isEmpty()) { errors.add(id + ": empty when-group"); continue; }
                when.add(group);
                rawWhen.add(rawGroup);
            }
            if (when.isEmpty()) { errors.add(id + ": no when-groups"); continue; }
            out.add(new Topic(id,
                str(o, "title", id),
                when,
                phrases(o.getAsJsonArray("boost"), id, errors),
                phrases(o.getAsJsonArray("not"), id, errors),
                str(o, "answer", ""),
                str(o, "unlinked", null),
                str(o, "see", null),
                strings(o.getAsJsonArray("related")),
                o.has("priority") ? o.get("priority").getAsInt() : 0,
                o.has("personal") && o.get("personal").getAsBoolean(),
                rawWhen,
                str(o, "ask", null),
                options(o.getAsJsonArray("options"))));
        }
        // All problems at once: a hand-edited override should get one complete list, not one error per reload.
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("; ", errors));
        return out;
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static List<List<String>> phrases(JsonArray a, String id, List<String> errors) {
        List<List<String>> out = new ArrayList<>();
        if (a == null) return out;
        for (JsonElement p : a) {
            List<String> t = Normalizer.tokens(p.getAsString());
            String collapse = collapsed(p.getAsString(), t);
            if (collapse != null) { errors.add(id + ": " + collapse); continue; }
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * A multi-word phrase that normalises to ONE word has silently become a generic keyword: "server up" turned
     * into "server" and fired the status rule on any sentence with "server" in it; a veto phrase "back up" turned
     * into "back" and vetoed the whole /back rule. Caught at load time instead (found by the question table test).
     */
    static String collapsed(String raw, List<String> toks) {
        int words = raw.trim().split(" +").length;
        if (words >= 2 && toks.size() == 1) {
            return "phrase '" + raw + "' collapses to the single word '" + toks.get(0)
                + "' (stop words removed); reword it or drop it";
        }
        return null;
    }

    private static List<Option> options(JsonArray a) {
        List<Option> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Option(o.get("label").getAsString(), o.get("topic").getAsString()));
        }
        return out;
    }

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }
}
