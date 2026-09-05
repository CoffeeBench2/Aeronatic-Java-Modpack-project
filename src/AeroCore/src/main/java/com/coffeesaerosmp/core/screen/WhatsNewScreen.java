package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.announce.AnnouncementData;
import com.coffeesaerosmp.core.announce.AnnouncementState;
import com.coffeesaerosmp.core.announce.NewsImages;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * "What's New" — a one-shot card shown over the title screen the first time a player launches after
 * the pack updates, then never again for that version.
 *
 * <p>Deliberately a summary, not a second News screen: the newest entry's tag, title, prose line and
 * the first few bullets, with a button through to the full scroll. Players who want detail press it;
 * players who do not get one click and are gone. Anything longer and it becomes a thing to dismiss
 * rather than a thing to read.
 *
 * <p><b>One time per update.</b> Gated on {@link AnnouncementState}, the same seen-state the menu
 * button's NEW badge uses, keyed by the latest entry's version string. Dismissing marks it seen, so
 * the popup and the badge cannot both nag about the same release — being told once is being told.
 * A player who never launches during a release simply gets the next one; there is no queue.
 *
 * <p>Same palette and the same {@code RenderType.text} blit as {@link AnnouncementsScreen} — Veil
 * makes {@code GuiGraphics.blit(ResourceLocation, …)} silently draw nothing, so the banner has to go
 * through the batched path or it renders as an empty gap. Do not "simplify" it.
 */
public class WhatsNewScreen extends Screen {

    // Palette — identical to AnnouncementsScreen so the two read as one feature.
    private static final int CARD_BG   = 0xF0191109;
    private static final int EDGE      = 0xFFC9973B;
    private static final int EDGE_SOFT = 0x55C9973B;
    private static final int INNER     = 0x22FFFFFF;
    private static final int TEXT      = 0xFFF2E8D5;
    private static final int TEXT_DIM  = 0xFF9A8F7E;
    private static final int TITLE     = 0xFFFFD24A;
    private static final int C_ADDED   = 0xFF57F287;
    private static final int C_FIXED   = 0xFFFFC44A;
    private static final int C_REMOVED = 0xFFED6A5E;

    private static final int PAD = 14;
    private static final int LINE = 11;
    private static final int CARD_W = 300;
    private static final int BANNER_MAX_H = 84;

    /** Bullets are a taste of the release, not the changelog. The rest is one click away. */
    private static final int MAX_BULLETS = 5;

    private final Screen parent;
    private final AnnouncementData.Entry entry;

    private WhatsNewScreen(Screen parent, AnnouncementData.Entry entry) {
        super(Component.literal("What's New"));
        this.parent = parent;
        this.entry = entry;
    }

    /**
     * Shows the popup if this player has not seen the newest entry yet.
     *
     * @return true when it took over the screen, so the caller knows not to do anything else
     */
    public static boolean showIfUnseen(net.minecraft.client.Minecraft mc, Screen parent) {
        try {
            if (!AnnouncementState.hasUnseen()) return false;
            AnnouncementData.Entry latest = AnnouncementData.latest();
            // A version-only entry has nothing worth interrupting someone for. The badge still
            // appears on the News button, which is the right weight for "something changed".
            if (latest == null || latest.isEmpty()) return false;
            mc.setScreen(new WhatsNewScreen(parent, latest));
            return true;
        } catch (Throwable t) {
            // A cosmetic popup must never be the reason the title screen fails to appear.
            return false;
        }
    }

    @Override
    protected void init() {
        int cardH = measure();
        int x = (this.width - CARD_W) / 2;
        int y = (this.height - cardH) / 2;
        int by = y + cardH - PAD - 20;
        int bw = (CARD_W - PAD * 2 - 8) / 2;

        addRenderableWidget(Button.builder(Component.literal("Read the full news"),
            b -> this.minecraft.setScreen(new AnnouncementsScreen(parent)))
            .bounds(x + PAD, by, bw, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Got it"), b -> this.onClose())
            .bounds(x + PAD + bw + 8, by, bw, 20).build());
    }

    /** Height of the card, measured from what is actually going to be drawn. */
    private int measure() { return body(null, 0, 0, false) + PAD + 20 + PAD; }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        this.renderBackground(g, mouseX, mouseY, partial);
        g.fill(0, 0, this.width, this.height, 0xC0000000);

        int cardH = measure();
        int x = (this.width - CARD_W) / 2;
        int y = (this.height - cardH) / 2;

        g.fill(x, y, x + CARD_W, y + cardH, CARD_BG);
        g.fill(x, y, x + CARD_W, y + 1, EDGE);
        g.fill(x, y + cardH - 1, x + CARD_W, y + cardH, EDGE_SOFT);
        g.fill(x, y, x + 1, y + cardH, EDGE_SOFT);
        g.fill(x + CARD_W - 1, y, x + CARD_W, y + cardH, EDGE_SOFT);
        // Accent stripe in the tag's colour, so the release type reads before any text does.
        g.fill(x, y, x + 3, y + cardH, tagColor(entry.tag()));

        body(g, x, y, true);
        super.render(g, mouseX, mouseY, partial);
    }

    /**
     * Draws the card contents when {@code draw}, and returns the height either way.
     *
     * <p>🔑 One method for both passes. An earlier version of the News screen had a separate
     * measure and paint that drifted apart; do not split this.
     */
    private int body(GuiGraphics g, int x, int y, boolean draw) {
        int innerX = x + PAD;
        int innerW = CARD_W - PAD * 2;
        int cy = y + PAD;

        // Header: "WHAT'S NEW" over the tag badge.
        if (draw) {
            g.drawString(this.font, Component.literal("WHAT'S NEW")
                .withStyle(s -> s.withColor(0xC9973B).withBold(true)), innerX, cy, 0xFFFFFF, false);
        }
        cy += LINE + 3;

        String tag = entry.tag() == null ? "" : entry.tag().trim();
        if (!tag.isBlank()) {
            if (draw) g.drawString(this.font, tag, innerX, cy, tagColor(tag), false);
            cy += LINE;
        }

        if (draw) {
            String head = entry.title() == null || entry.title().isBlank()
                ? ("Version " + entry.version()) : entry.title();
            g.drawString(this.font, Component.literal(head).withStyle(s -> s.withBold(true)),
                innerX, cy, TITLE, false);
        }
        cy += LINE + 1;

        String sub = ("v" + entry.version() + (entry.date() == null || entry.date().isBlank()
            ? "" : "  ·  " + entry.date()));
        if (draw) g.drawString(this.font, sub, innerX, cy, TEXT_DIM, false);
        cy += LINE + 5;

        // Banner, if one has finished downloading. Absent or still loading simply costs no space,
        // which is why the card is measured every frame rather than reserving a hole.
        String banner = entry.banner();
        if (banner != null && !banner.isBlank()) {
            NewsImages.Tex tex = NewsImages.get(banner);
            if (tex != null) {
                int h = Math.min(BANNER_MAX_H, (int) (innerW * (tex.height() / (float) tex.width())));
                if (draw) {
                    blitTex(g, tex.id(), innerX, cy, innerW, h, 0, 0,
                        tex.width(), tex.height(), tex.width(), tex.height());
                }
                cy += h + 6;
            }
        }

        if (entry.body() != null && !entry.body().isBlank()) {
            for (var line : this.font.split(Component.literal(entry.body()), innerW)) {
                if (draw) g.drawString(this.font, line, innerX, cy, TEXT, false);
                cy += LINE;
            }
            cy += 4;
        }

        int shown = 0;
        int total = entry.added().size() + entry.fixed().size() + entry.removed().size();
        for (var group : List.of(
                new Object[]{entry.added(), "+", C_ADDED},
                new Object[]{entry.fixed(), "*", C_FIXED},
                new Object[]{entry.removed(), "-", C_REMOVED})) {
            @SuppressWarnings("unchecked") List<String> items = (List<String>) group[0];
            String mark = (String) group[1];
            int col = (Integer) group[2];
            for (String item : items) {
                if (shown >= MAX_BULLETS) break;
                for (var line : this.font.split(Component.literal(mark + " " + item), innerW - 4)) {
                    if (draw) g.drawString(this.font, line, innerX + 2, cy, col, false);
                    cy += LINE;
                }
                shown++;
            }
        }
        if (total > shown) {
            if (draw) {
                g.drawString(this.font, "…and " + (total - shown) + " more",
                    innerX + 2, cy, TEXT_DIM, false);
            }
            cy += LINE;
        }

        cy += 4;
        if (draw) g.fill(innerX, cy, innerX + innerW, cy + 1, INNER);
        cy += 5;

        return cy - y;
    }

    private static int tagColor(String tag) {
        if (tag == null) return EDGE;
        return switch (tag.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "MAJOR"  -> 0xFFFFD24A;
            case "HOTFIX" -> 0xFFED6A5E;
            case "SOON"   -> 0xFF9B8CFF;
            case "UPDATE" -> 0xFF57F287;
            default       -> EDGE;
        };
    }

    /** Batched-text blit. Veil breaks the plain GuiGraphics path; see the class javadoc. */
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

    /**
     * Dismissing counts as having been told. Marked here rather than in {@code init} so a player who
     * alt-F4s mid-popup still sees it next launch instead of silently losing the release note.
     */
    @Override
    public void onClose() {
        AnnouncementState.markLatestSeen();
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
