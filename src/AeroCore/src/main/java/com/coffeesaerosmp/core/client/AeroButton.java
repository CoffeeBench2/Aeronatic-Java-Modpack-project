package com.coffeesaerosmp.core.client;

import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Steampunk-styled button for the pack's screens: dark oak panel with a brass frame that lights up
 * on hover, plus a small Create cogwheel to the left of the label that spins while hovered.
 * (Style survey: Create-mod GUIs use warm browns + brass #C9973B accents; FancyMenu-era menus used
 * flat dark panels. This keeps vanilla button metrics so layouts stay drop-in.)
 */
public class AeroButton extends Button {

    // Colors: panel gradient (dark oak), brass frame, hover brass.
    private static final int COL_TOP      = 0xFF4A331E;
    private static final int COL_BOTTOM   = 0xFF2E1F10;
    private static final int COL_TOP_HOV  = 0xFF5C4026;
    private static final int COL_BOT_HOV  = 0xFF3A2815;
    private static final int BRASS        = 0xFF9C7430;
    private static final int BRASS_HOV    = 0xFFF0C05A;
    private static final int COG_SIZE     = 12;

    /**
     * Optional square icon. When set the button renders as an icon tile — no label, no cogwheel —
     * because at the ~20px size these are used for, a glyph plus a spinning cog is unreadable mush.
     * The message is kept for narration and tooltips.
     */
    private net.minecraft.resources.ResourceLocation icon;
    private int iconSize;

    /**
     * Suppresses the cogwheel and centres the label. For narrow glyph buttons ("✎", arrows) the cog
     * competes with the glyph instead of decorating it.
     */
    private boolean plain;

    public AeroButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    public static Builder aero(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    /** Mirrors {@link Button.Builder} enough for the call sites we use. */
    public static class Builder {
        private final Component message;
        private final OnPress onPress;
        private int x, y, w = 150, h = 20;
        private net.minecraft.resources.ResourceLocation icon;
        private int iconSize;
        private boolean plain;

        Builder(Component message, OnPress onPress) { this.message = message; this.onPress = onPress; }

        public Builder bounds(int x, int y, int w, int h) {
            this.x = x; this.y = y; this.w = w; this.h = h; return this;
        }

        /**
         * Render as an icon tile. {@code size} must match the texture's pixel size so it blits 1:1 —
         * scaling a small texture up is what makes pixel art look smeared.
         */
        public Builder icon(net.minecraft.resources.ResourceLocation icon, int size) {
            this.icon = icon; this.iconSize = size; return this;
        }

        /** Label only, centred, no cogwheel — for narrow glyph buttons. */
        public Builder plain() { this.plain = true; return this; }

        public AeroButton build() {
            AeroButton b = new AeroButton(x, y, w, h, message, onPress);
            b.icon = this.icon;
            b.iconSize = this.iconSize;
            b.plain = this.plain;
            return b;
        }
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean hov = this.isHoveredOrFocused() && this.active;
        int x0 = getX(), y0 = getY(), x1 = x0 + width, y1 = y0 + height;

        // Panel + frame
        g.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF120B05);                       // outer dark rim
        g.fillGradient(x0, y0, x1, y1, hov ? COL_TOP_HOV : COL_TOP, hov ? COL_BOT_HOV : COL_BOTTOM);
        int brass = this.active ? (hov ? BRASS_HOV : BRASS) : 0xFF5A5A5A;
        g.fill(x0, y0, x1, y0 + 1, brass);                                        // brass frame
        g.fill(x0, y1 - 1, x1, y1, brass);
        g.fill(x0, y0, x0 + 1, y1, brass);
        g.fill(x1 - 1, y0, x1, y1, brass);

        // Icon tile: blit 1:1, centered, and stop. Rounding down keeps it on whole pixels so the
        // pixel art stays crisp instead of landing on a half-pixel and blurring.
        if (this.icon != null) {
            int ix = x0 + (width - this.iconSize) / 2;
            int iy = y0 + (height - this.iconSize) / 2;
            g.blit(this.icon, ix, iy, 0.0F, 0.0F,
                this.iconSize, this.iconSize, this.iconSize, this.iconSize);
            return;
        }

        // Label (centered, leaving room for the cog on the left)
        int textColor = this.active ? (hov ? 0xFFFFE6A8 : 0xFFEADCC3) : 0xFFA0A0A0;
        var font = Minecraft.getInstance().font;
        if (this.plain) {
            g.drawCenteredString(font, getMessage(), x0 + width / 2, y0 + (height - 8) / 2, textColor);
            return;
        }
        int cx = x0 + width / 2 + COG_SIZE / 2;
        g.drawCenteredString(font, getMessage(), cx, y0 + (height - 8) / 2, textColor);

        // Cogwheel icon left of the label — spins while hovered.
        int labelW = font.width(getMessage());
        int cogX = cx - labelW / 2 - COG_SIZE - 4;
        int cogY = y0 + (height - COG_SIZE) / 2;
        float angle = hov ? (Util.getMillis() % 3600L) / 10.0F : 0.0F;
        g.pose().pushPose();
        g.pose().translate(cogX + COG_SIZE / 2.0F, cogY + COG_SIZE / 2.0F, 0);
        g.pose().mulPose(Axis.ZP.rotationDegrees(angle));
        float s = COG_SIZE / (float) EarlyAssets.COG_W;
        g.pose().scale(s, s, 1.0F);
        g.blit(EarlyAssets.COG, -EarlyAssets.COG_W / 2, -EarlyAssets.COG_H / 2,
            0.0F, 0.0F, EarlyAssets.COG_W, EarlyAssets.COG_H, EarlyAssets.COG_W, EarlyAssets.COG_H);
        g.pose().popPose();
    }
}
