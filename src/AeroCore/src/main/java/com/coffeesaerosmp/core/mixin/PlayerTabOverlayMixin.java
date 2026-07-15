package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.client.RgbChatAnimator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes RGB player names flow in the tab list exactly like they do in chat. The server
 * (CoffeesAeroAuth) only re-pushes the tab list ~2×/sec, so a server-side gradient looks choppy;
 * instead we re-colour the name every frame on the client. The name is drawn as a {@link Component}
 * whose glyphs carry the sentinel RGB font — we convert it to its visual-order sequence and run it
 * through the shared {@link RgbChatAnimator} (same cycle + hue step as chat), so tab and chat match.
 *
 * <p>Non-RGB text (untagged names, header/footer) passes through untouched, so redirecting every
 * {@code drawString(Font, Component, …)} in {@code render} is safe. {@code require = 0}: if the
 * redirect never applies, the tab list renders exactly as vanilla.</p>
 */
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
    private int aerocore$animateRgbTabNames(GuiGraphics graphics, Font font, Component text,
                                            int x, int y, int color) {
        try {
            return graphics.drawString(font, RgbChatAnimator.animate(text.getVisualOrderText()), x, y, color);
        } catch (Throwable e) {
            return graphics.drawString(font, text, x, y, color);   // any failure: draw vanilla
        }
    }
}
