package com.coffeesaerosmp.auth.mail;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules a letter and a parcel must pass. Pure (no Minecraft classes) so every rule is unit-tested.
 *
 * <h2>Why Numismatics is matched on the SERIALISED item</h2>
 * Coins hide. A shulker box keeps its contents in {@code minecraft:container}, a bundle in
 * {@code minecraft:bundle_contents}, a Create toolbox or a backpack mod in its own block-entity data.
 * Chasing each component would miss the next container mod someone adds. The item's full SNBT holds
 * every one of them, so one substring check on {@code "numismatics:"} catches a coin at any depth.
 * Owner rule (2026-10-06): Numismatics circulates in the world only, never by mail.
 */
public final class MailRules {

    public static final int LETTER_MAX = 256;
    public static final int PREVIEW_MAX = 64;
    public static final int PARCEL_SLOTS = 9;
    public static final int DAILY_SENDS = 10;
    public static final long DRAFT_TIMEOUT_MS = 2 * 60_000L;
    private static final String BLOCKED_NAMESPACE = "numismatics:";

    private MailRules() {}

    /** Null when the letter is fine, otherwise a player-facing reason. */
    public static String letterProblem(String text) {
        if (text == null || text.isBlank()) return "Your letter is empty. Type something, or 'cancel'.";
        int len = text.length();
        if (len > LETTER_MAX) {
            return "That's " + len + " characters; a letter fits " + LETTER_MAX + ". Try a shorter one.";
        }
        return null;
    }

    /** The inbox line for a letter: its first line, capped. Also stored in the legacy subject column. */
    public static String preview(String text) {
        if (text == null || text.isBlank()) return "(no message)";
        String first = normalise(text).split("\n", 2)[0].trim();
        if (first.isEmpty()) return "(no message)";
        return first.length() <= PREVIEW_MAX ? first : first.substring(0, PREVIEW_MAX - 1) + "…";
    }

    /** True when the item's serialised data mentions a Numismatics id anywhere. */
    public static boolean containsBlockedSnbt(String snbt) {
        return snbt != null && snbt.contains(BLOCKED_NAMESPACE);
    }

    /** {@code limit} 0 = unlimited. */
    public static boolean limitReached(int sentInWindow, int limit) {
        return limit > 0 && sentInWindow >= limit;
    }

    /**
     * Splits text into book pages of at most {@code lines} lines of at most {@code width} characters.
     * Words are never split unless a single word is longer than a line.
     */
    public static List<List<String>> bookPages(String text, int width, int lines) {
        List<String> all = new ArrayList<>();
        for (String para : normalise(text == null ? "" : text).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (word.isEmpty()) continue;
                while (word.length() > width) {                // hard-wrap a monster word
                    if (line.length() > 0) { all.add(line.toString()); line.setLength(0); }
                    all.add(word.substring(0, width));
                    word = word.substring(width);
                }
                if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                    all.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            all.add(line.toString());
        }
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < all.size(); i += lines) {
            pages.add(new ArrayList<>(all.subList(i, Math.min(all.size(), i + lines))));
        }
        if (pages.isEmpty()) pages.add(new ArrayList<>(List.of("")));
        return pages;
    }

    /** Staff and older system mail write a literal backslash-n for a new line. */
    static String normalise(String text) {
        return text.replace("\\n", "\n").replace("\r", "");
    }
}
