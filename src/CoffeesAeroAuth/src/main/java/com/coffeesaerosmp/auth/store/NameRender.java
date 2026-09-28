package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a player's cosmetic selection into a rendered name Component.
 *
 * <h2>🔴 The caching rule, and why it is not optional</h2>
 * {@code TabListManager} builds every online player's name <b>twice a second, per viewer</b>. A
 * three-stop gradient on a 16-character name is 16 styled siblings; building that in the send loop is
 * O(players² × name length) of allocation on the server thread, on a server whose median tick is
 * already most of its budget.
 *
 * <p>So a rendered name is cached and only rebuilt when something that affects it actually changes —
 * the display name, the selection, or the effective rank. Unlike the animated rainbow in
 * {@code NameStyles}, a gradient is <b>static</b>, so there is nothing to recompute per frame and the
 * cache can be held indefinitely.
 *
 * <p>Component instances are treated as immutable and shared between viewers deliberately: nothing
 * downstream mutates them, and copying per viewer would defeat the point.
 */
public final class NameRender {

    /** A built name plus the inputs it was built from, so staleness is detectable without a clock. */
    private record Cached(String name, String key, Component component) {}

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    private NameRender() {}

    public static void forget(UUID localUuid) { if (localUuid != null) CACHE.remove(localUuid); }
    public static void clearAll()             { CACHE.clear(); }

    /**
     * The player's styled display name, or {@code null} when the store has nothing to say — in which
     * case the caller renders its own default, exactly like {@code NameStyles.nameComponent}.
     *
     * @param plainDisplayName the RAW display text, with no § colour code on the front. Passing the
     *                         already-coloured string would embed a literal code inside the Component,
     *                         and the client applies embedded codes over the Style set here — which
     *                         silently cancels the gradient partway through the name.
     */
    public static Component styledName(UUID localUuid, String plainDisplayName) {
        if (localUuid == null || plainDisplayName == null || plainDisplayName.isEmpty()) return null;

        StoreState.Snapshot snap = StoreState.get(localUuid);
        Selection sel = snap.liveSelection();
        String style = sel.nameStyle();
        boolean bold = sel.bold();
        if (style == null && !bold) return null;                  // nothing purchased or selected

        String key = style + "|" + bold + "|" + snap.effectiveRank().name();
        Cached hit = CACHE.get(localUuid);
        if (hit != null && hit.key().equals(key) && hit.name().equals(plainDisplayName)) {
            return hit.component();
        }

        Component built = build(plainDisplayName, style, bold);
        CACHE.put(localUuid, new Cached(plainDisplayName, key, built));
        return built;
    }

    private static Component build(String text, String style, boolean bold) {
        if (style != null && style.startsWith(Entitlements.GRADIENT_PREFIX)) {
            String id = style.substring(Entitlements.GRADIENT_PREFIX.length());
            int[] colours = Gradient.forLength(id, text.length());
            if (colours != null) return gradient(text, colours, bold);
            // Unknown gradient: fall through to plain rather than rendering nothing. A cosmetic that
            // cannot be resolved must not make a player's name disappear.
        }
        if (style != null && style.startsWith(Entitlements.COLOUR_PREFIX)) {
            String id = style.substring(Entitlements.COLOUR_PREFIX.length());
            int rgb = Palette.rgb(id);
            if (rgb >= 0) {
                Style st = Style.EMPTY.withColor(TextColor.fromRgb(rgb));
                if (bold) st = st.withBold(true);
                return Component.literal(text).setStyle(st);
            }
        }
        // Bold alone, or an unresolvable colour: still honour bold so a Bean purchase is visible.
        return bold
            ? Component.literal(text).setStyle(Style.EMPTY.withBold(true))
            : Component.literal(text);
    }

    /** One sibling per character. The parent carries no text so the colours cannot bleed. */
    private static Component gradient(String text, int[] colours, boolean bold) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            Style st = Style.EMPTY.withColor(TextColor.fromRgb(colours[i]));
            if (bold) st = st.withBold(true);
            out.append(Component.literal(String.valueOf(text.charAt(i))).setStyle(st));
        }
        return out;
    }

    /**
     * The single solid colour for the nametag above the head.
     *
     * <p>A scoreboard team prefix is one global string with one colour, so a gradient cannot render
     * there — the spec's rule is to use the gradient's <b>first</b> colour, which this returns. Falls
     * back to {@code null} meaning "leave the team colour alone".
     *
     * <p>Returns a {@link ChatFormatting} rather than an RGB because {@code PlayerTeam.setColor} takes
     * one, so the nameplate is limited to the 16 legacy colours by Minecraft, not by us. Every palette
     * colour is one of those 16, so nothing is lost for solid selections.
     */
    public static ChatFormatting nameplateColour(UUID localUuid) {
        String code = nameplateColourCode(localUuid);
        if (code == null || code.length() < 2) return null;
        return ChatFormatting.getByCode(code.charAt(1));
    }

    /**
     * The same solid colour as {@link #nameplateColour}, as a legacy {@code §x} code.
     *
     * <p>Needed because {@code PlayerDisplay.Parts.name()} is a plain string that must carry its own
     * colour — without one the name inherits whatever code the decoration emitted last. Gradients
     * collapse to their first stop here; the per-character version is applied downstream by
     * {@link #styledName}.
     */
    public static String nameplateColourCode(UUID localUuid) {
        if (localUuid == null) return null;
        Selection sel = StoreState.get(localUuid).liveSelection();
        String style = sel.nameStyle();
        if (style == null) return null;

        String colourId = null;
        if (style.startsWith(Entitlements.COLOUR_PREFIX)) {
            colourId = style.substring(Entitlements.COLOUR_PREFIX.length());
        } else if (style.startsWith(Entitlements.GRADIENT_PREFIX)) {
            List<String> stops = Palette.gradient(style.substring(Entitlements.GRADIENT_PREFIX.length()));
            if (stops != null && !stops.isEmpty()) colourId = stops.get(0);
        }
        return Palette.code(colourId);
    }
}
