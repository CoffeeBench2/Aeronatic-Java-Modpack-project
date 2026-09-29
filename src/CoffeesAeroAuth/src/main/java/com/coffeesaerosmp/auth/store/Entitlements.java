package com.coffeesaerosmp.auth.store;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a player is allowed to wear, given their rank and what they own outright.
 *
 * <p>Deliberately pure: no {@code net.minecraft}, no database, no mod state. This is the class that
 * decides whether a purchase is honoured, so it is the one that must be unit-testable exhaustively.
 *
 * <h2>The rule that matters</h2>
 * There are <b>two independent sources</b> of permission and they must never be collapsed:
 * <ul>
 *   <li><b>Rank</b> — rented. Evaporates the moment a subscription lapses.</li>
 *   <li><b>Owned</b> — bought with Coffee Beans. Permanent, per the spec: "Nothing bought with Beans
 *       is ever removed."</li>
 * </ul>
 * So a lapsed Admiral who once bought a blue name with Beans keeps the blue. Implementing the lapse as
 * "clear the selection" would delete something a player paid cash for; implementing it as "keep the
 * selection" would hand out a gradient for free. {@link #sanitise} does neither — it re-derives.
 */
public final class Entitlements {

    /** A thing a rank can grant. Not a cosmetic id — a *class* of cosmetic. */
    public enum Capability {
        /** One of the eight Deckhand colours. */
        SOLID_BASIC,
        /** Any colour in the full palette. */
        SOLID_FULL,
        BOLD,
        /** Two-colour gradient. */
        GRADIENT_2,
        /** Three-colour gradient. */
        GRADIENT_3,
        /** Custom join and leave message (moderated). */
        JOIN_MESSAGE,
        /** Custom chat title (moderated). Admiral only, and not purchasable with Beans. */
        CHAT_TITLE
    }

    // ── cosmetic id vocabulary ────────────────────────────────────────────────────
    // Stored verbatim in cosmetics_owned.cosmetic_id, so these prefixes are a data format.
    // Changing one orphans every row already written with it.
    public static final String COLOUR_PREFIX   = "colour:";
    public static final String GRADIENT_PREFIX = "gradient:";
    public static final String BOLD_ID         = "bold";
    public static final String JOIN_MESSAGE_ID = "join_message";

    private Entitlements() {}

    /** What a rank grants. Cumulative — each tier includes everything below it. */
    public static Set<Capability> of(Rank rank) {
        EnumSet<Capability> caps = EnumSet.noneOf(Capability.class);
        if (rank == null || rank == Rank.NONE) return Collections.unmodifiableSet(caps);
        if (rank.includes(Rank.DECKHAND))  caps.add(Capability.SOLID_BASIC);
        if (rank.includes(Rank.NAVIGATOR)) { caps.add(Capability.SOLID_FULL); caps.add(Capability.BOLD); }
        if (rank.includes(Rank.CAPTAIN))   { caps.add(Capability.GRADIENT_2); caps.add(Capability.JOIN_MESSAGE); }
        if (rank.includes(Rank.ADMIRAL))   { caps.add(Capability.GRADIENT_3); caps.add(Capability.CHAT_TITLE); }
        return Collections.unmodifiableSet(caps);
    }

    public static boolean has(Rank rank, Capability cap) { return of(rank).contains(cap); }

    /**
     * May this player wear this cosmetic id right now?
     *
     * @param id    a cosmetic id — {@code colour:gold}, {@code gradient:sunrise}, {@code bold},
     *              {@code join_message}
     * @param rank  current rank, {@link Rank#NONE} when lapsed or never subscribed
     * @param owned ids bought outright with Beans; never null — pass an empty set
     */
    public static boolean mayUse(String id, Rank rank, Set<String> owned) {
        if (id == null || id.isBlank()) return false;
        String key = id.trim().toLowerCase(Locale.ROOT);
        Set<String> own = owned == null ? Set.of() : owned;

        if (key.startsWith(COLOUR_PREFIX)) {
            String colour = key.substring(COLOUR_PREFIX.length());
            // 🔴 Hard gate, checked BEFORE ownership. A staff colour must be unwearable even if a row
            // granting it somehow exists — a bad Tebex package, a hand-run command, a restored
            // backup. Ownership must not be able to buy its way past the anti-spoof rule.
            if (Palette.isStaffReserved(colour)) return false;
            if (!Palette.isColour(colour)) return false;
            if (own.contains(key)) return true;                        // Beans: permanent
            if (has(rank, Capability.SOLID_FULL)) return true;
            return has(rank, Capability.SOLID_BASIC) && Palette.basicColours().contains(colour);
        }
        if (key.startsWith(GRADIENT_PREFIX)) {
            String grad = key.substring(GRADIENT_PREFIX.length());
            int stops = Palette.gradientStops(grad);
            if (stops == 0) return false;
            // A curated gradient can never contain a staff colour, but check anyway: the palette is
            // hand-edited, and this is the cheapest place to catch a bad edit.
            for (String c : Palette.gradient(grad)) if (Palette.isStaffReserved(c)) return false;
            if (own.contains(key)) return true;
            if (stops == 2) return has(rank, Capability.GRADIENT_2);
            return has(rank, Capability.GRADIENT_3);
        }
        if (key.equals(BOLD_ID)) {
            return own.contains(BOLD_ID) || has(rank, Capability.BOLD);
        }
        if (key.equals(JOIN_MESSAGE_ID)) {
            return own.contains(JOIN_MESSAGE_ID) || has(rank, Capability.JOIN_MESSAGE);
        }
        return false;   // unknown id → refused. Allowlist, not blacklist.
    }

    /** A player's current cosmetic choices. Any field may be null/false meaning "default". */
    public record Selection(String nameStyle, boolean bold, String chatTitle, String joinMessage) {
        public static final Selection DEFAULT = new Selection(null, false, null, null);
    }

    /**
     * The outcome of re-deriving a selection against current entitlements.
     *
     * @param selection what the player may actually wear now
     * @param changed   true when anything was taken away, so the caller can tell them why rather than
     *                  letting their name silently change colour
     */
    public record Sanitised(Selection selection, boolean changed, Set<String> removed) {}

    /**
     * Re-derive a selection so it contains only what the player may currently wear.
     *
     * <p>Falls back rather than clearing. A lapsed Captain whose gradient switches off drops to a
     * Bean-owned colour if they have one, and only then to the default. Clearing outright would look
     * identical to losing a Bean purchase, which is the thing the spec promises never happens.
     *
     * <p>The fallback is <b>deterministic</b> — palette order, not set iteration order — so the same
     * lapse produces the same colour every time. A non-deterministic fallback means a player's name
     * changes colour on every restart and generates a bug report per restart.
     */
    public static Sanitised sanitise(Selection current, Rank rank, Set<String> owned) {
        Selection cur = current == null ? Selection.DEFAULT : current;
        Set<String> own = owned == null ? Set.of() : owned;
        Set<String> removed = new LinkedHashSet<>();

        String style = cur.nameStyle();
        if (style != null && !mayUse(style, rank, own)) {
            removed.add(style);
            style = firstOwnedColour(own, rank);
        }

        boolean bold = cur.bold();
        if (bold && !mayUse(BOLD_ID, rank, own)) { removed.add(BOLD_ID); bold = false; }

        String title = cur.chatTitle();
        if (title != null && !has(rank, Capability.CHAT_TITLE)) { removed.add("chat_title"); title = null; }

        String join = cur.joinMessage();
        if (join != null && !mayUse(JOIN_MESSAGE_ID, rank, own)) { removed.add(JOIN_MESSAGE_ID); join = null; }

        Selection out = new Selection(style, bold, title, join);
        return new Sanitised(out, !removed.isEmpty(), Collections.unmodifiableSet(removed));
    }

    /**
     * The first palette-ordered colour this player owns outright, or null.
     * Gradients are not considered: a lapsed rank should not be rescued by a gradient they may no
     * longer wear, and a Bean-owned gradient is still usable so it never reaches this method.
     */
    private static String firstOwnedColour(Set<String> owned, Rank rank) {
        for (String colour : Palette.allColours()) {
            String id = COLOUR_PREFIX + colour;
            if (owned.contains(id) && mayUse(id, rank, owned)) return id;
        }
        return null;
    }
}
