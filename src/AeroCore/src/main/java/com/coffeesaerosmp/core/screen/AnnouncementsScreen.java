package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.announce.AnnouncementData;
import com.coffeesaerosmp.core.announce.AnnouncementState;
import com.coffeesaerosmp.core.client.AeroButton;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Main-menu "Announcements" screen — the pack changelog on an ornate pixel-art scroll (art baked into
 * Core, no resourcepack needed). Opening: the corner cogs crank the parchment open while the ink fades
 * in; closing (button or Esc) cranks it shut. The parchment is sized to the CONTENT (never a
 * screen-tall empty scroll), centred, and its middle is tiled from a clean band of the art so repeats
 * are seam-free. Reads bundled data only — no network.
 *
 * <p>Rendering notes (1.21.1): the pack ships Veil (jar-in-jar inside ldlib2) which wraps the shader
 * pipeline, and under it {@code GuiGraphics.blit(ResourceLocation,…)} — the IMMEDIATE Tesselator
 * path — can silently draw nothing until a window resize rebinds targets, while batched text always
 * renders. So ALL our textures go through {@link #blitTex}: quads submitted to the same batched
 * {@code RenderType.text} pipeline the font uses (proven working in this pack), full-bright, then
 * flushed. Never call {@code GuiGraphics.flush()} here: it force-enables depth-test for the rest of
 * the frame.</p>
 */
public class AnnouncementsScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroCore-Announce");

    private static final ResourceLocation SCROLL =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "textures/gui/scroll.png");
    private static final ResourceLocation COG =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "textures/gui/cogwheel.png");
    // UnifrakturMaguntia (OFL, bundled as font/ancient.ttf + font/ancient.json) — blackletter
    // "old manuscript" face for the scroll heading. Body stays on the default font for readability.
    private static final ResourceLocation ANCIENT_FONT =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "ancient");

    // scroll.png layout (measured): 535x572 — top roller rows 0..112, bottom roller rows 458..572.
    // The parchment between the rollers splits into a top curl cap (112..152), a CLEAN repeatable
    // band (160..410 — no curl shading, so tiling never shows seams), and a bottom curl cap
    // (418..458). cogwheel.png is 279x280.
    private static final int TEX_W = 535, TEX_H = 572, S_TOP = 112, S_BOT = 114;
    private static final int CAP_TOP_V = 112, CAP_TOP_H = 40, TILE_V = 160, TILE_SRC_H = 250,
                             CAP_BOT_V = 418, CAP_BOT_H = 40;
    private static final int COG_W = 112, COG_H = 112;

    private static final float UNROLL_MS = 620f;

    // Ink palette on cream parchment.
    private static final int C_HEADER  = 0xFF6E3B12;
    private static final int C_ADDED   = 0xFF2E6A26;
    private static final int C_FIXED   = 0xFF8A5410;
    private static final int C_REMOVED = 0xFF8A2A20;
    private static final int C_TEXT    = 0xFF473722;
    private static final int C_INK     = 0xFF4A2E12;
    private static final int GOLD      = 0x00F0C05A;   // rgb only; alpha applied per-use

    private final Screen parent;

    private record Line(String text, int color, int indent) {}
    private final List<Line> lines = new ArrayList<>();

    // Geometry (set in init).
    private int sx, scrollW, topH, botH, topMargin, midFull, tileH, capT, capB, titleY;
    private int viewLeft, viewRight, viewTop, viewBottom;

    private int scroll = 0, contentHeight = 0;
    private long openedAt = 0, closeAt = 0;
    private boolean closing = false, openSoundDone = false, loggedGeometry = false;
    private AeroButton closeBtn;

    public AnnouncementsScreen(Screen parent) {
        super(Component.literal("Announcements"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AnnouncementState.markLatestSeen();
        // init() re-runs on window resize — keep animation state instead of replaying it.
        if (openedAt == 0) openedAt = Util.getMillis();
        if (!openSoundDone) { playPage(); openSoundDone = true; }

        scrollW = Math.min(320, this.width - 40);
        sx      = (this.width - scrollW) / 2;
        float sc = scrollW / (float) TEX_W;
        topH    = Math.round(S_TOP * sc);
        botH    = Math.round(S_BOT * sc);
        capT    = Math.round(CAP_TOP_H * sc);
        capB    = Math.round(CAP_BOT_H * sc);
        tileH   = Math.max(8, Math.round(TILE_SRC_H * sc));   // natural (unstretched) tile height

        int inset = Math.round(scrollW * 0.18f);
        viewLeft  = sx + inset;
        viewRight = sx + scrollW - inset;
        buildLines(viewRight - viewLeft - 12);   // -12: wrapped continuation indents stay inside too

        // Parchment sized to CONTENT (title block + changelog + padding), capped to the window and
        // centred vertically — never a screen-tall scroll of empty parchment. Overflow scrolls.
        int titleBlock = 26;
        int minMid = capT + capB + 40;
        int needed = capT + 6 + titleBlock + contentHeight + 10 + capB;
        int avail  = this.height - 40 - topH - botH;   // leave room for the Close button below
        midFull    = Mth.clamp(needed, minMid, Math.max(minMid, avail));
        topMargin  = Math.max(8, (this.height - 24 - (topH + midFull + botH)) / 2);

        titleY     = topMargin + topH + capT + 4;
        viewTop    = titleY + titleBlock - 4;
        viewBottom = topMargin + topH + midFull - capB - 6;

        int cbY = Math.min(this.height - 24, topMargin + topH + midFull + botH + 4);
        closeBtn = AeroButton.aero(Component.literal("Close"), b -> beginClose())
            .bounds(this.width / 2 - 60, cbY, 120, 20).build();
        this.addRenderableWidget(closeBtn);

        loggedGeometry = false;   // re-log once per (re)layout so resize states are diagnosable
    }

    /**
     * The window was resized while we're open. The parent (title screen) isn't the active screen, so
     * vanilla never resizes IT — but we render its background every frame. Without this it would keep
     * its old dimensions and paint into one stale-sized corner of the new window.
     */
    @Override
    public void resize(Minecraft mc, int w, int h) {
        if (parent != null) parent.resize(mc, w, h);
        super.resize(mc, w, h);
    }

    private void playPage() {
        try {
            this.minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 0.9f));
        } catch (Throwable ignored) {}   // sound is garnish — never let it break the screen
    }

    private void beginClose() {
        if (!closing) { closing = true; closeAt = Util.getMillis(); playPage(); }
    }

    /** Esc rolls the scroll up first; Esc again skips the animation. */
    @Override
    public void onClose() {
        if (closing) this.minecraft.setScreen(parent);
        else beginClose();
    }

    private void buildLines(int wrapWidth) {
        lines.clear();
        List<AnnouncementData.Entry> entries = AnnouncementData.entries();
        if (entries.isEmpty())
            lines.add(new Line("No announcements yet — check back after the next update!", C_TEXT, 0));
        boolean first = true;
        for (AnnouncementData.Entry e : entries) {
            if (!first) lines.add(new Line("", C_TEXT, 0));
            first = false;
            // Entries whose version doesn't start with a digit are TEASERS ("Coming Soon…"):
            // no "v" prefix and the additions read as "On the horizon" instead of "Added".
            boolean teaser = !e.version().isEmpty() && !Character.isDigit(e.version().charAt(0));
            String head = (teaser ? e.version() : "v" + e.version())
                + (e.date().isBlank() ? "" : "   " + e.date());
            lines.add(new Line(head, C_HEADER, 0));
            if (!e.title().isBlank()) lines.add(new Line(e.title(), C_TEXT, 0));
            section(wrapWidth, teaser ? "✈ On the horizon" : "＋ Added", e.added(), teaser ? C_HEADER : C_ADDED);
            section(wrapWidth, "✔ Fixed",   e.fixed(),   C_FIXED);
            section(wrapWidth, "－ Removed", e.removed(), C_REMOVED);
        }
        contentHeight = lines.size() * 11;
    }

    private void section(int wrapWidth, String label, List<String> items, int color) {
        if (items.isEmpty()) return;
        lines.add(new Line(label, color, 0));
        for (String item : items) {
            List<FormattedCharSequence> wrapped = this.font.split(Component.literal("• " + item), wrapWidth - 8);
            boolean firstWrap = true;
            for (var seq : wrapped) {
                lines.add(new Line(seqToString(seq), C_TEXT, firstWrap ? 6 : 12));
                firstWrap = false;
            }
        }
    }

    private static String seqToString(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((idx, style, cp) -> { sb.appendCodePoint(cp); return true; });
        return sb.toString();
    }

    private static float easeOut(float p) { p = Mth.clamp(p, 0f, 1f); float i = 1f - p; return 1f - i * i * i; }
    private static float easeIn(float p)  { p = Mth.clamp(p, 0f, 1f); return p * p * p; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = Util.getMillis();
        float unroll;
        if (!closing) {
            unroll = easeOut((now - openedAt) / UNROLL_MS);
        } else {
            float ct = now - closeAt;
            if (ct > UNROLL_MS + 60) { this.minecraft.setScreen(parent); return; }
            unroll = 1f - easeIn(ct / UNROLL_MS);
        }
        int dstMid = Math.round(midFull * unroll);
        closeBtn.visible = !closing && unroll >= 1f;

        // CRITICAL ORDER (root cause of the invisible-scroll bug): in 1.21.1, Screen.render()
        // ITSELF calls renderBackground() before drawing widgets. super.render must therefore run
        // FIRST — background + Close button — and everything we paint comes AFTER, on top. Calling
        // super.render at the end repaints the fullscreen opaque backdrop OVER the whole scroll.
        super.render(g, mouseX, mouseY, partialTick);

        int topY = topMargin, midY = topMargin + topH, botY = midY + dstMid;

        if (!loggedGeometry) {
            loggedGeometry = true;
            LOGGER.info("[Announce r5/polish] layout {}x{} scroll sx={} w={} topY={} midFull={} view=({},{})..({},{}) lines={}",
                this.width, this.height, sx, scrollW, topY, midFull, viewLeft, viewTop, viewRight, viewBottom, lines.size());
        }

        // ── Soft drop shadow, then a golden glow behind the scroll (grows with the unroll) ──
        g.fill(sx + 5, topY + 6, sx + scrollW + 5, botY + botH + 6, 0x3C000000);
        int glowA = (int) (unroll * 40);
        if (glowA > 4) {
            g.fill(sx - 14, topY - 10, sx + scrollW + 14, botY + botH + 10, (glowA / 3 << 24) | GOLD);
            g.fill(sx - 8,  topY - 6,  sx + scrollW + 8,  botY + botH + 6,  (glowA / 2 << 24) | GOLD);
            g.fill(sx - 4,  topY - 3,  sx + scrollW + 4,  botY + botH + 3,  (glowA     << 24) | GOLD);
        }

        // Fallback panel: keeps the ink readable even if the texture quads ever fail to draw.
        g.fill(sx, topY, sx + scrollW, topY + topH, 0xFF8A5A20);
        if (dstMid > 0) g.fill(sx + 6, midY, sx + scrollW - 6, botY, 0xFFEBD9A4);
        g.fill(sx, botY, sx + scrollW, botY + botH, 0xFF8A5A20);

        // ── Cogwheels cranking the rollers (pack's own cog, behind the scroll) ──
        // Rotation is mechanically tied to how far the scroll has opened, plus a slow idle drift.
        float crank = dstMid * 2.2f + (now % 360000L) / 90f;
        int cogSz = Math.round(topH * 1.05f);
        drawCog(g, sx + 4,           topY + topH / 2, cogSz,  crank);
        drawCog(g, sx + scrollW - 4, topY + topH / 2, cogSz, -crank);
        drawCog(g, sx + 4,           botY + botH / 2, cogSz, -crank);
        drawCog(g, sx + scrollW - 4, botY + botH / 2, cogSz,  crank);

        // ── The scroll: rollers + curl caps + a CLEAN tiled band between (crisp, seam-free) ──
        if (dstMid > 0) {
            int innerH = dstMid - capT - capB;
            if (innerH <= 0) {   // still unrolling through the caps: squeeze the two curls together
                int t = Math.max(1, Math.round(dstMid * (capT / (float) (capT + capB))));
                blitTex(g, SCROLL, sx, midY, scrollW, t, 0, CAP_TOP_V, TEX_W, CAP_TOP_H, TEX_W, TEX_H);
                if (dstMid - t > 0)
                    blitTex(g, SCROLL, sx, midY + t, scrollW, dstMid - t, 0, CAP_BOT_V, TEX_W, CAP_BOT_H, TEX_W, TEX_H);
            } else {
                blitTex(g, SCROLL, sx, midY, scrollW, capT, 0, CAP_TOP_V, TEX_W, CAP_TOP_H, TEX_W, TEX_H);
                int drawn = 0;
                while (drawn < innerH) {
                    int seg = Math.min(tileH, innerH - drawn);
                    int srcH = Math.max(1, Math.round(TILE_SRC_H * (seg / (float) tileH)));
                    blitTex(g, SCROLL, sx, midY + capT + drawn, scrollW, seg, 0, TILE_V, TEX_W, srcH, TEX_W, TEX_H);
                    drawn += seg;
                }
                blitTex(g, SCROLL, sx, botY - capB, scrollW, capB, 0, CAP_BOT_V, TEX_W, CAP_BOT_H, TEX_W, TEX_H);
            }
        }
        blitTex(g, SCROLL, sx, topY, scrollW, topH, 0, 0, TEX_W, S_TOP, TEX_W, TEX_H);
        blitTex(g, SCROLL, sx, botY, scrollW, botH, 0, TEX_H - S_BOT, TEX_W, S_BOT, TEX_W, TEX_H);

        // Depth shading on the open parchment: faint light at the top edge, soft shade near the
        // unrolling edge — sells the "curved paper" illusion without touching the pixel art.
        if (dstMid > capT + capB + 16) {
            g.fillGradient(sx + 6, midY + capT, sx + scrollW - 6, midY + capT + 10, 0x14FFFFFF, 0x00FFFFFF);
            g.fillGradient(sx + 6, botY - capB - 22, sx + scrollW - 6, botY - capB, 0x00000000, 0x1E000000);
        }

        // ── Ink: title + changelog, clipped to the parchment, fading in near the unrolling edge ──
        if (dstMid > capT + 20) {
            if (botY - capB > titleY + 12) {
                drawInkTitle(g, "Coffees Aeronautics SMP", this.width / 2, titleY);
                // Two tiny cogs slowly turning beside the heading — the machine that drives the scroll.
                int miniR = 7;
                drawCog(g, viewLeft + 2,  titleY + 4, miniR * 2,  crank * 0.5f);
                drawCog(g, viewRight - 2, titleY + 4, miniR * 2, -crank * 0.5f);
            }

            int limit = Math.min(botY - capB - 2, viewBottom);
            g.enableScissor(viewLeft - 4, viewTop - 1, viewRight + 16, limit + 10);
            int y = viewTop - scroll;
            for (Line ln : lines) {
                if (y > viewTop - 12 && y < limit + 11 && !ln.text().isEmpty()) {
                    float fade = Mth.clamp((limit - (y + 9)) / 10f, 0f, 1f);   // soft reveal at the edge
                    int a = (int) (fade * 255);
                    if (a > 8)
                        g.drawString(this.font, ln.text(), viewLeft + ln.indent(), y,
                            (a << 24) | (ln.color() & 0xFFFFFF), false);
                }
                y += 11;
            }
            g.disableScissor();
        }

        // ── Sparkles drifting around the open scroll ──
        if (unroll > 0.5f) drawSparkles(g, now, topY, botY + botH, (int) (unroll * 255));

        // Scrollbar (inside the parchment, only when fully open).
        if (!closing && unroll >= 1f) {
            int viewH = viewBottom - viewTop;
            if (contentHeight > viewH) {
                int barH = Math.max(20, viewH * viewH / contentHeight);
                int barY = viewTop + (viewH - barH) * scroll / Math.max(1, contentHeight - viewH);
                g.fill(viewRight + 6, barY, viewRight + 9, barY + barH, 0xB06E4A22);
            }
        }
    }

    /** The pack cogwheel, rotating about its center. */
    private void drawCog(GuiGraphics g, int cx, int cy, int size, float angleDeg) {
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(angleDeg));
        blitTex(g, COG, -size / 2, -size / 2, size, size, 0, 0, COG_W, COG_H, COG_W, COG_H);
        g.pose().popPose();
    }

    /**
     * Textured GUI quad through the BATCHED {@code RenderType.text} pipeline (the one the font uses)
     * instead of {@code GuiGraphics.blit}'s immediate Tesselator path — see the class javadoc for why.
     * Draws the (uPx,vPx)..(uPx+uwPx,vPx+vhPx) region of the texture scaled into (x,y,w,h), then
     * flushes so painter's order is preserved against the surrounding fills/text.
     */
    private void blitTex(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                         int uPx, int vPx, int uwPx, int vhPx, int texW, int texH) {
        float u0 = uPx / (float) texW, v0 = vPx / (float) texH;
        float u1 = (uPx + uwPx) / (float) texW, v1 = (vPx + vhPx) / (float) texH;
        Matrix4f m = g.pose().last().pose();
        MultiBufferSource.BufferSource buffers = this.minecraft.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
        int light = 0xF000F0;   // full-bright GUI light
        vc.addVertex(m, x,     y,     0).setColor(-1).setUv(u0, v0).setLight(light);
        vc.addVertex(m, x,     y + h, 0).setColor(-1).setUv(u0, v1).setLight(light);
        vc.addVertex(m, x + w, y + h, 0).setColor(-1).setUv(u1, v1).setLight(light);
        vc.addVertex(m, x + w, y,     0).setColor(-1).setUv(u1, v0).setLight(light);
        buffers.endBatch();
    }

    /** Golden twinkles drifting up past the scroll — deterministic, no allocation. */
    private void drawSparkles(GuiGraphics g, long now, int top, int bottom, int maxA) {
        int span = Math.max(1, bottom - top - 12);
        for (int i = 0; i < 12; i++) {
            float phase = i * 0.7f;
            float tw = 0.5f + 0.5f * Mth.sin(now / 320f + phase * 4.1f);        // twinkle
            int a = (int) (maxA * tw * 0.7f);
            if (a < 12) continue;
            boolean left = (i & 1) == 0;
            int px = left ? sx - 9 - (i % 4) * 8 : sx + scrollW + 9 + (i % 4) * 8;
            float rise = ((now / (2200f + i * 130f)) + phase) % 1.0f;           // slow upward drift
            int py = bottom - 8 - (int) (rise * span);
            px += Math.round(Mth.sin(now / 900f + phase * 2.3f) * 3);           // gentle sway
            int c = (a << 24) | GOLD;
            if (i % 3 == 0) {                                                    // big 4-armed star
                g.fill(px - 3, py, px + 4, py + 1, c);
                g.fill(px, py - 3, px + 1, py + 4, c);
                g.fill(px - 1, py - 1, px + 2, py + 2, (Math.min(255, a + 60) << 24) | GOLD);
            } else {                                                             // small twinkle
                g.fill(px - 1, py, px + 2, py + 1, c);
                g.fill(px, py - 1, px + 1, py + 2, c);
            }
        }
    }

    /** Old-manuscript ink heading in the blackletter face, with a thin flourish rule. */
    private void drawInkTitle(GuiGraphics g, String text, int cx, int y) {
        Component styled = Component.literal(text).withStyle(s -> s.withFont(ANCIENT_FONT));
        int tw = this.font.width(styled);
        g.fill(cx - tw / 2 - 8, y + 12, cx + tw / 2 + 8, y + 13, 0x886E4A22);
        g.drawString(this.font, styled, cx - tw / 2, y, C_INK, false);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int max = Math.max(0, contentHeight - (viewBottom - viewTop));
        scroll = Mth.clamp(scroll - (int) (dy * 18), 0, max);
        return true;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Parent draws the airship art cover-scaled (its override skips vanilla's menu-blur pass).
        if (parent != null) parent.renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, this.width, this.height, 0x66000000);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
