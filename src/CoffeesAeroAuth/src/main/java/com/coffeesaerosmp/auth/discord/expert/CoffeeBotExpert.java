package com.coffeesaerosmp.auth.discord.expert;

import com.coffeesaerosmp.auth.discord.expert.InferenceEngine.Kind;
import com.coffeesaerosmp.auth.discord.expert.InferenceEngine.Match;
import com.coffeesaerosmp.auth.discord.expert.KnowledgeBase.Topic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CoffeeBot's expert system. Every @mention goes through one pipeline, most certain source first:
 * <ol>
 *   <li><b>Follow-ups</b> on the last answer: a number picks a choice (troubleshooting dialogues are walked this
 *       way), "why" explains how the answer was reached, "more" lists related topics.</li>
 *   <li><b>Live lookups</b> ({@link LiveAnswers}): who's online, when a player was last seen, how to craft an item
 *       (from the server's own recipe data), what an installed mod is, time to the next restart.</li>
 *   <li><b>Taught phrasings</b> ({@link LearnedExamples}): questions staff mapped to a topic.</li>
 *   <li><b>Rules</b> ({@link KnowledgeBase} + {@link InferenceEngine}); a tie asks which one was meant.</li>
 *   <li><b>Wiki search</b> ({@link Bm25Index} over every wiki section): the best-matching section, quoted and
 *       linked, when no rule fires.</li>
 *   <li><b>Honest unknown</b>: says so, offers its three nearest topics as numbered choices, and logs the question
 *       so staff can teach it.</li>
 * </ol>
 * Small talk (hi, thanks, how are you) is ordinary rules in the knowledge base, answered usefully — there is no
 * canned-banter fallback any more.
 *
 * <p>Deliberately NOT a language model: instant, free, cannot be prompt-injected, and can only ever say what the
 * owner wrote or what the server's live data says. The player's own text is never echoed back.
 */
public final class CoffeeBotExpert {

    /** Live values for the answer templates. Called off the server thread; implementations must be thread-safe. */
    public interface FactSource {
        Map<String, String> global();
        /** The asker's own stats, or null when their Discord isn't linked to a game account. */
        Map<String, String> personal(String discordUserId);
    }

    /** Questions answered from live server data. Return null when the question isn't one of yours. */
    public interface LiveAnswers {
        LiveReply answer(String raw, List<String> tokens);
    }

    /** A live answer. {@code why} is what "why" will say about it. */
    public record LiveReply(String title, String body, String why) {}

    public record Reply(String text, Kind kind, String topicId) {}

    /** What the last answer was, for follow-ups. {@code choices} = what a bare number picks. */
    private record Session(long atMs, Topic lastTopic, String why, List<Topic> choices) {}

    static final long SESSION_MS = 5 * 60_000L;
    static final long COOLDOWN_MS = 2_500L;
    static final int MAX_CHARS = 1900;
    /** BM25 score a wiki section needs before it's offered as an answer (tuned on the question tables). */
    static final double WIKI_MIN_SCORE = 4.0;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z.]+)}");

    private final Supplier<KnowledgeBase> kb;
    private final FactSource facts;
    private final String wikiBase;
    private final Bm25Index wiki;
    private final LearnedExamples learned;
    private final LiveAnswers live;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAsk = new ConcurrentHashMap<>();
    private final Deque<String> unanswered = new ArrayDeque<>();
    private volatile java.util.function.Consumer<String> unansweredSink = q -> {};
    /** Test hook: disables the per-user cooldown. */
    volatile boolean cooldownEnabled = true;

    public CoffeeBotExpert(Supplier<KnowledgeBase> kb, FactSource facts, String wikiBase,
                           Bm25Index wiki, LearnedExamples learned, LiveAnswers live) {
        this.kb = kb;
        this.facts = facts;
        this.wikiBase = wikiBase.endsWith("/") ? wikiBase.substring(0, wikiBase.length() - 1) : wikiBase;
        this.wiki = wiki;
        this.learned = learned;
        this.live = live;
    }

    public void setUnansweredSink(java.util.function.Consumer<String> sink) {
        this.unansweredSink = sink == null ? q -> {} : sink;
    }

    /** @return the reply, or null only while the asker is on cooldown (so a burst of pings gets one answer). */
    public Reply answer(String userId, String content) {
        if (content == null) content = "";
        String plain = content.replaceAll("<@[!&]?\\d+>", " ").replaceAll("\\s+", " ").trim();
        String lower = plain.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        String key = userId == null ? "" : userId;

        if (cooldownEnabled) {
            Long last = lastAsk.get(key);
            if (last != null && now - last < COOLDOWN_MS) return null;
        }
        if (lastAsk.size() > 1024) lastAsk.clear();
        lastAsk.put(key, now);

        KnowledgeBase base = kb.get();

        // ── 1. follow-ups ────────────────────────────────────────────────────
        Session s = sessions.get(key);
        if (s != null && now - s.atMs() > SESSION_MS) { sessions.remove(key); s = null; }
        String bare = lower.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (s != null) {
            Matcher num = Pattern.compile("^(?:option |number |no )?([1-9])$").matcher(bare);
            if (s.choices() != null && num.matches()) {
                int i = Integer.parseInt(num.group(1)) - 1;
                if (i < s.choices().size()) {
                    Topic t = s.choices().get(i);
                    return answerTopic(key, t, "You picked option " + (i + 1) + " (*" + t.title() + "*) from my list.");
                }
            }
            if (bare.matches("why|why tho|why though|how do you know|explain|explain that|how did you get that|source|sources")
                && s.why() != null) {
                return new Reply(s.why(), Kind.ANSWER, s.lastTopic() == null ? null : s.lastTopic().id());
            }
            if (bare.matches("more|tell me more|anything else|related|what else") && s.lastTopic() != null) {
                return more(key, s.lastTopic());
            }
        }

        // ── 1b. small talk: only when the WHOLE message is small talk ────────
        String smallTalk = SmallTalk.topicFor(bare);
        if (smallTalk != null && base.get(smallTalk) != null) {
            return answerTopic(key, base.get(smallTalk), "That was small talk, so I answered in kind.");
        }

        List<String> tokens = Normalizer.tokens(plain);
        // A bare mention is a hello.
        if (tokens.isEmpty()) {
            Topic hello = base.get("hello");
            if (hello != null) return answerTopic(key, hello, "You mentioned me without a question, so I introduced myself.");
        }


        // ── 2. live lookups ──────────────────────────────────────────────────
        if (live != null) {
            LiveReply lr = null;
            try { lr = live.answer(plain, tokens); } catch (Exception ignored) { }
            if (lr != null) {
                sessions.put(key, new Session(now, null, lr.why(), null));
                return new Reply(cap("**" + lr.title() + "**\n" + lr.body() + "\n-# live from the server · say `why` to see how I worked that out"),
                    Kind.ANSWER, "live");
            }
        }

        // ── 3. taught phrasings ──────────────────────────────────────────────
        if (learned != null) {
            LearnedExamples.Match lm = learned.best(tokens);
            if (lm != null) {
                Topic t = base.get(lm.example().topicId());
                if (t != null) return answerTopic(key, t, String.format(Locale.ROOT,
                    "Staff taught me that a question like *%s* means **%s**. Yours overlapped it by %.0f%%.",
                    lm.example().text(), t.title(), lm.similarity() * 100));
            }
        }

        // ── 4. rules ─────────────────────────────────────────────────────────
        var result = InferenceEngine.infer(base, tokens);
        if (result.kind() == Kind.ANSWER) {
            Match m = result.top();
            return answerTopic(key, m.topic(), explainRule(m.topic(), m.evidence()));
        }
        if (result.kind() == Kind.CLARIFY && result.matches().stream().anyMatch(Match::fired)) {
            List<Topic> choices = new ArrayList<>();
            for (Match m : result.matches()) choices.add(m.topic());
            return clarify(key, "That could be a few things. Which one do you mean?", choices);
        }

        // ── 5. wiki search ───────────────────────────────────────────────────
        if (wiki != null && wiki.size() > 0) {
            var hits = wiki.search(tokens, 3);
            if (!hits.isEmpty() && hits.get(0).score() >= WIKI_MIN_SCORE && hits.get(0).matched().size() >= Math.min(2, tokens.size())) {
                var h = hits.get(0);
                String why = "No rule of mine matched, so I searched the wiki. The section *" + h.doc().title()
                    + "* scored highest for your words: " + String.join(", ", h.matched().stream().map(x -> "*" + x + "*").toList()) + ".";
                sessions.put(key, new Session(now, null, why, null));
                return new Reply(cap("**From the wiki: " + h.doc().title() + "**\n" + excerpt(h.doc().body(), h.matched())
                    + "\n-# 📖 " + h.doc().url() + " · say `why` to see how I found it"), Kind.ANSWER, "wiki:" + h.doc().id());
            }
        }

        // ── 6. honest unknown, with best guesses ─────────────────────────────
        record(plain);
        List<Topic> guesses = new ArrayList<>();
        for (Match m : result.matches()) if (guesses.size() < 3) guesses.add(m.topic());
        if (guesses.size() < 3) {
            for (Topic t : nearestTopics(base, tokens, 3)) if (guesses.size() < 3 && !guesses.contains(t)) guesses.add(t);
        }
        String head = "I don't have a good answer for that yet. I've noted it so staff can teach me.";
        if (guesses.isEmpty()) {
            sessions.remove(key);
            return new Reply(head + "\nTry the wiki: " + wikiBase + "\nOr ask staff in game with `/ticket <your question>`.",
                Kind.UNKNOWN, null);
        }
        Reply r = clarify(key, head + " Is it one of these?", guesses);
        return new Reply(r.text(), Kind.UNKNOWN, null);
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    private Reply answerTopic(String key, Topic t, String why) {
        String body;
        if (t.personal()) {
            Map<String, String> me = facts.personal(key);
            body = me == null ? (t.unlinked() == null ? "Link your account first." : t.unlinked()) : fill(t.answer(), me);
        } else {
            body = t.answer();
        }
        body = fill(body, Map.of());
        StringBuilder sb = new StringBuilder("**").append(t.title()).append("**\n").append(body);

        // Troubleshooting dialogue: offer the next question as numbered choices.
        List<Topic> next = new ArrayList<>();
        if (!t.options().isEmpty()) {
            KnowledgeBase base = kb.get();
            sb.append("\n\n**").append(t.ask() == null ? "Which fits best?" : t.ask()).append("**");
            int i = 1;
            for (KnowledgeBase.Option o : t.options()) {
                Topic target = base.get(o.topic());
                if (target == null) continue;
                next.add(target);
                sb.append("\n**").append(i++).append(".** ").append(o.label());
            }
            sb.append("\n-# Mention me with the number.");
        }
        String foot = t.see() == null ? "" : "📖 " + wikiBase + "/" + ("index".equals(t.see()) ? "" : t.see()) + " · ";
        if (!SmallTalk.TOPICS.contains(t.id())) sb.append("\n-# ").append(foot).append("say `why` to see how I worked that out");
        sessions.put(key, new Session(System.currentTimeMillis(), t, why, next.isEmpty() ? null : next));
        return new Reply(cap(sb.toString()), Kind.ANSWER, t.id());
    }

    private Reply clarify(String key, String head, List<Topic> choices) {
        StringBuilder sb = new StringBuilder(head).append('\n');
        for (int i = 0; i < choices.size(); i++) sb.append("**").append(i + 1).append(".** ").append(choices.get(i).title()).append('\n');
        sb.append("-# Mention me with the number.");
        sessions.put(key, new Session(System.currentTimeMillis(), null,
            "I wasn't sure which answer you wanted, so I asked instead of guessing.", choices));
        return new Reply(cap(sb.toString()), Kind.CLARIFY, null);
    }

    private Reply more(String key, Topic t) {
        KnowledgeBase base = kb.get();
        List<Topic> rel = new ArrayList<>();
        for (String id : t.related()) { Topic r = base.get(id); if (r != null) rel.add(r); }
        if (rel.isEmpty()) return new Reply("That's everything I have on that. The wiki has more: " + wikiBase, Kind.ANSWER, t.id());
        return clarify(key, "Related to *" + t.title() + "*:", rel);
    }

    private static String explainRule(Topic t, List<String> evidence) {
        StringBuilder sb = new StringBuilder("I used the rule **").append(t.title()).append("**. It needs ");
        List<List<String>> groups = t.rawWhen();
        for (int g = 0; g < groups.size(); g++) {
            if (g > 0) sb.append(" **and** ");
            List<String> show = groups.get(g).subList(0, Math.min(4, groups.get(g).size()));
            sb.append("one of: ").append(String.join(" / ", show.stream().map(x -> "*" + x + "*").toList()));
            if (groups.get(g).size() > 4) sb.append(" / …");
        }
        sb.append(".\nYour message matched: ")
          .append(evidence.isEmpty() ? "nothing specific" : String.join(", ", evidence.stream().map(x -> "*" + x + "*").toList()))
          .append(". If I got it wrong, rephrase with the exact error or the command you mean.");
        return sb.toString();
    }

    /** The topics whose own words best match the question (for "is it one of these?"). */
    private List<Topic> nearestTopics(KnowledgeBase base, List<String> tokens, int k) {
        List<Bm25Index.Doc> docs = new ArrayList<>();
        Map<String, Topic> byId = new LinkedHashMap<>();
        for (Topic t : base.topics()) {
            if (t.id().equals("hello")) continue;
            StringBuilder words = new StringBuilder(t.title());
            for (var g : t.rawWhen()) for (String p : g) words.append(' ').append(p);
            docs.add(new Bm25Index.Doc(t.id(), t.title(), words.toString(), ""));
            byId.put(t.id(), t);
        }
        List<Topic> out = new ArrayList<>();
        for (var h : new Bm25Index(docs).search(tokens, k)) out.add(byId.get(h.doc().id()));
        return out;
    }

    /** The part of a wiki section around the matched words, about 500 characters, cut at sentence ends. */
    static String excerpt(String body, List<String> matched) {
        String text = body.replaceAll("\\n{2,}", "\n").trim();
        if (text.length() <= 520) return text;
        int best = 0;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String m : matched) {
            int i = lower.indexOf(m);
            if (i >= 0) { best = i; break; }
        }
        int start = Math.max(0, lower.lastIndexOf('\n', Math.max(0, best - 1)));
        if (best - start > 300) start = Math.max(0, best - 200);
        int end = Math.min(text.length(), start + 520);
        int stop = text.lastIndexOf(". ", end);
        if (stop > start + 200) end = stop + 1;
        return (start > 0 ? "… " : "") + text.substring(start, end).trim() + (end < text.length() ? " …" : "");
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
        String q = question.replaceAll("\\s+", " ").trim();
        if (q.length() > 200) q = q.substring(0, 200);
        if (q.isEmpty()) return;
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
