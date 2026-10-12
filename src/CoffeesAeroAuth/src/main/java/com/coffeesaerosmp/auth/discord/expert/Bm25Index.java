package com.coffeesaerosmp.auth.discord.expert;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Okapi BM25 over short documents (wiki sections). The fallback when no rule fires: instead of "I don't know",
 * the bot quotes the wiki section that best matches the question. Pure; documents are tokenised with the same
 * {@link Normalizer} as questions, so synonyms and stemming line up.
 */
public final class Bm25Index {

    /** A searchable section. {@code url} is a link to it, {@code body} the plain text shown in the answer. */
    public record Doc(String id, String title, String body, String url) {}

    public record Hit(Doc doc, double score, List<String> matched) {}

    static final double K1 = 1.2, B = 0.75;

    private final List<Doc> docs = new ArrayList<>();
    private final List<Map<String, Integer>> tf = new ArrayList<>();
    private final List<Integer> len = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private double avgLen;

    public Bm25Index(List<Doc> input) {
        long total = 0;
        for (Doc d : input) {
            // The title counts twice: a section called "Voting" is about voting even if the body says it once.
            List<String> toks = new ArrayList<>(Normalizer.tokens(d.title()));
            toks.addAll(Normalizer.tokens(d.title()));
            toks.addAll(Normalizer.tokens(d.body()));
            Map<String, Integer> f = new HashMap<>();
            for (String t : toks) f.merge(t, 1, Integer::sum);
            for (String t : f.keySet()) df.merge(t, 1, Integer::sum);
            docs.add(d);
            tf.add(f);
            len.add(toks.size());
            total += toks.size();
        }
        avgLen = docs.isEmpty() ? 1 : (double) total / docs.size();
    }

    public int size() { return docs.size(); }

    public List<Hit> search(List<String> query, int k) {
        List<Hit> hits = new ArrayList<>();
        int n = docs.size();
        if (n == 0 || query.isEmpty()) return hits;
        List<String> uniq = query.stream().distinct().toList();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> f = tf.get(i);
            double score = 0;
            List<String> matched = new ArrayList<>();
            for (String q : uniq) {
                Integer c = f.get(q);
                if (c == null) continue;
                int dfq = df.getOrDefault(q, 0);
                double idf = Math.log(1 + (n - dfq + 0.5) / (dfq + 0.5));
                score += idf * (c * (K1 + 1)) / (c + K1 * (1 - B + B * len.get(i) / avgLen));
                matched.add(q);
            }
            if (score > 0) hits.add(new Hit(docs.get(i), score, matched));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > k ? hits.subList(0, k) : hits;
    }

    /** How many query words are known to the corpus at all — a question of unknown words has no good match. */
    public long knownWords(List<String> query) {
        return query.stream().distinct().filter(df::containsKey).count();
    }
}
