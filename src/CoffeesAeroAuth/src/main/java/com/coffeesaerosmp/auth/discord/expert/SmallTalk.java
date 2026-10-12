package com.coffeesaerosmp.auth.discord.expert;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises a message that is ONLY small talk ("hi", "thanks!", "how are you", "good bot") and names the knowledge
 * base topic that answers it. Anchored on the whole message on purpose: "hey whats the ip" is a question that
 * happens to start with a greeting, and must reach the rules. Input is already lowercased with punctuation removed.
 */
final class SmallTalk {

    private SmallTalk() {}

    static final Set<String> TOPICS = Set.of("hello", "thanks", "how-are-you", "about-bot", "compliment", "insult");

    private record Rule(Pattern p, String topic) {}

    private static final List<Rule> RULES = List.of(
        new Rule(Pattern.compile("^(hi|hii+|hello|helo|hey|heya|hey there|yo|sup|wassup|whats up|hola|howdy|hai|"
            + "good (morning|evening|afternoon|night)|gm|gn|morning)( (coffeebot|bot|there|guys|all|everyone))?$"), "hello"),
        new Rule(Pattern.compile("^(thanks|thank you|thank u|thankyou|thx|thnx|ty|tysm|tyvm|cheers|appreciate it|"
            + "appreciated|ok thanks|okay thanks|got it thanks)( (so much|a lot|man|bro|coffeebot|bot|mate))?$"), "thanks"),
        new Rule(Pattern.compile("^(how are you|how r u|how are u|hru|how you doing|how ya doing|how are you doing|"
            + "you good|u good|hows it going|how is it going|whats good|you alive|u alive|you there|u there)( (coffeebot|bot))?$"),
            "how-are-you"),
        new Rule(Pattern.compile("^(who are you|who r u|what are you|what can you do|what do you do|help|"
            + "what can i ask|what can i ask you|how do i use you|commands|what commands do you have)( (coffeebot|bot))?$"), "about-bot"),
        new Rule(Pattern.compile("^(good bot|goodbot|nice bot|best bot|great bot|love you|ily|you rock|"
            + "smart bot|nice)( (coffeebot|bot))?$"), "compliment"),
        new Rule(Pattern.compile("^(bad bot|stupid bot|dumb bot|useless|useless bot|shut up|you suck|trash bot)( (coffeebot|bot))?$"),
            "insult")
    );

    /** Topic id for a message that is only small talk, else null. */
    static String topicFor(String bareLower) {
        if (bareLower == null) return null;
        String s = bareLower.trim();
        for (Rule r : RULES) if (r.p().matcher(s).matches()) return r.topic();
        return null;
    }
}
