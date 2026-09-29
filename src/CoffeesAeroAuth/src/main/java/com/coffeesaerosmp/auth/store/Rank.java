package com.coffeesaerosmp.auth.store;

import java.util.Locale;

/**
 * The four purchasable rank tiers, plus {@link #NONE} for everyone else.
 *
 * <p>Ranks are <b>monthly subscriptions</b>. A tier is rented, never owned — which is the whole
 * reason {@link Capability} exists separately from {@code cosmetics_owned}: entitlements granted by a
 * rank evaporate when the subscription lapses, while anything bought with Coffee Beans is permanent.
 * Conflating the two is how a lapse ends up deleting something a player paid cash for.
 *
 * <h2>Badges</h2>
 * Symbols are restricted to ones the DEFAULT Minecraft font already renders, because this launches
 * server-side with no client pack. A glyph that needs a resource pack shows as a missing-character
 * box to every player, and there is no way to detect that server-side.
 *
 * <p>⚠️ {@code ✈} is currently the <i>verified account</i> badge ({@code DisplayAdapter} renders
 * {@code "§6✈ "} for premium). When the auth tags retire it becomes Navigator's badge, so existing
 * players will see a symbol they have had for months change meaning. That is a communications item,
 * not a code one.
 *
 * <h2>Sort order</h2>
 * {@link #sortKey()} exists because vanilla sorts the tab list by scoreboard <b>team name,
 * alphabetically</b> — there is no sort field in the player-info packet. A numeric prefix is the only
 * way to express "Admiral first", and it must be zero-padded and <i>descending</i> by tier.
 */
public enum Rank {

    /** No subscription. Not a tier — the absence of one. */
    NONE     (0, "",   "",          "None"),
    DECKHAND (1, "⚓", "§7",        "Deckhand"),
    NAVIGATOR(2, "✈",  "§6",        "Navigator"),
    CAPTAIN  (3, "⚙", "§b",        "Captain"),
    ADMIRAL  (4, "♛", "§d",        "Admiral");

    /** Higher is better. Used for "includes everything below it" and for tab-list ordering. */
    private final int tier;
    /** Bare symbol, no colour and no trailing space. */
    private final String symbol;
    /** The badge's own colour code. Not a name colour — the player picks that separately. */
    private final String colour;
    private final String displayName;

    Rank(int tier, String symbol, String colour, String displayName) {
        this.tier = tier;
        this.symbol = symbol;
        this.colour = colour;
        this.displayName = displayName;
    }

    public int tier()           { return tier; }
    public String symbol()      { return symbol; }
    public String displayName() { return displayName; }

    /**
     * The badge exactly as {@code PlayerDisplay.Parts.badge()} wants it: coloured, with a trailing
     * space. Empty for {@link #NONE}, which is what makes an unranked player render with no gap.
     */
    public String badge() {
        return this == NONE ? "" : colour + symbol + " ";
    }

    /** True when this rank includes everything {@code other} grants. Reflexive. */
    public boolean includes(Rank other) {
        return other != null && this.tier >= other.tier;
    }

    /**
     * Scoreboard team name component that makes the tab list sort by rank.
     *
     * <p>Descending on purpose: Admiral must sort FIRST, and the sort is ascending alphabetical, so
     * the highest tier needs the lowest key. {@code 9 - tier} gives Admiral {@code "5"} and NONE
     * {@code "9"}. Single digit is enough for five values and keeps the team name short — team names
     * are capped at 16 characters in 1.21, and the visibility state has to fit alongside it.
     */
    public String sortKey() {
        return Integer.toString(9 - tier);
    }

    /** Parse from the database or a Tebex command argument. Unknown or null → {@link #NONE}. */
    public static Rank parse(String raw) {
        if (raw == null) return NONE;
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (Rank r : values()) if (r.name().equals(v)) return r;
        return NONE;
    }

    /** The next tier up, or {@code this} at the top. Used by upgrade prompts, not by billing. */
    public Rank next() {
        return switch (this) {
            case NONE -> DECKHAND;
            case DECKHAND -> NAVIGATOR;
            case NAVIGATOR -> CAPTAIN;
            case CAPTAIN, ADMIRAL -> ADMIRAL;
        };
    }
}
