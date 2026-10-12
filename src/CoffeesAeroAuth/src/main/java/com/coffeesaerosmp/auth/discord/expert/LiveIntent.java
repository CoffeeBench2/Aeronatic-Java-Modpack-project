package com.coffeesaerosmp.auth.discord.expert;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the questions that need LIVE server data and pulls out their subject (a player name, an item, a mod).
 * Pure, so every phrasing is unit-tested; {@link ServerLiveAnswers} does the lookups.
 *
 * <p>Anchored patterns only: "is Steve online" is a lookup, "is the server online" is not (the excluded-subject
 * list catches that), and a sentence that merely contains "craft" is not a recipe question.
 */
public final class LiveIntent {

    public enum Kind { WHO_ONLINE, PLAYER_SEEN, NEXT_RESTART, RECIPE, MOD_INFO }

    public record Intent(Kind kind, String subject) {}

    private LiveIntent() {}

    /** Words that look like a name in "is X online" but are not a player. */
    static final Set<String> NOT_A_PLAYER = Set.of("server", "the", "smp", "it", "anyone", "anybody", "someone",
        "somebody", "everyone", "he", "she", "they", "staff", "admin", "admins", "mods", "owner", "this", "that",
        "lobby", "nether", "end", "discord", "bot", "coffeebot", "map", "bluemap", "store", "shop", "pvp", "voice", "wiki",
        "there", "anything", "u", "you", "i", "me", "we");

    private static final String NAME = "([a-z0-9_]{3,16})";
    private static final List<Pattern> WHO = List.of(
        Pattern.compile("^(who|whos|who s|who is|who are|who all|whos all)( all| else)?( is| are)? (online|on|playing|"
            + "on rn|on now|online now|online rn|on the server|playing now|here)$"),
        Pattern.compile("^(list|show)( the| me the| me)? (players|online players|player list)$"),
        Pattern.compile("^(player list|online list|players online)$"),
        Pattern.compile("^(is |are )?(anyone|anybody|someone|somebody|people|ppl|any players|anyone else)( even)? "
            + "(on|online|playing|here|on the server|on server)( right now| rn| now| atm| today)?$"));
    private static final List<Pattern> SEEN = List.of(
        Pattern.compile("^is " + NAME + " (online|on|playing|on rn|on now|online now|here|on the server|in game|ingame)$"),
        Pattern.compile("^(when was|when did|when is|when s) " + NAME + " (last )?(on|online|seen|here|playing|play|join|joined|"
            + "last on|last online|last seen)( last)?$"),
        Pattern.compile("^(last seen|have you seen|you seen|seen|lastseen) " + NAME + "$"),
        Pattern.compile("^" + NAME + " (online|last seen)$"));
    private static final Pattern RESTART = Pattern.compile(
        "\\b(when|next|how long|what time|time)\\b.*\\brestart|\\brestart\\b.*\\b(when|next|time|how long)\\b");
    private static final Pattern RECIPE = Pattern.compile(
        "^(how (do|can|to|would|should|does one) (i |you |we |u |one )?(craft|make|get|build|create|obtain|cook|smelt)|"
            + "how to (craft|make|get|obtain)|(crafting )?recipe (for|of|to)|how (is|are)|craft|make)"
            + "( an| a| the| some| my)? (?<item>.+?)( made| crafted| obtained| in create| with create| in survival)?$");
    private static final Pattern MOD = Pattern.compile(
        "^(what is|whats|what s|what does|what do|tell me about|is there|do we have|does the pack have|is|info on|info about)"
            + "( the)? (?<mod>[a-z0-9 :&'_.+-]{2,40}?)( mod)?( do| for| in the pack| installed| added| a mod| on the server)?$");

    /**
     * @param plain the message with mention markup removed (any case, any punctuation)
     */
    public static Intent parse(String plain) {
        if (plain == null) return null;
        String s = plain.toLowerCase(Locale.ROOT)
            .replace("'", "").replace("’", "")
            .replaceAll("[^a-z0-9_ :&.+-]", " ")
            .replaceAll("\\s+", " ").trim();
        s = s.replaceAll("^(hey|hi|yo|hello|pls|please|coffeebot|bot)\\s+", "")
             .replaceAll("\\s+(pls|please|plz|rn|right now|now|bro)$", "").trim();
        // A trailing "?" or "." is gone already; strip sentence dots for the anchored patterns.
        String t = s.replaceAll("[.]+$", "").trim();

        for (Pattern p : WHO) if (p.matcher(t).matches()) return new Intent(Kind.WHO_ONLINE, null);
        for (Pattern p : SEEN) {
            Matcher m = p.matcher(t);
            if (m.matches()) {
                String name = null;
                for (int g = 1; g <= m.groupCount(); g++) {
                    String v = m.group(g);
                    if (v != null && v.matches("[a-z0-9_]{3,16}") && !isPhraseWord(v)) { name = v; break; }
                }
                if (name != null && !NOT_A_PLAYER.contains(name)) return new Intent(Kind.PLAYER_SEEN, name);
            }
        }
        if (RESTART.matcher(t).find()) return new Intent(Kind.NEXT_RESTART, null);
        Matcher r = RECIPE.matcher(t);
        if (r.matches()) {
            String item = r.group("item").replaceAll("^(an|a|the|some) ", "").trim();
            if (item.length() >= 2 && item.split(" ").length <= 5) return new Intent(Kind.RECIPE, item);
        }
        Matcher m = MOD.matcher(t);
        if (m.matches()) {
            String mod = m.group("mod").trim();
            // "is pvp on", "is the server online", "is the end open" are state questions for the rules, not mods.
            boolean stateQuestion = mod.matches(".*\\b(on|online|up|down|open|closed|allowed|enabled|off|working|dead)$");
            if (mod.length() >= 2 && !stateQuestion) return new Intent(Kind.MOD_INFO, mod);
        }
        return null;
    }

    /** Pattern words that the name group must not capture ("when was LAST seen"). */
    private static boolean isPhraseWord(String v) {
        return switch (v) {
            case "when", "was", "did", "is", "last", "seen", "have", "you", "online", "playing", "here", "joined",
                 "join", "lastseen" -> true;
            default -> false;
        };
    }
}
