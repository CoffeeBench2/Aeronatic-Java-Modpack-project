package com.coffeesaerosmp.auth.discord.expert;

import com.coffeesaerosmp.auth.discord.expert.InferenceEngine.Kind;
import com.coffeesaerosmp.auth.discord.expert.InferenceEngine.Match;
import com.coffeesaerosmp.auth.discord.expert.KnowledgeBase.Topic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CoffeeBot's expert system: answers a player who @mentions the bot with a question about the server.
 *
 * <p>Classic expert-system parts, all deterministic:
 * <ul>
 *   <li><b>Knowledge base</b> — {@link KnowledgeBase}, production rules written from the wiki and the live config.</li>
 *   <li><b>Inference engine</b> — {@link InferenceEngine}: fires rules, ranks them, asks when two tie.</li>
 *   <li><b>Working memory</b> — live facts ({@link FactSource}: status, TPS, player count, the asker's own stats)
 *       substituted into the answer at the moment it's given.</li>
 *   <li><b>Explanation facility</b> — say "why" and it shows the rule and the words that fired it.</li>
 *   <li><b>Knowledge acquisition</b> — questions it couldn't answer are kept for staff ({@link #unanswered()}),
 *       who add a topic to {@code config/coffeebot-kb.json} and {@code /aerobot reload}.</li>
 * </ul>
 *
 * <p>Deliberately NOT a language model, for the same reasons as {@code BotBanter}: instant, free, cannot be
 * prompt-injected, and can only ever say what the owner wrote. The player's own text is never echoed back.
 */
public final class CoffeeBotExpert {

    /** Live values for the answer templates. Called off the server thread; implementations must be thread-safe. */
    public interface FactSource {
        Map<String, String> global();
        /** The asker's own stats, or null when their Discord isn't linked to a game account. */
        Map<String, String> personal(String discordUserId);
    }

    public record Reply(String text, Kind kind, String topicId) {}

    private record Session(long atMs, Topic lastTopic, List<String> evidence, List<Topic> choices) {}

    static final long SESSION_MS = 3 * 60_000L;
    static final long COOLDOWN_MS = 3_000L;
    static final int MAX_CHARS = 1900;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z.]+)}");

    private final Supplier<KnowledgeBase> kb;
    private final FactSource facts;
    private final String wikiBase;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAsk = new ConcurrentHashMap<>();
    private final Deque<String> unanswered = new ArrayDeque<>();
    private volatile java.util.function.Consumer<String> unansweredSink = q -> {};
    /** Test hook: disables the per-user cooldown. */
    volatile boolean cooldownEnabled = true;

    public CoffeeBotExpert(Supplier<KnowledgeBase> kb, FactSource facts, String wikiBase) {
        this.kb = kb;
        this.facts = facts;
        this.wikiBase = wikiBase.endsWith("/") ? wikiBase.substring(0, wikiBase.length() - 1) : wikiBase;
    }

    public void setUnansweredSink(java.util.function.Consumer<String> sink) {
        this.unansweredSink = sink == null ? q -> {} : sink;
    }

    /**
     * @return the reply, or {@code null} to let small talk ({@code BotBanter}) handle it — a bare "hi" or "thanks"
     *         is not a question and shouldn't get "I don't know that one".
     */
    public Reply answer(String userId, String content) {
        if (content == null) return null;
        String plain = content.replaceAll("<@[!&]?\\d+>", " ").trim().toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        String key = userId == null ? "" : userId;

        if (cooldownEnabled) {
            Long last = lastAsk.get(key);
            if (last != null && now - last < COOLDOWN_MS) return null;
        }
        if (lastAsk.size() > 1024) lastAsk.clear();
        lastAsk.put(key, now);

        Session s = sessions.get(key);
        if (s != null && now - s.atMs() > SESSION_MS) { sessions.remove(key); s = null; }

        // ── follow-ups on the previous answer ────────────────────────────────
        if (s != null) {
            String bare = plain.replaceAll("[^a-z0-9 ]", " ").trim();
            if (s.choices() != null && bare.matches("[1-9]")) {
                int i = Integer.parseInt(bare) - 1;
                if (i < s.choices().size()) return answerTopic(key, s.choices().get(i), List.of("you picked option " + (i + 1)));
            }
            if (bare.matches("(why|why tho|why though|how do you know|explain|explain that|how did you get that|source)")
                && s.lastTopic() != null) {
                return new Reply(explain(s), Kind.ANSWER, s.lastTopic().id());
            }
            if (bare.matches("(more|tell me more|anything else|related|what else)") && s.lastTopic() != null) {
                return new Reply(more(key, s.lastTopic()), Kind.CLARIFY, s.lastTopic().id());
            }
        }

        KnowledgeBase base = kb.get();
        List<String> tokens = Normalizer.tokens(content);
        var result = InferenceEngine.infer(base, tokens);
        switch (result.kind()) {
            case ANSWER -> {
                Match m = result.top();
                return answerTopic(key, m.topic(), m.evidence());
            }
            case CLARIFY -> {
                List<Topic> choices = new ArrayList<>();
                for (Match m : result.matches()) choices.add(m.topic());
                sessions.put(key, new Session(now, null, List.of(), choices));
                StringBuilder sb = new StringBuilder("That could be a few things. Which one do you mean?\n");
                for (int i = 0; i < choices.size(); i++) {
                    sb.append("**").append(i + 1).append(".** ").append(choices.get(i).title()).append('\n');
                }
                sb.append("-# Mention me with the number.");
                return new Reply(sb.toString(), Kind.CLARIFY, null);
            }
            default -> {
                // Small talk is not a question: hand it to BotBanter.
                boolean looksLikeQuestion = plain.contains("?") || tokens.size() >= 3;
                if (!looksLikeQuestion) return null;
                record(content);
                return new Reply("I don't know that one yet. I've noted it so staff can teach me.\n"
                    + "Try the wiki: " + wikiBase + "\nOr ask staff in game with `/ticket <your question>`.",
                    Kind.UNKNOWN, null);
            }
        }
    }

    private Reply answerTopic(String key, Topic t, List<String> evidence) {
        String body;
        if (t.personal()) {
            Map<String, String> me = facts.personal(key);
            body = me == null ? (t.unlinked() == null ? "Link your account first." : t.unlinked()) : fill(t.answer(), me);
        } else {
            body = t.answer();
        }
        body = fill(body, Map.of());
        StringBuilder sb = new StringBuilder("**").append(t.title()).append("**\n").append(body);
        String foot = t.see() == null ? "" : "📖 " + wikiBase + "/" + ("index".equals(t.see()) ? "" : t.see()) + " · ";
        sb.append("\n-# ").append(foot).append("say `why` to see how I worked that out");
        sessions.put(key, new Session(System.currentTimeMillis(), t, evidence, null));
        return new Reply(cap(sb.toString()), Kind.ANSWER, t.id());
    }

    private String explain(Session s) {
        Topic t = s.lastTopic();
        StringBuilder sb = new StringBuilder("I used the rule **").append(t.title()).append("**. It needs ");
        List<List<String>> groups = t.rawWhen();
        for (int g = 0; g < groups.size(); g++) {
            if (g > 0) sb.append(" **and** ");
            List<String> show = groups.get(g).subList(0, Math.min(4, groups.get(g).size()));
            sb.append("one of: ").append(String.join(" / ", show.stream().map(x -> "*" + x + "*").toList()));
            if (groups.get(g).size() > 4) sb.append(" / …");
        }
        sb.append(".\nYour message matched: ");
        sb.append(s.evidence().isEmpty() ? "your earlier choice"
            : String.join(", ", s.evidence().stream().map(x -> "*" + x + "*").toList()));
        sb.append(". If I got it wrong, rephrase with the exact error or the command you mean.");
        return sb.toString();
    }

    private String more(String key, Topic t) {
        KnowledgeBase base = kb.get();
        List<Topic> rel = new ArrayList<>();
        for (String id : t.related()) { Topic r = base.get(id); if (r != null) rel.add(r); }
        if (rel.isEmpty()) return "That's everything I have on that. The wiki has more: " + wikiBase;
        sessions.put(key, new Session(System.currentTimeMillis(), t, List.of(), rel));
        StringBuilder sb = new StringBuilder("Related:\n");
        for (int i = 0; i < rel.size(); i++) sb.append("**").append(i + 1).append(".** ").append(rel.get(i).title()).append('\n');
        return sb.append("-# Mention me with the number.").toString();
    }

    /** Fill {placeholders}: the given map first, then the live global facts. Unknown ones become "?". */
    String fill(String template, Map<String, String> first) {
        if (template.indexOf('{') < 0) return template;
        Map<String, String> global = facts.global();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String k = m.group(1);
            String v = first.get(k);
            if (v == null) v = global.get(k);
            if (v == null && k.equals("wiki")) v = wikiBase;
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "?" : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void record(String question) {
        String q = question.replaceAll("<@[!&]?\\d+>", "").replaceAll("\\s+", " ").trim();
        if (q.length() > 200) q = q.substring(0, 200);
        synchronized (unanswered) {
            unanswered.addFirst(q);
            while (unanswered.size() > 50) unanswered.removeLast();
        }
        try { unansweredSink.accept(q); } catch (Exception ignored) { }
    }

    public List<String> unanswered() {
        synchronized (unanswered) { return new ArrayList<>(unanswered); }
    }

    private static String cap(String s) {
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS - 1) + "…";
    }
}
