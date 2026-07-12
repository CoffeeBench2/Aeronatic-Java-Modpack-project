package com.coffeesaerosmp.auth.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;

import java.awt.Color;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Animated per-character rainbow ("RGB") name gradient — a cosmetic flourish for select players
 * (owner MrCoffeeBench for now; generalized to any player via {@code /authmod namecolor} in a later
 * update). Renders as a {@link Component} with per-letter hex {@link TextColor}, so it shows in chat
 * and the tab list (both take full Components). Nametags above the head use the scoreboard team color
 * and can't gradient — they keep the badge color.
 *
 * <p>The hue phase advances with wall-clock time so callers that re-emit the name (chat per message,
 * tab list ~2×/s) produce a moving rainbow. Pure math ({@link Color#HSBtoRGB}) — no AWT display, safe
 * on a headless server.</p>
 */
public final class RainbowText {

    private static final long  CYCLE_MS = 4000L;    // one full hue rotation
    private static final float SPREAD   = 0.62f;    // hue span across the whole word
    private static final float SAT      = 0.85f;
    private static final float BRI      = 1.0f;

    /** Sentinel font tagging RGB-name glyphs so CoffeesAeroCore's client mixin re-animates them in
     *  chat (chat messages are immutable, so the STATIC gradient below is the fallback for clients
     *  without Core; Core clients see it flow). Renders as the default font (Core ships a reference). */
    private static final ResourceLocation RGB_FONT =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "rgb");

    /** Usernames rendered with the RGB gradient (lower-cased). Seeded from config; mutable for cmds. */
    private static final Set<String> ENABLED = ConcurrentHashMap.newKeySet();

    private RainbowText() {}

    public static void setEnabledNames(String csv) {
        ENABLED.clear();
        if (csv == null) return;
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) ENABLED.add(t);
        }
    }

    public static boolean isEnabled(String username) {
        return username != null && ENABLED.contains(username.toLowerCase(java.util.Locale.ROOT));
    }

    /** Rainbow gradient of {@code text}, phase from the current time. */
    public static Component gradient(String text) {
        return gradient(text, System.currentTimeMillis());
    }

    public static Component gradient(String text, long timeMs) {
        if (text == null || text.isEmpty()) return Component.empty();
        MutableComponent out = Component.empty();
        float base = (timeMs % CYCLE_MS) / (float) CYCLE_MS;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            float hue = (base + (n <= 1 ? 0f : SPREAD * i / (n - 1))) % 1.0f;
            int rgb = Color.HSBtoRGB(hue, SAT, BRI) & 0xFFFFFF;
            final char ch = text.charAt(i);
            out.append(Component.literal(String.valueOf(ch))
                .withStyle(s -> s.withColor(TextColor.fromRgb(rgb)).withFont(RGB_FONT)));
        }
        return out;
    }
}
