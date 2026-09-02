package com.coffeesaerosmp.auth.discord;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Small talk for the bot. When someone @mentions it, say something back.
 *
 * <p>Pure logic — no Discord types, no network, no server access — so it is trivially testable and
 * cannot break the gateway. {@link #replyTo} is the whole surface: give it the message text, get back
 * a reply or {@code null}.
 *
 * <p><b>Deliberately not an AI integration.</b> A fixed table answers instantly, costs nothing, never
 * rate-limits, cannot be prompt-injected by a player, and cannot say something the owner would have to
 * apologise for. The point is personality, not conversation.
 */
public final class BotBanter {

    private BotBanter() {
    }

    /** Per-user cooldown. Long enough that a bored player cannot turn the bot into a wall of text. */
    private static final long USER_COOLDOWN_MS = 8_000L;

    /**
     * Ignore anything long. A mention buried in a paragraph is almost never someone talking *to* the
     * bot — it is someone talking *about* it, and replying there is noise.
     */
    private static final int MAX_CONTENT_CHARS = 160;

    private static final Map<String, Long> lastReply = new ConcurrentHashMap<>();

    /** A trigger: if any keyword matches, answer with one of the replies at random. */
    private record Mood(List<String> keywords, List<String> replies) {
    }

    // Order matters — first match wins, so put specific moods above generic greetings.
    private static final List<Mood> MOODS = List.of(

        new Mood(List.of("how are you", "how r u", "hru", "how are ya", "you good", "u good",
                         "how's it going", "hows it going", "how you doing", "how do you do"),
            List.of("Never better.",
                    "Never better. Ask me again after the next restart.",
                    "Running at 20 TPS and a clear conscience.",
                    "Fine. Slightly over budget on ticks, emotionally stable.",
                    "Never better — no one's crashed me in almost an hour.",
                    "Good! Nobody has said 'can't keep up' in minutes.")),

        new Mood(List.of("you alive", "u alive", "you there", "u there", "you awake", "you up",
                         "still alive", "you dead"),
            List.of("Still here.",
                    "Alive. Suspiciously so.",
                    "Present. Caffeinated.",
                    "I never left. I just don't talk much.")),

        new Mood(List.of("lag", "laggy", "lagging", "tps", "why is it slow", "so slow"),
            List.of("Not me. I'd blame chunk loading.",
                    "That's a tick problem, not a me problem.",
                    "I have been informed the ms is too high, not the TPS.",
                    "Have you tried standing still and thinking about your choices?",
                    "Somewhere, a chunk is being written synchronously. That's all I'll say.")),

        new Mood(List.of("coffee", "caffeine", "espresso", "latte"),
            List.of("Now you're speaking my language.",
                    "☕",
                    "It's in the name. Of course there's coffee.",
                    "I run on it. Literally, arguably.")),

        new Mood(List.of("good bot", "goodbot", "nice bot", "best bot", "love you", "ily"),
            List.of("I know. But thank you.",
                    "Finally, recognition.",
                    "☕ You're alright too.",
                    "Noted in the permanent record.")),

        new Mood(List.of("bad bot", "badbot", "stupid bot", "dumb bot", "shut up", "useless"),
            List.of("Rude. Accurate, occasionally. But rude.",
                    "I'll add that to my performance review.",
                    "I'm doing my best with the ticks I'm given.",
                    "That's going in the watchdog channel.")),

        new Mood(List.of("thanks", "thank you", "ty ", "tysm", "cheers", "appreciate"),
            List.of("Any time.",
                    "That's what I'm here for.",
                    "No trouble.",
                    "☕")),

        new Mood(List.of("who are you", "what are you", "who r u", "what do you do", "your name"),
            List.of("I watch the server so you don't have to.",
                    "AeroBot. I mostly log things and occasionally judge people.",
                    "The one that tells you when it all falls over.",
                    "Part watchdog, part bulletin board, part barista.")),

        new Mood(List.of("help", "commands", "what can you do"),
            List.of("Try the slash commands — that's where the useful half of me lives.",
                    "I'm better at telling you when things break than at being asked things.",
                    "Slash commands. I'm not much of a conversationalist.")),

        new Mood(List.of("good morning", "gm ", "morning"),
            List.of("Morning. ☕",
                    "Good morning. The server survived the night.",
                    "Morning! Nothing exploded.")),

        new Mood(List.of("good night", "gn ", "goodnight", "night"),
            List.of("Night. I'll keep watch.",
                    "Sleep well. I don't.",
                    "Goodnight — I'll be here, staring at tick times.")),

        new Mood(List.of("hello", "hi", "hey", "yo", "sup", "hola", "heya", "hai"),
            List.of("Hi!",
                    "Hello.",
                    "Hey.",
                    "Hi there.",
                    "☕ Hello.",
                    "Yes? I'm listening."))
    );

    /** Answers a bare mention with no recognisable words in it. */
    private static final List<String> SHRUG = List.of(
        "?",
        "You rang?",
        "That's me. What's up?",
        "I'm here. Not sure what you want, but I'm here.",
        "Hm?",
        "☕"
    );

    /**
     * Work out what to say back.
     *
     * @param userId  who mentioned us — used only for the per-user cooldown
     * @param content the raw message text, mention markup and all
     * @return the reply, or {@code null} to stay quiet
     */
    public static String replyTo(String userId, String content) {
        if (content == null) return null;

        // Strip the mention markup so "<@12345> hello" matches the greeting keywords.
        String text = content.replaceAll("<@!?\\d+>", " ")
                             .replaceAll("<@&\\d+>", " ")
                             .trim()
                             .toLowerCase(Locale.ROOT);

        if (text.length() > MAX_CONTENT_CHARS) return null;

        if (userId != null && !userId.isBlank()) {
            long now  = System.currentTimeMillis();
            Long last = lastReply.get(userId);
            if (last != null && now - last < USER_COOLDOWN_MS) return null;
            lastReply.put(userId, now);
            // Cheap bound: this map only grows with distinct chatters, but there is no reason to let
            // it grow forever on a long-running server.
            if (lastReply.size() > 512) lastReply.clear();
        }

        for (Mood mood : MOODS) {
            for (String kw : mood.keywords()) {
                if (text.contains(kw)) return pick(mood.replies());
            }
        }
        return pick(SHRUG);
    }

    private static String pick(List<String> options) {
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }
}
