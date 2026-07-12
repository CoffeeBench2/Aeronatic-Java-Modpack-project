package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.client.AeroOverlayProgress;
import com.coffeesaerosmp.core.client.EarlyAssets;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brands the STARTUP loading overlay. NeoForge swaps vanilla's {@code LoadingOverlay} for its own
 * subclass at game launch and fully overrides {@code render} (it draws the early-window
 * framebuffer), so {@link LoadingOverlayMixin} — which covers RELOADS — never runs at startup.
 * This mixin paints over NeoForge's frame at TAIL each render: the airship art (cover-scaled) and
 * the cog-row progress bar, honoring the overlay's own fade so the title screen crossfades in only
 * AFTER loading completes.
 *
 * <p>All drawing is raw Tesselator quads: at TAIL the method has replaced the projection with a
 * bottom-up framebuffer ortho, so GuiGraphics coordinates would be wrong — we draw in framebuffer
 * pixels in THEIR projection instead. {@code require = 0}: on any mismatch players just see the
 * stock NeoForge screen.</p>
 */
@Mixin(NeoForgeLoadingOverlay.class)
public abstract class NeoForgeStartupOverlayMixin {

    @Shadow(remap = false) private long fadeOutStart;

    @Inject(method = "render", at = @At("TAIL"), require = 0, remap = false)
    private void aerocore$brandStartupOverlay(GuiGraphics graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        EarlyAssets.ensureRegistered(mc);

        float fadeOutTimer = this.fadeOutStart > -1L
            ? (Util.getMillis() - this.fadeOutStart) / 1000.0F : -1.0F;
        float fade = 1.0F - Mth.clamp(fadeOutTimer - 1.0F, 0.0F, 1.0F);
        if (fade <= 0.01F) return;

        int fbW = mc.getWindow().getWidth();
        int fbH = mc.getWindow().getHeight();
        if (fbW <= 0 || fbH <= 0) return;

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(770, 771);   // SRC_ALPHA, ONE_MINUS_SRC_ALPHA
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // ── Background: airship art, cover-scaled, centered (bottom-up ortho is active) ──
        var bgTex = mc.getTextureManager().getTexture(EarlyAssets.BG);
        RenderSystem.setShaderTexture(0, bgTex.getId());
        float scale = Math.max(fbW / (float) EarlyAssets.BG_W, fbH / (float) EarlyAssets.BG_H);
        float w = EarlyAssets.BG_W * scale, h = EarlyAssets.BG_H * scale;
        float left = (fbW - w) / 2.0F, bottom = (fbH - h) / 2.0F;
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buf.addVertex(left,     bottom,     0).setUv(0, 1).setColor(1f, 1f, 1f, fade);
        buf.addVertex(left + w, bottom,     0).setUv(1, 1).setColor(1f, 1f, 1f, fade);
        buf.addVertex(left + w, bottom + h, 0).setUv(1, 0).setColor(1f, 1f, 1f, fade);
        buf.addVertex(left,     bottom + h, 0).setUv(0, 0).setColor(1f, 1f, 1f, fade);
        BufferUploader.drawWithShader(buf.buildOrThrow());

        // ── Cog-row progress bar (same design as the reload overlay's) ──
        float progress = this instanceof AeroOverlayProgress p ? p.aerocore$progress() : 0.0F;
        var cogTex = mc.getTextureManager().getTexture(EarlyAssets.COG);
        RenderSystem.setShaderTexture(0, cogTex.getId());
        int size = Math.max(16, (int) (fbH * 0.045F));
        int spacing = size + Math.max(2, size / 8);
        int count = Mth.clamp((int) (fbW * 0.4F) / spacing, 6, 14);
        float rowW = count * spacing - (spacing - size);
        float startX = (fbW - rowW) / 2.0F;
        float cy = fbH * 0.1675F;                                  // matches vanilla's bar height
        float lit = Mth.clamp(progress, 0.0F, 1.0F) * count;
        float spin = (Util.getMillis() % 5400L) / 15.0F;

        buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int slot = 0; slot < count; slot++) {
            float visibility = Mth.clamp(lit - slot, 0.0F, 1.0F);
            // Upcoming cogs used to sit at 0.18 alpha / 0.35 shade — nearly invisible during the
            // early startup screen (2026-07-12 report: "can't see the cogs, almost transparent").
            // Floors raised so the whole gear train reads clearly, lit cogs still brighten/fill.
            float a = fade * (0.62F + 0.38F * visibility);
            float shade = 0.55F + 0.45F * visibility;
            float angle = (float) Math.toRadians(slot % 2 == 0 ? spin : -spin);
            float cos = Mth.cos(angle), sin = Mth.sin(angle);
            float cx = startX + slot * spacing + size / 2.0F;
            float s2 = size / 2.0F;
            // corners pre-rotation (bottom-up coords): BL, BR, TR, TL with matching UVs
            float[][] c = { {-s2, -s2, 0, 1}, {s2, -s2, 1, 1}, {s2, s2, 1, 0}, {-s2, s2, 0, 0} };
            for (float[] k : c) {
                float x = cx + k[0] * cos - k[1] * sin;
                float y = cy + k[0] * sin + k[1] * cos;
                buf.addVertex(x, y, 0).setUv(k[2], k[3]).setColor(shade, shade, shade, a);
            }
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
