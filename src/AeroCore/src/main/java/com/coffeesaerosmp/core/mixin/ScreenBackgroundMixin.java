package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.client.EarlyAssets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the pack's airship art behind EVERY out-of-world menu (Options, resource packs, controls…),
 * not just our title screen — otherwise any nested settings screen falls back to the vanilla
 * panorama. Two hooks:
 * <ul>
 *   <li>{@code renderPanorama} (only ever called when {@code level == null}) → draw the art
 *       cover-scaled instead of the vanilla cube-map panorama.</li>
 *   <li>{@code renderBlurredBackground} → skipped when out of world so the art stays CRISP
 *       (vanilla's menu blur is what made the first title-screen build look smeared). The in-world
 *       pause-menu blur is untouched.</li>
 * </ul>
 * Vanilla still draws its translucent dark menu layer on top, which keeps widget text readable.
 */
@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {

    @Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true, require = 0)
    private void aerocore$airshipInsteadOfPanorama(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        Minecraft mc = Minecraft.getInstance();
        EarlyAssets.ensureRegistered(mc);
        float scale = Math.max(self.width / (float) EarlyAssets.BG_W, self.height / (float) EarlyAssets.BG_H);
        int drawW = (int) (EarlyAssets.BG_W * scale);
        int drawH = (int) (EarlyAssets.BG_H * scale);
        graphics.pose().pushPose();
        graphics.pose().translate((self.width - drawW) / 2.0F, (self.height - drawH) / 2.0F, 0);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.blit(EarlyAssets.BG, 0, 0, 0.0F, 0.0F,
            EarlyAssets.BG_W, EarlyAssets.BG_H, EarlyAssets.BG_W, EarlyAssets.BG_H);
        graphics.pose().popPose();
        ci.cancel();
    }

    @Inject(method = "renderBlurredBackground", at = @At("HEAD"), cancellable = true, require = 0)
    private void aerocore$noMenuBlurOutOfWorld(float partialTick, CallbackInfo ci) {
        if (Minecraft.getInstance().level == null) ci.cancel();
    }
}
