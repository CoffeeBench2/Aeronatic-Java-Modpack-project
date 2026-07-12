package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.client.RgbChatAnimator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Animates RGB player names in the chat HUD by wrapping each drawn chat line through
 * {@link RgbChatAnimator} — glyphs tagged with the sentinel RGB font get a per-frame rainbow, so the
 * name flows even though the chat message is immutable. {@code require = 0}: if the redirect ever
 * fails to apply, chat renders exactly as vanilla.
 */
@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"))
    private int aerocore$animateRgbNames(GuiGraphics graphics, Font font, FormattedCharSequence text,
                                         int x, int y, int color) {
        return graphics.drawString(font, RgbChatAnimator.animate(text), x, y, color);
    }
}
