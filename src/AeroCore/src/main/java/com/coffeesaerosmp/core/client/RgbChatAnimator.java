package com.coffeesaerosmp.core.client;

import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.awt.Color;

/**
 * Animates RGB player names in the chat HUD. The server (CoffeesAeroAuth) tags each letter of an
 * "RGB" name with the sentinel font {@link #RGB_FONT}; {@code ChatComponentMixin} routes every chat
 * line's glyphs through {@link #animate}, which re-colours the tagged glyphs with a time-driven
 * rainbow each frame — so the name flows like a Razer-Aurora wave even though the chat message
 * itself is immutable. Untagged glyphs pass through untouched. Fully defensive: any error falls back
 * to the original sequence so chat can never break.
 */
public final class RgbChatAnimator {

    public static final ResourceLocation RGB_FONT =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "rgb");

    private static final long  CYCLE_MS = 3000L;   // faster + smoother than the 4s tab wave
    private static final float SAT = 0.9f, BRI = 1.0f;
    private static final float PER_CHAR_HUE = 0.045f;   // hue step between adjacent letters

    private RgbChatAnimator() {}

    /** Wrap a chat line so tagged (RGB-font) glyphs animate; others are unchanged. */
    public static FormattedCharSequence animate(FormattedCharSequence in) {
        if (in == null) return null;
        return sink -> {
            final long t = System.currentTimeMillis();
            final float base = (t % CYCLE_MS) / (float) CYCLE_MS;
            final int[] rgbIndex = {0};   // position along the gradient, only advances on tagged glyphs
            try {
                return in.accept((index, style, codePoint) -> {
                    Style s = style;
                    if (style != null && RGB_FONT.equals(style.getFont())) {
                        float hue = (base + PER_CHAR_HUE * rgbIndex[0]++) % 1.0f;
                        int rgb = Color.HSBtoRGB(hue, SAT, BRI) & 0xFFFFFF;
                        s = style.withColor(TextColor.fromRgb(rgb));
                    }
                    return sink.accept(index, s, codePoint);
                });
            } catch (Throwable e) {
                return in.accept(sink);   // any failure: draw the message unmodified
            }
        };
    }
}
