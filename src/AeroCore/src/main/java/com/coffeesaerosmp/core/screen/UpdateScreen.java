package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.client.AeroButton;
import com.coffeesaerosmp.core.client.EarlyAssets;
import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.version.VersionCheck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown when the player's pack is out of date (clicking "Join" on a stale pack, or a registry kick).
 * "Update Now" hands off to {@link UpdatingScreen}, which downloads the changed files in-client (with a
 * progress bar) and applies them via a windowless helper after the game closes — no console, no link.
 *
 * <p><b>Presentation only.</b> Restyled 2026-09-03 to match {@link UpdatingScreen}, so the handoff
 * between the two does not jump from a polished card back to a vanilla dirt background. The button
 * actions, {@code onClose()} and the values read from {@link VersionCheck} / {@link AeroConfig} are
 * unchanged.
 */
public class UpdateScreen extends Screen {

    private static final int PANEL_BG    = 0xE6120D08;
    private static final int PANEL_EDGE  = 0xFFC9973B;
    private static final int PANEL_INNER = 0x33FFFFFF;
    private static final int TEXT        = 0xFFF2E8D5;
    private static final int TEXT_DIM    = 0xFF9A8F7E;
    private static final int WARN        = 0xFFFFD24A;

    private static final int CARD_W = 320, CARD_H = 150;

    private final Screen parent;

    public UpdateScreen(Screen parent) {
        super(Component.literal("Pack Update Required"));
        this.parent = parent;
    }

    private int cardTop() { return this.height / 2 - CARD_H / 2; }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y  = cardTop() + CARD_H - 54;

        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Update Now"),
                b -> Minecraft.getInstance().setScreen(new UpdatingScreen(parent)))
            .bounds(cx - 140, y, 280, 20).build());

        this.addRenderableWidget(AeroButton.aero(
                Component.literal("Back"),
                b -> Minecraft.getInstance().setScreen(parent))
            .bounds(cx - 140, y + 24, 280, 20).build());
    }

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
        g.fill(0, 0, this.width, this.height, 0xA0000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);

        int cx = this.width / 2;
        int x0 = cx - CARD_W / 2, y0 = cardTop();
        int x1 = x0 + CARD_W,     y1 = y0 + CARD_H;

        g.fill(x0 + 3, y0 + 3, x1 + 3, y1 + 3, 0x50000000);
        g.fill(x0, y0, x1, y1, PANEL_BG);
        g.fill(x0, y0, x1, y0 + 1, PANEL_EDGE);
        g.fill(x0, y1 - 1, x1, y1, PANEL_EDGE);
        g.fill(x0, y0, x0 + 1, y1, PANEL_EDGE);
        g.fill(x1 - 1, y0, x1, y1, PANEL_EDGE);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, PANEL_INNER);

        String latest = VersionCheck.latestVersion();
        String ver    = (latest == null || latest.isBlank()) ? "the latest version" : "v" + latest;

        int tx = x0 + 16, ty = y0 + 16;
        g.fill(tx, ty, tx + 2, ty + 20, WARN);                       // accent bar
        g.drawString(this.font, Component.literal("Your pack is out of date")
            .withStyle(s -> s.withColor(0xFFD24A).withBold(true)), tx + 8, ty + 1, 0xFFFFFF, false);
        g.drawString(this.font, "Update to " + ver + " to keep playing.", tx + 8, ty + 13, TEXT, false);

        g.fill(x0 + 14, y0 + 48, x1 - 14, y0 + 49, 0x22FFFFFF);      // divider

        g.drawString(this.font, "Installed", tx, y0 + 58, TEXT_DIM, false);
        g.drawString(this.font, "v" + AeroConfig.PACK_VERSION.get(),
            x1 - 16 - this.font.width("v" + AeroConfig.PACK_VERSION.get()), y0 + 58, TEXT, false);
        g.drawString(this.font, "Available", tx, y0 + 70, TEXT_DIM, false);
        g.drawString(this.font, ver, x1 - 16 - this.font.width(ver), y0 + 70, WARN, false);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
