package com.coffeesaerosmp.auth.discord.expert;

import com.coffeesaerosmp.auth.discord.expert.KnowledgeBase.Topic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Matches a question against every rule and decides: answer, ask which one they meant, or admit it doesn't know.
 *
 * <h2>Scoring</h2>
 * A rule FIRES only when every {@code when} group matches. Each matched group scores {@code 2 + 2·L} where L is
 * the token length of the best phrase it matched — so "hash mismatch" (2 tokens) beats "join" (1), and a
 * specific rule outranks a generic one that happens to fire on the same words. {@code boost} phrases add
 * {@code 1.5·L}; {@code priority} adds its value; any {@code not} phrase vetoes the rule.
 *
 * <h2>Decision</h2>
 * <ul>
 *   <li>One clear winner → {@link Kind#ANSWER}.</li>
 *   <li>Runner-up within {@value #AMBIGUITY} of the winner (and not one of its {@code related}) → {@link Kind#CLARIFY}
 *       with up to three choices — an expert asks rather than guesses.</li>
 *   <li>No rule fires, but some multi-group rules half-match → CLARIFY with those ("did you mean").</li>
 *   <li>Nothing → {@link Kind#UNKNOWN}.</li>
 * </ul>
 * Every result carries the phrases that matched, which is the bot's answer to "why?".
 */
public final class InferenceEngine {

    public enum Kind { ANSWER, CLARIFY, UNKNOWN }

    /** A rule's evaluation: score, whether it fired, and which phrases did it. */
    public record Match(Topic topic, double score, boolean fired, double fraction, List<String> evidence) {}

    public record Result(Kind kind, List<Match> matches) {
        public Match top() { return matches.isEmpty() ? null : matches.get(0); }
    }

    /** Runner-up at or above this share of the winner's score counts as a tie. */
    static final double AMBIGUITY = 0.85;

    private InferenceEngine() {}

    public static Result infer(KnowledgeBase kb, String question) {
        return infer(kb, Normalizer.tokens(question));
    }

    public static Result infer(KnowledgeBase kb, List<String> q) {
        List<Match> fired = new ArrayList<>(), partial = new ArrayList<>();
        if (q.isEmpty()) return new Result(Kind.UNKNOWN, List.of());
        for (Topic t : kb.topics()) {
            Match m = evaluate(t, q);
            if (m == null) continue;
            if (m.fired()) fired.add(m);
            else if (m.fraction() >= 0.5 && t.when().size() >= 2) partial.add(m);
        }
        Comparator<Match> byScore = Comparator.comparingDouble(Match::score).reversed();
        fired.sort(byScore);
        partial.sort(byScore);

        if (fired.isEmpty()) {
            return partial.isEmpty()
                ? new Result(Kind.UNKNOWN, List.of())
                : new Result(Kind.CLARIFY, partial.subList(0, Math.min(3, partial.size())));
        }
        Match best = fired.get(0);
        // A general rule that lists a specific one as related, and ties with it, yields to the specific one:
        // "can't join … hash mismatch" is a hash-mismatch question, not a generic can't-join one.
        for (int i = 1; i < fired.size(); i++) {
            Match m = fired.get(i);
            if (m.score() < best.score() * AMBIGUITY) break;
            if (best.topic().related().contains(m.topic().id())) { best = m; break; }
        }
        final Match winner = best;
        fired.remove(winner);
        fired.add(0, winner);
        List<Match> close = new ArrayList<>();
        close.add(best);
        for (int i = 1; i < fired.size() && close.size() < 3; i++) {
            Match m = fired.get(i);
            if (m.score() < best.score() * AMBIGUITY) break;
            if (best.topic().related().contains(m.topic().id())) continue;   // the answer already points there
            if (m.topic().related().contains(best.topic().id())) continue;   // a general rule the winner specialises
            close.add(m);
        }
        return close.size() == 1 ? new Result(Kind.ANSWER, List.of(best)) : new Result(Kind.CLARIFY, close);
    }

    /** Null when vetoed or nothing matched at all. */
    static Match evaluate(Topic t, List<String> q) {
        for (List<String> n : t.not()) if (find(q, n)) return null;
        double score = 0;
        int matchedGroups = 0;
        List<String> evidence = new ArrayList<>();
        for (int g = 0; g < t.when().size(); g++) {
            int bestLen = 0;
            String bestRaw = null;
            List<List<String>> group = t.when().get(g);
            for (int p = 0; p < group.size(); p++) {
                List<String> phrase = group.get(p);
                if (phrase.size() > bestLen && find(q, phrase)) {
                    bestLen = phrase.size();
                    bestRaw = t.rawWhen().get(g).get(p);
                }
            }
            if (bestLen > 0) {
                matchedGroups++;
                score += 2 + 2.0 * bestLen;
                evidence.add(bestRaw);
            }
        }
        if (matchedGroups == 0) return null;
        for (List<String> b : t.boost()) if (find(q, b)) score += 1.5 * b.size();
        score += t.priority();
        double fraction = matchedGroups / (double) t.when().size();
        return new Match(t, score, matchedGroups == t.when().size(), fraction, evidence);
    }

    /**
     * Phrase match in order, allowing at most ONE extra word between consecutive phrase words ("change my name"
     * matches "change name", "name my ship" matches "name ship"), with one-typo tolerance per word of five letters
     * or more.
     */
    static boolean find(List<String> q, List<String> phrase) {
        for (int i = 0; i < q.size(); i++) {
            if (!Normalizer.nearlyEqual(q.get(i), phrase.get(0))) continue;
            if (rest(q, i + 1, phrase, 1)) return true;
        }
        return false;
    }

    private static boolean rest(List<String> q, int from, List<String> phrase, int j) {
        if (j == phrase.size()) return true;
        for (int k = from; k <= from + 1 && k < q.size(); k++) {
            if (Normalizer.nearlyEqual(q.get(k), phrase.get(j)) && rest(q, k + 1, phrase, j + 1)) return true;
        }
        return false;
    }
}
