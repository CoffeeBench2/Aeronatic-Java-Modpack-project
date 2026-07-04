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
import org.spongepowered.asm.mixin.injection.ModifyArg;
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

    /** Dark bronze replacing vanilla's red "brand background" everywhere in this overlay. */
    private static final int AERO_DARK_RGB = 0x1A0F06;

    /** The fade veil fills (fade-in during resource-pack reloads + fade-out): keep vanilla's
     *  computed alpha, but swap the RED brand color for dark bronze. */
    @ModifyArg(method = "render", require = 0, index = 5, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;fill(Lnet/minecraft/client/renderer/RenderType;IIIII)V"))
    private int aerocore$deRedVeil(int color) {
        return (color & 0xFF000000) | AERO_DARK_RGB;
    }

    /** The no-fade launch branch clears the framebuffer with the red brand color — darken that too. */
    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/GlStateManager;_clearColor(FFFF)V"))
    private void aerocore$deRedClear(float r, float g, float b, float a) {
        com.mojang.blaze3d.platform.GlStateManager._clearColor(
            ((AERO_DARK_RGB >> 16) & 0xFF) / 255.0F,
            ((AERO_DARK_RGB >> 8) & 0xFF) / 255.0F,
            (AERO_DARK_RGB & 0xFF) / 255.0F, 1.0F);
    }

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
    private void aerocore$dropLogoFirstHalf(GuiGraphics g, ResourceLocation tex,
                                            int x, int y, int w, int h,
                                            float u, float v, int uw, int vh, int tw, int th) {
        // no-op — no center emblem; the airship art + cog-row bar carry the loading screen
    }

    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIFFIIII)V",
        ordinal = 1))
    private void aerocore$dropLogoSecondHalf(GuiGraphics g, ResourceLocation tex,
                                             int x, int y, int w, int h,
                                             float u, float v, int uw, int vh, int tw, int th) {
        // no-op
    }

    /** The vanilla progress BAR becomes a row of small meshing cogwheels: slots light up left to
     *  right as loading advances, neighbours counter-rotate like a real gear train, and the
     *  not-yet-reached slots sit as dim silhouettes. */
    @Redirect(method = "render", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/LoadingOverlay;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphics;IIIIF)V"))
    private void aerocore$cogRowProgressBar(LoadingOverlay self, GuiGraphics g,
                                            int minX, int minY, int maxX, int maxY, float alpha) {
        int cogSize = Math.max(12, (maxY - minY) + 8);            // slightly taller than the old bar
        int spacing = cogSize + 2;                                // teeth nearly touch — gear train
        int count = Math.max(4, (maxX - minX) / spacing);
        int rowW = count * spacing - 2;
        int startX = (minX + maxX - rowW) / 2;
        int cy = (minY + maxY) / 2;
        float progress = Mth.clamp(this.currentProgress, 0.0F, 1.0F);
        float lit = progress * count;                              // fractional: last cog fades in
        float spin = (Util.getMillis() % 5400L) / 15.0F;           // one turn / 5.4s

        for (int slot = 0; slot < count; slot++) {
            float visibility = Mth.clamp(lit - slot, 0.0F, 1.0F);  // 0 = upcoming, 1 = fully lit
            float slotAlpha = alpha * (0.18F + 0.82F * visibility);
            float shade = 0.35F + 0.65F * visibility;              // dim silhouette → full color
            float angle = (slot % 2 == 0 ? spin : -spin);          // mesh: alternate directions
            int cx = startX + slot * spacing + cogSize / 2;

            g.setColor(shade, shade, shade, slotAlpha);
            g.pose().pushPose();
            g.pose().translate(cx, cy, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(angle));
            float s = cogSize / (float) EarlyAssets.COG_W;
            g.pose().scale(s, s, 1.0F);
            g.blit(EarlyAssets.COG, -EarlyAssets.COG_W / 2, -EarlyAssets.COG_H / 2,
                0.0F, 0.0F, EarlyAssets.COG_W, EarlyAssets.COG_H, EarlyAssets.COG_W, EarlyAssets.COG_H);
            g.pose().popPose();
        }
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
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
