package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.client.EarlyAssets;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Rebrands the LAUNCH loading screen (vanilla {@code LoadingOverlay}): the pack's airship art as
 * the background, the Mojang logo replaced by a spinning Create cogwheel, and a brass progress
 * bar. Vanilla's fade state machine is left fully intact — we only swap what gets DRAWN:
 * <ul>
 *   <li>{@code setColor} (ordinal 0, carries the fade alpha every frame on every branch — the
 *       launch branch never calls {@code fill}, it {@code glClear}s) → draw the background first.</li>
 *   <li>the two Mojang-logo {@code blit}s → one spinning cogwheel (inherits the fade alpha
 *       already set on the shader color) / no-op.</li>
 *   <li>{@code drawProgressBar} → brass-styled bar (no text: font atlases aren't loaded during
 *       the first resource reload).</li>
 * </ul>
 * All injectors are {@code require = 0}: if another mod (rrls) rewires the overlay first, we
 * degrade to whatever it does rather than crash.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {

    @Shadow private float currentProgress;
    @Shadow @Final private Minecraft minecraft;

    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;setColor(FFFF)V", ordinal = 0))
    private void aerocore$bgThenColor(GuiGraphics g, float r, float green, float b, float a) {
        EarlyAssets.ensureRegistered(this.minecraft);
        aerocore$drawBackground(g, a);
        g.setColor(r, green, b, a);
    }

    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIFFIIII)V",
        ordinal = 0))
    private void aerocore$cogInsteadOfLogo(GuiGraphics g, ResourceLocation tex,
                                           int x, int y, int w, int h,
                                           float u, float v, int uw, int vh, int tw, int th) {
        // Spinning cogwheel where the Mojang logo sat; shader color already carries the fade alpha.
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        int size = (int) (Math.min(g.guiWidth() * 0.75, g.guiHeight()) * 0.28);
        float angle = (Util.getMillis() % 7200L) / 20.0F;   // one turn / 7.2s
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(angle));
        float s = size / (float) EarlyAssets.COG_W;
        g.pose().scale(s, s, 1.0F);
        g.blit(EarlyAssets.COG, -EarlyAssets.COG_W / 2, -EarlyAssets.COG_H / 2,
            0.0F, 0.0F, EarlyAssets.COG_W, EarlyAssets.COG_H, EarlyAssets.COG_W, EarlyAssets.COG_H);
        g.pose().popPose();
    }

    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIFFIIII)V",
        ordinal = 1))
    private void aerocore$dropSecondLogoHalf(GuiGraphics g, ResourceLocation tex,
                                             int x, int y, int w, int h,
                                             float u, float v, int uw, int vh, int tw, int th) {
        // no-op — the cog is drawn by the ordinal-0 redirect
    }

    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphics;IIIIF)V"))
    private void aerocore$brassProgressBar(LoadingOverlay self, GuiGraphics g,
                                           int minX, int minY, int maxX, int maxY, float alpha) {
        int a = Math.round(alpha * 255.0F) << 24;
        int border = 0x009C7430 | a;   // brass
        int fill   = 0x00F0C05A | a;   // bright brass
        int back   = 0x00241505 | a;   // dark bronze channel
        g.fill(minX, minY, maxX, maxY, back);
        g.fill(minX, minY, maxX, minY + 1, border);
        g.fill(minX, maxY - 1, maxX, maxY, border);
        g.fill(minX, minY, minX + 1, maxY, border);
        g.fill(maxX - 1, minY, maxX, maxY, border);
        int w = (int) ((maxX - minX - 4) * Mth.clamp(this.currentProgress, 0.0F, 1.0F));
        g.fill(minX + 2, minY + 2, minX + 2 + w, maxY - 2, fill);
    }

    /** Cover-scaled airship art at the given alpha (the overlay's fade value). */
    private static void aerocore$drawBackground(GuiGraphics g, float alpha) {
        float scale = Math.max(g.guiWidth() / (float) EarlyAssets.BG_W,
                               g.guiHeight() / (float) EarlyAssets.BG_H);
        int drawW = (int) (EarlyAssets.BG_W * scale);
        int drawH = (int) (EarlyAssets.BG_H * scale);
        int x = (g.guiWidth() - drawW) / 2;
        int y = (g.guiHeight() - drawH) / 2;
        g.setColor(1.0F, 1.0F, 1.0F, alpha);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1.0F);
        g.blit(EarlyAssets.BG, 0, 0, 0.0F, 0.0F,
            EarlyAssets.BG_W, EarlyAssets.BG_H, EarlyAssets.BG_W, EarlyAssets.BG_H);
        g.pose().popPose();
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
