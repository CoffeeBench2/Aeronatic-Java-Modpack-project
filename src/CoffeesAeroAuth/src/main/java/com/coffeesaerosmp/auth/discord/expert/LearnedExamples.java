package com.coffeesaerosmp.auth.discord.expert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phrasings staff have taught the bot: "this question means that topic". Kept in its own file
 * ({@code config/coffeebot-learned.json}, written only by {@code /aerobot teach}, never hand-edited) so a teach
 * can never corrupt the hand-written override.
 *
 * <p>Matching is word-set overlap (Jaccard) on normalised tokens, so "how do i change my skin" taught once also
 * catches "change skin how?" — but a single shared word can't trigger it.
 */
public final class LearnedExamples {

    public record Example(String topicId, String text, Set<String> tokens) {}

    public record Match(Example example, double similarity) {}

    /** Overlap needed to treat a question as a taught one. */
    static final double THRESHOLD = 0.7;

    private final List<Example> examples = new ArrayList<>();
    private final Path file;

    public LearnedExamples(Path file) { this.file = file; }

    public synchronized void load() throws Exception {
        examples.clear();
        if (file == null || !Files.isRegularFile(file)) return;
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        for (var e : root.entrySet()) {
            for (JsonElement q : e.getValue().getAsJsonArray()) add0(e.getKey(), q.getAsString());
        }
    }

    /** Add and persist. Returns false if it adds nothing (empty after normalising, or already known). */
    public synchronized boolean teach(String topicId, String question) throws Exception {
        if (!add0(topicId, question)) return false;
        save();
        return true;
    }

    private boolean add0(String topicId, String question) {
        Set<String> toks = new HashSet<>(Normalizer.tokens(question));
        if (toks.isEmpty()) return false;
        for (Example e : examples) if (e.topicId().equals(topicId) && e.tokens().equals(toks)) return false;
        examples.add(new Example(topicId, question.trim(), toks));
        return true;
    }

    private void save() throws Exception {
        if (file == null) return;
        Map<String, JsonArray> by = new LinkedHashMap<>();
        for (Example e : examples) by.computeIfAbsent(e.topicId(), k -> new JsonArray()).add(e.text());
        JsonObject root = new JsonObject();
        by.forEach(root::add);
        Gson g = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, g.toJson(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public synchronized Match best(List<String> question) {
        Set<String> q = new HashSet<>(question);
        if (q.isEmpty()) return null;
        Match best = null;
        for (Example e : examples) {
            Set<String> inter = new HashSet<>(q);
            inter.retainAll(e.tokens());
            if (inter.isEmpty()) continue;
            Set<String> union = new HashSet<>(q);
            union.addAll(e.tokens());
            double j = inter.size() / (double) union.size();
            if (j >= THRESHOLD && (best == null || j > best.similarity())) best = new Match(e, j);
        }
        return best;
    }

    public synchronized int size() { return examples.size(); }
}
