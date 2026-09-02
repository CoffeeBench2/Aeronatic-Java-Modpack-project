package com.coffeesaerosmp.auth.display;

/**
 * The single source of truth for how a player's name is rendered, everywhere.
 *
 * <p>Before this class, THREE systems wrote the displayed name independently and overwrote each
 * other: {@code NameVisibility.reveal} (scoreboard team prefix), {@code sendStyledNames} and
 * {@code sendAdminNameOverlay} (both {@code UPDATE_DISPLAY_NAME}, ~2/sec). Because a tab-list
 * display name makes the client IGNORE the team prefix, and neither packet carried the clan tag,
 * the tag was painted by the team and erased from TAB twice a second. One cause, three bugs.</p>
 *
 * <p>Deliberately pure: no {@code net.minecraft} imports, no static mod state. That is what makes
 * it unit-testable without a server, and the tests encode the regression above.</p>
 */
public final class PlayerDisplay {

    /** Where the result is going. Each surface can carry only what its mechanism supports. */
    public enum Surface {
        /** {@code UPDATE_DISPLAY_NAME} packet — per viewer, supports animation. */
        TAB,
        /** Scoreboard team prefix — GLOBAL to the team, so no per-viewer content, no animation. */
        NAMEPLATE,
        /** Chat component, sent per recipient. */
        CHAT,
        /** Join/leave line, sent per recipient. */
        JOIN,
        /** Plain text for the Discord bridge — § codes stripped. */
        DISCORD
    }

    /**
     * Everything the renderer needs, already resolved to strings.
     *
     * @param badge     account badge WITH its trailing space, e.g. {@code "§6✈ "}; may be empty
     * @param staffTag  staff badge WITH its trailing space, e.g. {@code "§c[ADMIN] "}; may be empty
     * @param clanTag   clan tag WITH its trailing space, e.g. {@code "§7[§9AERO§7] "}; may be empty
     * @param name      the display name, INCLUDING its own colour code (e.g. "§fCoffee") — without
     *                  one it inherits the last code emitted by the decoration
     * @param realName  the account name when masked, else {@code null}
     */
    public record Parts(String badge, String staffTag, String clanTag, String name, String realName) {}

    private PlayerDisplay() {}

    /**
     * The composed name split at the NAME boundary, so a caller that must substitute a
     * per-character animated Component for the name can do so without string surgery.
     * Subtracting the name with {@code String.replace} is unsound: the display name is routinely
     * a substring of the account name ("Coffee" inside "MrCoffeeBench"), may occur inside the
     * clan tag, and may be null or empty.
     */
    public record Segments(String prefix, String name, String suffix) {
        public String flat() { return prefix + name + suffix; }
    }

    public static Segments segments(Parts p, Surface surface, boolean viewerIsOp) {
        StringBuilder pre = new StringBuilder();
        append(pre, p.badge());
        append(pre, p.staffTag());
        append(pre, p.clanTag());

        // NAMEPLATE is a scoreboard team PREFIX — the client appends the scoreboard name after it,
        // so including the name here would render it twice.
        String name = surface == Surface.NAMEPLATE || p.name() == null ? "" : p.name();

        String suffix = "";
        boolean perViewer = surface == Surface.TAB || surface == Surface.CHAT || surface == Surface.JOIN;
        if (perViewer && viewerIsOp && p.realName() != null && !p.realName().isBlank()
            && !p.realName().equals(name)) {
            suffix = " §8(" + p.realName() + ')';
        }
        return new Segments(pre.toString(), name, suffix);
    }

    public static String compose(Parts p, Surface surface, boolean viewerIsOp) {
        String out = segments(p, surface, viewerIsOp).flat();
        return surface == Surface.DISCORD ? stripCodes(out).trim() : out;
    }

    /**
     * Everything that goes BEFORE the name — badge, staff tag, clan tag — and nothing else.
     *
     * <p>A scoreboard team prefix must exclude the name, because the client appends the scoreboard
     * name after it. Deriving this by string-trimming {@link #compose} would be fragile: the
     * display name and the scoreboard name differ whenever NameMask is active, so the substring
     * would not match and the name would render twice.</p>
     */
    public static String composePrefix(Parts p) {
        return segments(p, Surface.NAMEPLATE, false).prefix();
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        sb.append(part);
        if (!part.endsWith(" ")) sb.append(' ');   // convention enforced, not merely documented
    }

    /** Removes legacy § formatting codes. Used for Discord, which renders them literally. */
    public static String stripCodes(String in) {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder(in.length());
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '§') { if (i + 1 < in.length()) i++; continue; }
            sb.append(c);
        }
        return sb.toString();
    }
}
