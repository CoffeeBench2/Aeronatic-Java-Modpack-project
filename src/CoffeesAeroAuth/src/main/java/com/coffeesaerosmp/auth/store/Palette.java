package com.coffeesaerosmp.auth.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The curated name-colour and gradient palette. Presets only — never raw hex or arbitrary § codes.
 *
 * <h2>Why a whitelist and not a validator</h2>
 * The spec's rule is "presets, not hex codes, so every name stays readable". A blacklist of bad
 * colours is the wrong shape: it has to be complete to be correct, and every new formatting code
 * Minecraft adds silently becomes allowed. This is an allowlist, so an unknown id is refused by
 * default and the failure mode is "you cannot select that" rather than "a player is invisible".
 *
 * <h2>🔴 Reserved colours</h2>
 * {@code red} (§c) and {@code dark_red} (§4) belong to staff and appear in no selectable set. This is
 * the anti-spoof rule from the spec, and it pairs with the ordering guarantee in
 * {@code PlayerDisplay.segments} — staff render leftmost, in a colour nobody can buy.
 *
 * <p>{@code black} (§0) and {@code dark_gray} (§8) are excluded for a different reason: they are
 * unreadable against the chat background, and a name nobody can read is a moderation problem. §8 is
 * also the colour the op-only real-name suffix uses, so a player coloured §8 would visually merge
 * with it.
 */
public final class Palette {

    /** Colours staff alone may wear. Never selectable, at any rank, for any number of Beans. */
    public static final Set<String> STAFF_RESERVED =
        Collections.unmodifiableSet(new LinkedHashSet<>(List.of("red", "dark_red")));

    /** id → legacy § code. Insertion order is the order menus display them in. */
    private static final Map<String, String> COLOURS = new LinkedHashMap<>();
    /**
     * id → packed RGB, needed because a gradient interpolates between colours and legacy § codes
     * cannot express an intermediate value. These are Minecraft's own chat colours, so a solid name
     * rendered from {@link #code} and one rendered from {@link #rgb} look identical.
     */
    private static final Map<String, Integer> RGB = new LinkedHashMap<>();
    /** The eight Deckhand colours — a deliberate subset, not the first eight of the full palette. */
    private static final Set<String> BASIC = new LinkedHashSet<>();
    /** id → the colours of a curated gradient, in order. Length 2 or 3. */
    private static final Map<String, List<String>> GRADIENTS = new LinkedHashMap<>();

    static {
        // Full palette: every readable, non-staff legacy colour.
        COLOURS.put("white",        "§f");
        COLOURS.put("gray",         "§7");
        COLOURS.put("gold",         "§6");
        COLOURS.put("yellow",       "§e");
        COLOURS.put("green",        "§a");
        COLOURS.put("dark_green",   "§2");
        COLOURS.put("aqua",         "§b");
        COLOURS.put("dark_aqua",    "§3");
        COLOURS.put("blue",         "§9");
        COLOURS.put("dark_blue",    "§1");
        COLOURS.put("light_purple", "§d");
        COLOURS.put("dark_purple",  "§5");

        // Minecraft's own chat-colour RGB values, so solid names look the same whether they were
        // rendered from the § code or from these.
        RGB.put("white",        0xFFFFFF);
        RGB.put("gray",         0xAAAAAA);
        RGB.put("gold",         0xFFAA00);
        RGB.put("yellow",       0xFFFF55);
        RGB.put("green",        0x55FF55);
        RGB.put("dark_green",   0x00AA00);
        RGB.put("aqua",         0x55FFFF);
        RGB.put("dark_aqua",    0x00AAAA);
        RGB.put("blue",         0x5555FF);
        RGB.put("dark_blue",    0x0000AA);
        RGB.put("light_purple", 0xFF55FF);
        RGB.put("dark_purple",  0xAA00AA);

        // Deckhand's "pick 1 of 8". Chosen for contrast against each other so eight players at the
        // bottom tier still look distinct.
        BASIC.addAll(List.of("white", "gray", "gold", "yellow", "green", "aqua", "blue", "light_purple"));

        // Two-colour gradients (Captain). Airship-themed names.
        GRADIENTS.put("sunrise",   List.of("gold", "yellow"));
        GRADIENTS.put("altitude",  List.of("aqua", "blue"));
        GRADIENTS.put("canopy",    List.of("green", "dark_green"));
        GRADIENTS.put("dusk",      List.of("light_purple", "dark_purple"));
        GRADIENTS.put("brass",     List.of("yellow", "gold"));
        GRADIENTS.put("deepwater", List.of("dark_aqua", "dark_blue"));
        // Three-colour gradients (Admiral).
        GRADIENTS.put("horizon",   List.of("gold", "yellow", "white"));
        GRADIENTS.put("stratos",   List.of("white", "aqua", "blue"));
        GRADIENTS.put("aurora",    List.of("aqua", "green", "light_purple"));
        GRADIENTS.put("ember",     List.of("yellow", "gold", "light_purple"));
    }

    private Palette() {}

    public static Set<String> allColours()   { return Collections.unmodifiableSet(COLOURS.keySet()); }
    public static Set<String> basicColours() { return Collections.unmodifiableSet(BASIC); }
    public static Set<String> allGradients() { return Collections.unmodifiableSet(GRADIENTS.keySet()); }

    /** The § code for a colour id, or {@code null} if it is not in the palette. */
    public static String code(String colourId) {
        return colourId == null ? null : COLOURS.get(colourId.toLowerCase(Locale.ROOT));
    }

    /** Packed RGB for a colour id, or {@code -1} when unknown. */
    public static int rgb(String colourId) {
        if (colourId == null) return -1;
        Integer v = RGB.get(colourId.toLowerCase(Locale.ROOT));
        return v == null ? -1 : v;
    }

    /** The colour ids of a gradient, or {@code null} if unknown. */
    public static List<String> gradient(String gradientId) {
        if (gradientId == null) return null;
        List<String> g = GRADIENTS.get(gradientId.toLowerCase(Locale.ROOT));
        return g == null ? null : Collections.unmodifiableList(g);
    }

    /** How many colours a gradient has: 2, 3, or 0 when unknown. */
    public static int gradientStops(String gradientId) {
        List<String> g = gradient(gradientId);
        return g == null ? 0 : g.size();
    }

    public static boolean isColour(String id)   { return code(id) != null; }
    public static boolean isGradient(String id) { return gradient(id) != null; }

    /**
     * True when a colour is staff-only. Checked separately from palette membership on purpose: a
     * reserved colour must be <i>absent</i> from the palette AND recognised as reserved, so that a
     * future edit adding §c to {@code COLOURS} still cannot be selected.
     */
    public static boolean isStaffReserved(String id) {
        return id != null && STAFF_RESERVED.contains(id.toLowerCase(Locale.ROOT));
    }
}
