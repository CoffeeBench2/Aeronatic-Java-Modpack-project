package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.announce.AnnouncementData;
import com.coffeesaerosmp.core.announce.AnnouncementState;
import com.coffeesaerosmp.core.announce.NewsImages;
import com.coffeesaerosmp.core.client.AeroButton;
import com.coffeesaerosmp.core.client.EarlyAssets;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The News screen: one card per release, scrolling, with pictures.
 *
 * <p>Replaces the pixel-art scroll. That design sized the parchment to its content and inset the text
 * column by 18% a side, which left roughly 200px to write in — fine for four bullet points, and the
 * reason longer entries stopped reading properly once releases got bigger. This lays the same data
 * out as cards in a column up to 420px wide, so a real paragraph fits, and it matches the update
 * screens rather than being the one ornate outlier in the menu.
 *
 * <p><b>Veil rendering trap, inherited and still load-bearing.</b> The pack ships Veil (jar-in-jar
 * inside ldlib2), and under it {@code GuiGraphics.blit(ResourceLocation,…)} — the immediate
 * Tesselator path — can silently draw nothing until a window resize rebinds targets, while batched
 * text always renders. So every texture here goes through {@link #blitTex}, which submits quads to
 * the same batched {@code RenderType.text} pipeline the font uses. Do not "simplify" it back to
 * {@code blit}. Never call {@code GuiGraphics.flush()} either: it force-enables depth test for the
 * rest of the frame.
 *
 * <p>Pictures come from {@link NewsImages}, which returns null until an image is ready. Layout is
 * measured every frame from what is actually loaded, so a card grows when its picture arrives rather
 * than reserving a hole that may never fill.
 */
public class AnnouncementsScreen extends Screen {

    // Palette — same brass-on-dark-oak language as the update screens.
    private static final int CARD_BG     = 0xF0191109;
    private static final int EDGE        = 0xFFC9973B;
    private static final int EDGE_SOFT   = 0x55C9973B;
    private static final int INNER       = 0x22FFFFFF;
    private static final int TEXT        = 0xFFF2E8D5;
    private static final int TEXT_DIM    = 0xFF9A8F7E;
    private static final int TITLE       = 0xFFFFD24A;
    private static final int C_ADDED     = 0xFF57F287;
    private static final int C_FIXED     = 0xFFFFC44A;
    private static final int C_REMOVED   = 0xFFED6A5E;
    private static final int LINK        = 0xFF6EC7FF;

    private static final int PAD = 12;          // inside a card
    private static final int GAP = 10;          // between cards
    private static final int LINE = 11;         // text line height

    private final Screen parent;

    private List<AnnouncementData.Entry> entries = List.of();
    private int colX, colW, viewTop, viewBottom;
    private int scroll = 0, contentH = 0;

    /** Link hitboxes rebuilt each frame, so a click can be matched to whatever was drawn. */
    private record Hit(int x, int y, int w, int h, String url) {}
    private final List<Hit> hits = new ArrayList<>();

    public AnnouncementsScreen(Screen parent) {
        super(Component.literal("News"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AnnouncementState.markLatestSeen();
        entries = AnnouncementData.entries();

        colW = Math.min(420, this.width - 60);
        colX = (this.width - colW) / 2;
        viewTop = 52;
        viewBottom = this.height - 40;

        this.addRenderableWidget(AeroButton.aero(Component.literal("Close"),
            b -> this.minecraft.setScreen(parent))
            .bounds(this.width / 2 - 70, this.height - 30, 140, 20).build());
    }

    /** Our own background — overriding this keeps vanilla's menu blur pass away. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        float sc = Math.max(this.width / (float) EarlyAssets.BG_W, this.height / (float) EarlyAssets.BG_H);
        int dw = (int) (EarlyAssets.BG_W * sc), dh = (int) (EarlyAssets.BG_H * sc);
        g.pose().pushPose();
        g.pose().translate((this.width - dw) / 2.0F, (this.height - dh) / 2.0F, 0);
        g.pose().scale(sc, sc, 1.0F);
        blitTex(g, EarlyAssets.BG, 0, 0, EarlyAssets.BG_W, EarlyAssets.BG_H,
            0, 0, EarlyAssets.BG_W, EarlyAssets.BG_H, EarlyAssets.BG_W, EarlyAssets.BG_H);
        g.pose().popPose();
        g.fill(0, 0, this.width, this.height, 0xC0000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        hits.clear();

        g.drawString(this.font, Component.literal("News").withStyle(s -> s.withColor(0xC9973B).withBold(true)),
            colX, 24, 0xFFFFFF, false);
        String sub = entries.isEmpty() ? "Nothing to report yet."
            : entries.size() + (entries.size() == 1 ? " entry" : " entries");
        g.drawString(this.font, sub, colX + this.font.width("News") + 8, 24, TEXT_DIM, false);

        // Clip to the viewport so cards cannot paint over the header or the Close button.
        g.enableScissor(0, viewTop, this.width, viewBottom);
        int y = viewTop + 4 - scroll;
        for (AnnouncementData.Entry e : entries) {
            int h = card(g, e, colX, y, colW, mouseX, mouseY);
            y += h + GAP;
        }
        g.disableScissor();
        contentH = (y + scroll) - (viewTop + 4);

        // Scrollbar, only when there is somewhere to scroll to.
        int overflow = contentH - (viewBottom - viewTop);
        if (overflow > 0) {
            int trackX = colX + colW + 6, trackH = viewBottom - viewTop;
            int thumbH = Math.max(20, (int) (trackH * (trackH / (float) contentH)));
            int thumbY = viewTop + (int) ((trackH - thumbH) * (scroll / (float) overflow));
            g.fill(trackX, viewTop, trackX + 3, viewBottom, 0x40FFFFFF);
            g.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, EDGE);
        }
    }

    /**
     * Draws one card and returns its height.
     *
     * <p>The frame has to be painted before the content but its height is only known after the
     * content has been laid out, so this runs {@link #body} twice: once with {@code draw=false} to
     * measure, then the frame, then again to paint. One implementation, measured and painted by the
     * same code — the moment those are two methods they drift, and a card whose measured height
     * disagrees with its painted height makes scrolling subtly wrong in a way that is miserable to
     * chase. (It was two methods for about ten minutes. It had already drifted.)
     */
    private int card(GuiGraphics g, AnnouncementData.Entry e, int x, int y, int w,
                     int mouseX, int mouseY) {
        int h = body(g, e, x, y, w, mouseX, mouseY, false) + PAD - 4;

        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x40000000);
        g.fill(x, y, x + w, y + h, CARD_BG);
        g.fill(x, y, x + w, y + 1, EDGE);
        g.fill(x, y + h - 1, x + w, y + h, EDGE_SOFT);
        g.fill(x, y, x + 1, y + h, EDGE_SOFT);
        g.fill(x + w - 1, y, x + w, y + h, EDGE_SOFT);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, INNER);
        // Accent stripe keyed to the tag, so releases are scannable at a glance.
        g.fill(x, y, x + 2, y + h, tagColor(e.tag().toUpperCase(Locale.ROOT)));

        body(g, e, x, y, w, mouseX, mouseY, true);
        return h;
    }

    /**
     * Lays out a card's contents, drawing only when asked. Every branch advances {@code cy} whether
     * or not it draws, so the measure and paint passes cannot disagree.
     */
    private int body(GuiGraphics g, AnnouncementData.Entry e, int x, int y, int w,
                     int mouseX, int mouseY, boolean draw) {
        int cy = y + PAD, innerX = x + PAD, innerW = w - PAD * 2;

        String tag = e.tag().isBlank() ? "" : e.tag().toUpperCase(Locale.ROOT);
        int tx = innerX;
        if (!tag.isBlank()) {
            int tw = this.font.width(tag) + 8;
            if (draw) {
                g.fill(tx, cy - 1, tx + tw, cy + 10, (tagColor(tag) & 0x00FFFFFF) | 0x33000000);
                g.fill(tx, cy - 1, tx + 2, cy + 10, tagColor(tag));
                g.drawString(this.font, tag, tx + 5, cy + 1, tagColor(tag), false);
            }
            tx += tw + 6;
        }
        if (draw) {
            String vd = e.version() + (e.date().isBlank() ? "" : "  ·  " + e.date());
            g.drawString(this.font, vd, tx, cy + 1, TEXT_DIM, false);
        }
        cy += 14;

        if (!e.title().isBlank()) {
            for (FormattedCharSequence l : this.font.split(Component.literal(e.title()), innerW)) {
                if (draw) g.drawString(this.font, l, innerX, cy, TITLE, false);
                cy += LINE + 1;
            }
            cy += 2;
        }

        cy += picture(g, e.banner(), innerX, cy, innerW, 150, draw);

        if (!e.body().isBlank()) {
            for (FormattedCharSequence l : this.font.split(Component.literal(e.body()), innerW)) {
                if (draw) g.drawString(this.font, l, innerX, cy, TEXT, false);
                cy += LINE;
            }
            cy += 4;
        }

        cy += section(g, "Added",   e.added(),   C_ADDED,   innerX, cy, innerW, draw);
        cy += section(g, "Fixed",   e.fixed(),   C_FIXED,   innerX, cy, innerW, draw);
        cy += section(g, "Removed", e.removed(), C_REMOVED, innerX, cy, innerW, draw);

        if (!e.images().isEmpty()) {
            int thumbW = Math.min(120, (innerW - 12) / 3);
            int gx = innerX, rowH = 0;
            for (String url : e.images()) {
                if (gx + thumbW > innerX + innerW) { gx = innerX; cy += rowH + 4; rowH = 0; }
                rowH = Math.max(rowH, picture(g, url, gx, cy, thumbW, 90, draw));
                gx += thumbW + 4;
            }
            cy += rowH;
        }

        if (e.hasLink()) {
            String label = "→ " + e.linkLabel();
            int lw = this.font.width(label);
            boolean hot = mouseX >= innerX && mouseX <= innerX + lw && mouseY >= cy && mouseY <= cy + 9
                && mouseY >= viewTop && mouseY <= viewBottom;
            if (draw) {
                g.drawString(this.font, label, innerX, cy, hot ? 0xFFFFFFFF : LINK, false);
                if (hot) g.fill(innerX, cy + 9, innerX + lw, cy + 10, LINK);
                hits.add(new Hit(innerX, cy, lw, 10, e.linkUrl()));
            }
            cy += LINE + 2;
        }

        return cy - y;
    }

    /** A heading plus bullets. Returns the height used, 0 when the list is empty. */
    private int section(GuiGraphics g, String heading, List<String> items, int colour,
                        int x, int y, int w, boolean draw) {
        if (items.isEmpty()) return 0;
        int cy = y;
        if (draw) {
            g.drawString(this.font, heading, x, cy, colour, false);
            g.fill(x, cy + 9, x + w, cy + 10, (colour & 0x00FFFFFF) | 0x33000000);
        }
        cy += LINE + 3;
        for (String it : items) {
            boolean first = true;
            // Bullets hang: the marker sits in the gutter and wrapped lines align under the text,
            // which is what makes a long entry scannable instead of a wall.
            for (FormattedCharSequence l : this.font.split(Component.literal(it), w - 10)) {
                if (draw) {
                    if (first) g.fill(x + 1, cy + 3, x + 4, cy + 6, colour);
                    g.drawString(this.font, l, x + 10, cy, TEXT, false);
                }
                cy += LINE;
                first = false;
            }
        }
        return (cy - y) + 4;
    }

    /**
     * Draws a picture scaled to fit {@code maxW} x {@code maxH} with its aspect kept, and returns the
     * height it used. While the image is still downloading a slim placeholder holds its place; once
     * it has definitively failed, nothing is reserved at all.
     */
    private int picture(GuiGraphics g, String url, int x, int y, int maxW, int maxH, boolean draw) {
        if (url == null || url.isBlank()) return 0;
        NewsImages.Tex tex = NewsImages.get(url);
        if (tex == null) {
            if (NewsImages.failed(url)) return 0;
            if (draw) {
                g.fill(x, y, x + maxW, y + 18, 0x30FFFFFF);
                g.drawString(this.font, "loading picture…", x + 6, y + 5, TEXT_DIM, false);
            }
            return 22;
        }
        float scale = Math.min(maxW / (float) tex.width(), maxH / (float) tex.height());
        int dw = Math.max(1, Math.round(tex.width() * scale));
        int dh = Math.max(1, Math.round(tex.height() * scale));
        if (draw) {
            g.fill(x - 1, y - 1, x + dw + 1, y + dh + 1, EDGE_SOFT);
            blitTex(g, tex.id(), x, y, dw, dh, 0, 0, tex.width(), tex.height(), tex.width(), tex.height());
        }
        return dh + 6;
    }

    private static int tagColor(String tag) {
        return switch (tag) {
            case "MAJOR"  -> 0xFFFFD24A;
            case "HOTFIX" -> 0xFFED6A5E;
            case "SOON"   -> 0xFF9B8CFF;
            case "UPDATE" -> 0xFF57F287;
            default        -> EDGE;
        };
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        int overflow = Math.max(0, contentH - (viewBottom - viewTop));
        scroll = Mth.clamp(scroll - (int) (dy * 18), 0, overflow);
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Hit h : hits) {
                if (mx >= h.x() && mx <= h.x() + h.w() && my >= h.y() && my <= h.y() + h.h()
                        && my >= viewTop && my <= viewBottom) {
                    // Vanilla's confirm screen — never open a URL from a remote document silently.
                    this.minecraft.setScreen(new ConfirmLinkScreen(
                        ok -> { if (ok) Util.getPlatform().openUri(h.url()); this.minecraft.setScreen(this); },
                        h.url(), true));
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    /** See the class note: batched text pipeline, because Veil can swallow immediate blits. */
    private void blitTex(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h,
                         int uPx, int vPx, int uwPx, int vhPx, int texW, int texH) {
        float u0 = uPx / (float) texW, v0 = vPx / (float) texH;
        float u1 = (uPx + uwPx) / (float) texW, v1 = (vPx + vhPx) / (float) texH;
        Matrix4f m = g.pose().last().pose();
        MultiBufferSource.BufferSource buffers = this.minecraft.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.text(tex));
        int light = 0xF000F0;
        vc.addVertex(m, x,     y,     0).setColor(-1).setUv(u0, v0).setLight(light);
        vc.addVertex(m, x,     y + h, 0).setColor(-1).setUv(u0, v1).setLight(light);
        vc.addVertex(m, x + w, y + h, 0).setColor(-1).setUv(u1, v1).setLight(light);
        vc.addVertex(m, x + w, y,     0).setColor(-1).setUv(u1, v0).setLight(light);
        buffers.endBatch();
    }

    @Override
    public void onClose() { this.minecraft.setScreen(parent); }

    @Override
    public boolean isPauseScreen() { return false; }
}
