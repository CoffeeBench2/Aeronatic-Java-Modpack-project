package com.coffeesaerosmp.auth.store.gui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paints a whole screen into a chest window's TITLE using the server resource pack's fonts (built by
 * {@code src/ShopResourcePack/build_rp.py}, which also writes the metrics this reads).
 *
 * <p>The client draws the title as one line starting at window (8, 6), so everything is a run of glyphs: art
 * glyphs for the panels, space glyphs that move the pen, and text in a font whose ascent puts it at the right
 * height ({@code aerosmp:t<Y>}). To land text at an exact x the pen position must be known exactly, so every
 * advance is computed here the way the client computes it: a bitmap glyph advances by its last opaque column + 1,
 * times the scale, rounded, plus one; bold adds one.
 *
 * <p>Coordinates are window pixels: (0,0) is the chest window's top-left; negative x is left of it.
 */
final class TitleCanvas {

    // ── metrics (generated) ───────────────────────────────────────────────────

    static final class Metrics {
        final Map<Character, Integer> widths = new HashMap<>();
        final Map<String, Integer> wdAdvance = new HashMap<>();
        int textYmin, textYmax, headerY, tabY, hotbarY, canvasLeft, viewTop, viewBottom;
        int[] rows, left, window, right, bigYs;
        int dollTop, pixel;
        final Map<String, Scene> scenes = new HashMap<>();
    }

    record Scene(char glyph, int advance, int width) {}

    static final Metrics M = load();

    private static Metrics load() {
        Metrics m = new Metrics();
        try (var in = TitleCanvas.class.getResourceAsStream("/assets/coffees_aero_auth/rp/metrics.json")) {
            if (in == null) throw new IllegalStateException("rp/metrics.json missing from the jar");
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : o.getAsJsonObject("widths").entrySet()) m.widths.put(e.getKey().charAt(0), e.getValue().getAsInt());
            for (var e : o.getAsJsonObject("wdAdvance").entrySet()) m.wdAdvance.put(e.getKey(), e.getValue().getAsInt());
            JsonObject l = o.getAsJsonObject("layout");
            m.textYmin = l.get("textYmin").getAsInt();
            m.textYmax = l.get("textYmax").getAsInt();
            m.headerY = l.get("headerY").getAsInt();
            m.tabY = l.get("tabY").getAsInt();
            m.hotbarY = l.get("hotbarY").getAsInt();
            m.canvasLeft = l.get("canvasLeft").getAsInt();
            m.rows = ints(l, "rows");
            m.left = ints(l, "left");
            m.window = ints(l, "window");
            m.right = ints(l, "right");
            m.bigYs = ints(l, "bigYs");
            int[] v = ints(l, "viewport");
            m.viewTop = v[0];
            m.viewBottom = v[1];
            JsonObject doll = o.getAsJsonObject("doll");
            m.dollTop = doll.get("top").getAsInt();
            m.pixel = doll.get("pixel").getAsInt();
            for (var e : doll.getAsJsonObject("scenes").entrySet()) {
                JsonObject sc = e.getValue().getAsJsonObject();
                m.scenes.put(e.getKey(), new Scene(sc.get("char").getAsString().charAt(0), sc.get("advance").getAsInt(),
                    sc.get("w").getAsInt()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("can't read rp/metrics.json", e);
        }
        return m;
    }

    private static int[] ints(JsonObject o, String key) {
        var a = o.getAsJsonArray(key);
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
        return out;
    }

    // ── glyphs ────────────────────────────────────────────────────────────────

    static final ResourceLocation SPACE = ResourceLocation.fromNamespaceAndPath("aerosmp", "sp");
    static final ResourceLocation ART = ResourceLocation.fromNamespaceAndPath("aerosmp", "wd");
    static final char STAR = '\uE200', CHECK = '\uE201', BEAN = '\uE202', CROSS = '\uE203', LOCK = '\uE204',
        ARROW = '\uE205', DOT = '\uE206';
    static final char TILE_L = '\uE000', TILE_R = '\uE001', TAB = '\uE020', ROW0 = '\uE030';

    private final MutableComponent out = Component.empty();
    private int x = 8;                                // the client starts the title at window x = 8

    int x() { return x; }

    /** Move the pen to window-x {@code target} with space glyphs (powers of two, either direction). */
    TitleCanvas moveTo(int target) {
        int d = target - x;
        if (d == 0) return this;
        StringBuilder sb = new StringBuilder();
        int n = Math.abs(d);
        while (n > 0) {
            int bit = Math.min(8, 31 - Integer.numberOfLeadingZeros(n));     // largest power of two <= n, max 256
            sb.append((char) ((d > 0 ? 0xE101 : 0xE111) + bit));
            n -= 1 << bit;
        }
        out.append(Component.literal(sb.toString()).withStyle(Style.EMPTY.withFont(SPACE)));
        x = target;
        return this;
    }

    /** An art glyph from the pack's wardrobe font, untinted. */
    TitleCanvas art(int atX, char glyph, int advance) {
        moveTo(atX);
        out.append(Component.literal(String.valueOf(glyph)).withStyle(Style.EMPTY.withFont(ART).withColor(0xFFFFFF)));
        x += advance;
        return this;
    }

    static final ResourceLocation SCENES = ResourceLocation.fromNamespaceAndPath("aerosmp", "sc");

    /** A pre-composed effect picture; its height is baked into the pack. */
    TitleCanvas scene(int atX, Scene s) {
        moveTo(atX);
        out.append(Component.literal(String.valueOf(s.glyph())).withStyle(Style.EMPTY.withFont(SCENES).withColor(0xFFFFFF)));
        x += s.advance();
        return this;
    }

    /**
     * One row of square pixels with its top at window-y {@code y}, starting at {@code x0}: runs of the same colour
     * become one tinted string of pixel glyphs (each pixel = glyph + -2 space = net {@link Metrics#pixel}).
     * Alpha 0 is skipped.
     */
    TitleCanvas pixels(int x0, int y, int[] argb) {
        ResourceLocation font = ResourceLocation.fromNamespaceAndPath("aerosmp", "p" + y);
        int i = 0;
        while (i < argb.length) {
            int c = argb[i];
            int j = i + 1;
            while (j < argb.length && argb[j] == c) j++;
            if ((c >>> 24) != 0) {
                moveTo(x0 + i * M.pixel);
                out.append(Component.literal("\uE300\uE301".repeat(j - i))
                    .withStyle(Style.EMPTY.withFont(font).withColor(c & 0xFFFFFF)));
                x += (j - i) * M.pixel;
            }
            i = j;
        }
        return this;
    }

    /** Text with its top at window-y {@code y}. Returns the x where it ended. */
    int text(int atX, int y, Component c, int defaultColour, boolean big) {
        moveTo(atX);
        ResourceLocation font = font(y, big);
        for (Seg s : segments(c, defaultColour)) {
            if (s.text.isEmpty()) continue;
            Style st = Style.EMPTY.withFont(font).withColor(TextColor.fromRgb(s.colour)).withBold(s.bold);
            out.append(Component.literal(s.text).withStyle(st));
            x += width(s.text, s.bold, big);
        }
        return x;
    }

    int text(int atX, int y, String s, int colour) { return text(atX, y, Component.literal(s), colour, false); }

    /** Text whose right edge is at {@code rightX}. */
    void textRight(int rightX, int y, Component c, int defaultColour, boolean big) {
        text(rightX - width(c, defaultColour, big), y, c, defaultColour, big);
    }

    void textCentre(int centreX, int y, Component c, int defaultColour, boolean big) {
        text(centreX - width(c, defaultColour, big) / 2, y, c, defaultColour, big);
    }

    Component build() {
        moveTo(8);                                    // tidy: leave the pen where it started
        return out;
    }

    // ── measuring ─────────────────────────────────────────────────────────────

    private static ResourceLocation font(int y, boolean big) {
        if (big) {
            for (int b : M.bigYs) if (b == y) return ResourceLocation.fromNamespaceAndPath("aerosmp", "b" + y);
            throw new IllegalArgumentException("no big font at y=" + y);
        }
        int cy = Math.max(M.textYmin, Math.min(M.textYmax, y));
        return ResourceLocation.fromNamespaceAndPath("aerosmp", "t" + cy);
    }

    /** One character's advance, exactly as the client computes it. */
    static int advance(char c, boolean bold, boolean big) {
        if (c == ' ') return big ? 8 : 4;
        Integer w = M.widths.get(c);
        if (w == null) return 0;
        int scaled = big ? (int) (0.5f + w * 2f) + 1 : w + 1;
        return scaled + (bold ? 1 : 0);
    }

    static int width(String s, boolean bold, boolean big) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += advance(s.charAt(i), bold, big);
        return w;
    }

    static int width(Component c, int defaultColour, boolean big) {
        int w = 0;
        for (Seg s : segments(c, defaultColour)) w += width(s.text, s.bold, big);
        return w;
    }

    /** Cut {@code s} to fit {@code max} pixels, ending in "..." when it had to be cut. */
    static String fit(String s, int max) {
        String clean = sanitize(s);
        if (width(clean, false, false) <= max) return clean;
        int dots = width("...", false, false);
        StringBuilder b = new StringBuilder();
        int w = 0;
        for (int i = 0; i < clean.length(); i++) {
            int a = advance(clean.charAt(i), false, false);
            if (w + a + dots > max) break;
            b.append(clean.charAt(i));
            w += a;
        }
        return b.toString().stripTrailing() + "...";
    }

    /** Word-wrap to {@code max} pixels, at most {@code lines} lines (the last one cut with "..."). */
    static List<String> wrap(String s, int max, int lines) {
        List<String> out = new ArrayList<>();
        String[] words = sanitize(s).split(" ");
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String next = cur.isEmpty() ? words[i] : cur + " " + words[i];
            if (width(next, false, false) <= max) { cur = new StringBuilder(next); continue; }
            if (!cur.isEmpty()) out.add(cur.toString());
            cur = new StringBuilder(words[i]);
            if (out.size() == lines - 1) {             // last line: take the rest and cut it
                StringBuilder rest = new StringBuilder(cur);
                for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
                out.add(fit(rest.toString(), max));
                return out;
            }
        }
        if (!cur.isEmpty()) out.add(fit(cur.toString(), max));
        return out;
    }

    // ── text segments ─────────────────────────────────────────────────────────

    private record Seg(String text, int colour, boolean bold) {}

    /**
     * Flattens a component into coloured runs the pack fonts can draw. Legacy § codes inside literal text are
     * applied here (the badge strings carry them), unsupported characters are mapped or dropped.
     */
    private static List<Seg> segments(Component c, int defaultColour) {
        List<Seg> out = new ArrayList<>();
        c.visit((style, str) -> {
            int colour = style.getColor() != null ? style.getColor().getValue() : defaultColour;
            boolean bold = style.isBold();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < str.length(); i++) {
                char ch = str.charAt(i);
                if (ch == '§' && i + 1 < str.length()) {
                    if (!b.isEmpty()) { out.add(new Seg(b.toString(), colour, bold)); b.setLength(0); }
                    char code = Character.toLowerCase(str.charAt(++i));
                    var f = net.minecraft.ChatFormatting.getByCode(code);
                    if (f == null) continue;
                    if (f.isColor() && f.getColor() != null) { colour = f.getColor(); bold = false; }
                    else if (f == net.minecraft.ChatFormatting.BOLD) bold = true;
                    else if (f == net.minecraft.ChatFormatting.RESET) { colour = defaultColour; bold = false; }
                    continue;
                }
                b.append(ch);
            }
            if (!b.isEmpty()) out.add(new Seg(sanitize(b.toString()), colour, bold));
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** Printable ASCII plus the pack's icon glyphs; common typography mapped, anything else dropped. */
    static String sanitize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '—', '–' -> b.append('-');
                case '‘', '’' -> b.append('\'');
                case '“', '”' -> b.append('"');
                case '…' -> b.append("...");
                case '·', '•' -> b.append(DOT);
                case '★', '⭐' -> b.append(STAR);
                case '✓', '✔' -> b.append(CHECK);
                case '☕' -> b.append(BEAN);
                case '✗', '✘' -> b.append(CROSS);
                case '→', '▸', '➤' -> b.append(ARROW);
                case 'é', 'è' -> b.append('e');
                default -> {
                    if (ch == ' ' || M.widths.containsKey(ch)) b.append(ch);
                }
            }
        }
        return b.toString();
    }
}
