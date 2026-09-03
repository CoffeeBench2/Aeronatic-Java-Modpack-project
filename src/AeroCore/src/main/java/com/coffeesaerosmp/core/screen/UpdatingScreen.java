package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.client.AeroButton;
import com.coffeesaerosmp.core.client.EarlyAssets;
import com.coffeesaerosmp.core.update.InClientUpdater;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * In-game progress screen for the one-click update. Shows the download progress live (no external
 * console / packwiz window). When the staged update is ready it closes the game so the windowless
 * applier can swap the files; on "already up to date" or an error it offers a Back button.
 *
 * <p><b>This class is presentation only.</b> Every value it draws is read straight off
 * {@link InClientUpdater}'s public fields and nothing here writes to them, drives them, or changes
 * when they are read. The 2026-09-03 restyle deliberately rewrote {@code render()} and the Back
 * button and touched nothing else: the constructor still calls {@code start()}, {@code tick()} still
 * has the same close/Back branches with the same 60-tick delay, and {@code shouldCloseOnEsc()} is
 * still false so Esc cannot bail out mid-update. A broken updater is unrecoverable for players who
 * have already staged files, so cosmetic work here must stay strictly cosmetic.
 */
public class UpdatingScreen extends Screen {

    // ── palette (matches AeroButton's brass-on-dark-oak styling) ────────────────
    private static final int PANEL_BG     = 0xE6120D08;
    private static final int PANEL_EDGE   = 0xFFC9973B;   // brass
    private static final int PANEL_INNER  = 0x33FFFFFF;
    private static final int TRACK        = 0xFF241E17;
    private static final int TRACK_EDGE   = 0xFF0A0705;
    private static final int FILL         = 0xFF57F287;
    private static final int FILL_TOP     = 0xFFA9F5C6;   // highlight row, reads as a rounded bar
    private static final int SHIMMER      = 0x40FFFFFF;
    private static final int TEXT         = 0xFFF2E8D5;
    private static final int TEXT_DIM     = 0xFF9A8F7E;
    private static final int OK           = 0xFF57F287;
    private static final int ERR          = 0xFFED4245;

    private static final int CARD_W = 320, CARD_H = 150;
    private static final int COG    = 18;

    private final Screen parent;
    private boolean endButtonAdded = false;
    private int     closeIn        = -1;

    public UpdatingScreen(Screen parent) {
        super(Component.literal("Updating Coffees Aero SMP"));
        this.parent = parent;
        InClientUpdater.start();
    }

    @Override
    protected void init() {
        if (endButtonAdded && closeIn < 0) addBack();   // keep the button on resize
    }

    private void addBack() {
        this.clearWidgets();
        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Back"),
                b -> Minecraft.getInstance().setScreen(parent))
            .bounds(this.width / 2 - 70, cardTop() + CARD_H - 32, 140, 20).build());
    }

    @Override
    public void tick() {
        if (InClientUpdater.finished && !endButtonAdded) {
            endButtonAdded = true;
            if (InClientUpdater.success && InClientUpdater.willClose) {
                closeIn = 60;                 // staged + applier launched → close to apply
            } else {
                addBack();                    // up-to-date or error → let the player return
            }
        }
        if (closeIn > 0 && --closeIn == 0) Minecraft.getInstance().stop();
    }

    private int cardTop() { return this.height / 2 - CARD_H / 2; }

    /** Our own background — overriding this is what keeps vanilla's menu BLUR pass away. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        float scale = Math.max(this.width / (float) EarlyAssets.BG_W, this.height / (float) EarlyAssets.BG_H);
        int drawW = (int) (EarlyAssets.BG_W * scale);
        int drawH = (int) (EarlyAssets.BG_H * scale);
        g.pose().pushPose();
        g.pose().translate((this.width - drawW) / 2.0F, (this.height - drawH) / 2.0F, 0);
        g.pose().scale(scale, scale, 1.0F);
        g.blit(EarlyAssets.BG, 0, 0, 0.0F, 0.0F,
            EarlyAssets.BG_W, EarlyAssets.BG_H, EarlyAssets.BG_W, EarlyAssets.BG_H);
        g.pose().popPose();
        // Heavier scrim than the title screen: this is a focused modal, the art is only atmosphere.
        g.fill(0, 0, this.width, this.height, 0xA0000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        int cx = this.width / 2;
        int x0 = cx - CARD_W / 2, y0 = cardTop();
        int x1 = x0 + CARD_W,     y1 = y0 + CARD_H;

        // Card: soft drop shadow, dark body, brass frame, inner highlight.
        g.fill(x0 + 3, y0 + 3, x1 + 3, y1 + 3, 0x50000000);
        g.fill(x0, y0, x1, y1, PANEL_BG);
        g.fill(x0, y0, x1, y0 + 1, PANEL_EDGE);
        g.fill(x0, y1 - 1, x1, y1, PANEL_EDGE);
        g.fill(x0, y0, x0 + 1, y1, PANEL_EDGE);
        g.fill(x1 - 1, y0, x1, y1, PANEL_EDGE);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, PANEL_INNER);

        boolean done   = InClientUpdater.finished;
        boolean active = !done || (InClientUpdater.success && InClientUpdater.willClose);

        // Header: spinning cog + title, left-aligned so it reads as a dialog rather than a splash.
        int hx = x0 + 14, hy = y0 + 14;
        if (!done) drawSpinningCog(g, hx, hy);
        g.drawString(this.font, Component.literal("Updating Coffees Aero SMP")
            .withStyle(s -> s.withColor(0xC9973B).withBold(true)), hx + COG + 8, hy + 5, 0xFFFFFF, false);

        if (active) {
            int total = Math.max(InClientUpdater.total, 1);
            int frac  = Math.min(InClientUpdater.done, total);
            float pct = frac / (float) total;

            int barX = x0 + 14, barW = CARD_W - 28, barY = y0 + 56, barH = 12;

            // Track, inset by a pixel so the fill sits inside a well rather than floating on the card.
            g.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, TRACK_EDGE);
            g.fill(barX, barY, barX + barW, barY + barH, TRACK);

            int fillW = (int) (barW * pct);
            if (fillW > 0) {
                g.fill(barX, barY, barX + fillW, barY + barH, FILL);
                g.fill(barX, barY, barX + fillW, barY + 2, FILL_TOP);   // top highlight = rounded look
                // Shimmer sweeping the filled region: the only thing on screen that proves the
                // download is alive when a single large jar sits at the same percentage for a while.
                g.enableScissor(barX, barY, barX + fillW, barY + barH);
                int sweep = (int) ((Util.getMillis() / 6L) % (barW + 80L)) - 40;
                g.fill(barX + sweep, barY, barX + sweep + 28, barY + barH, SHIMMER);
                g.disableScissor();
            }

            // Percentage right-aligned over the bar, counter on the left.
            String pctText = Math.round(pct * 100) + "%";
            g.drawString(this.font, pctText, barX + barW - this.font.width(pctText), barY - 12, TEXT, false);
            if (InClientUpdater.total > 0) {
                g.drawString(this.font, frac + " / " + InClientUpdater.total, barX, barY - 12, TEXT_DIM, false);
            }

            g.drawString(this.font, InClientUpdater.phase, barX, barY + barH + 8, TEXT, false);
            // The current-file line and the finished message share this slot, so only one of them
            // may ever draw. They used to overlap on the willClose path, where `active` is still
            // true and `current` keeps the last filename it saw.
            if (!done && !InClientUpdater.current.isBlank()) {
                // Mod filenames routinely overflow the card; truncate rather than letting them
                // run under the frame.
                g.drawString(this.font, ellipsize(InClientUpdater.current, barW),
                    barX, barY + barH + 20, TEXT_DIM, false);
            }
        }

        if (done) {
            String msg; int col;
            if (!InClientUpdater.success) {
                msg = "Update failed: " + InClientUpdater.error; col = ERR;
            } else if (InClientUpdater.willClose) {
                msg = "Downloaded — closing to apply. Relaunch when it reopens."; col = OK;
            } else {
                msg = "Already up to date."; col = OK;
            }
            java.util.List<String> lines = wrap(msg, CARD_W - 44);
            int my = active ? y0 + 88 : y0 + 60;                     // below the bar, or higher if no bar
            g.fill(x0 + 14, my - 3, x0 + 16, my + lines.size() * 11, col);   // accent bar, sized to the text
            for (String line : lines) {
                g.drawString(this.font, line, x0 + 22, my, col, false);
                my += 11;
            }
        }
    }

    private void drawSpinningCog(GuiGraphics g, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x + COG / 2.0F, y + COG / 2.0F, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees((Util.getMillis() % 3600L) / 10.0F));
        float s = COG / (float) EarlyAssets.COG_W;
        g.pose().scale(s, s, 1.0F);
        g.blit(EarlyAssets.COG, -EarlyAssets.COG_W / 2, -EarlyAssets.COG_H / 2,
            0.0F, 0.0F, EarlyAssets.COG_W, EarlyAssets.COG_H, EarlyAssets.COG_W, EarlyAssets.COG_H);
        g.pose().popPose();
    }

    private String ellipsize(String s, int maxW) {
        if (this.font.width(s) <= maxW) return s;
        return this.font.plainSubstrByWidth(s, maxW - this.font.width("...")) + "...";
    }

    private java.util.List<String> wrap(String s, int maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            // A single token wider than the card (a bare filename or URL in an error) can never be
            // wrapped, so cut it rather than letting it run past the frame.
            if (this.font.width(word) > maxW) word = ellipsize(word, maxW);
            String probe = line.isEmpty() ? word : line + " " + word;
            if (this.font.width(probe) > maxW && !line.isEmpty()) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(probe);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }   // don't let Esc bail mid-update
}
