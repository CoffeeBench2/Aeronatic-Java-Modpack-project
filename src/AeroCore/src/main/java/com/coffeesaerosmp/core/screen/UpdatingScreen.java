package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.update.InClientUpdater;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * In-game progress screen for the one-click update. Shows the download progress live (no external
 * console / packwiz window). When the staged update is ready it closes the game so the windowless
 * applier can swap the files; on "already up to date" or an error it offers a Back button.
 */
public class UpdatingScreen extends Screen {

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
        this.addRenderableWidget(Button.builder(
                Component.literal("Back"),
                b -> Minecraft.getInstance().setScreen(parent))
            .bounds(this.width / 2 - 100, this.height / 2 + 50, 200, 20).build());
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = this.width / 2;
        int y  = this.height / 2 - 40;

        g.drawCenteredString(this.font,
            Component.literal("Updating Coffees Aero SMP").withStyle(ChatFormatting.GOLD), cx, y, 0xFFFFFF);

        boolean done = InClientUpdater.finished;
        if (!done || (InClientUpdater.success && InClientUpdater.willClose)) {
            // progress bar
            int total = Math.max(InClientUpdater.total, 1);
            int frac  = Math.min(InClientUpdater.done, total);
            int barW = 240, barX = cx - barW / 2, barY = y + 24;
            g.fill(barX - 1, barY - 1, barX + barW + 1, barY + 11, 0xFF000000);
            g.fill(barX, barY, barX + barW, barY + 10, 0xFF3A3A3A);
            g.fill(barX, barY, barX + (int) (barW * (frac / (float) total)), barY + 10, 0xFF57F287);
            g.drawCenteredString(this.font,
                Component.literal(InClientUpdater.phase).withStyle(ChatFormatting.WHITE), cx, barY + 16, 0xFFFFFF);
            if (!InClientUpdater.current.isBlank())
                g.drawCenteredString(this.font,
                    Component.literal(InClientUpdater.current).withStyle(ChatFormatting.GRAY), cx, barY + 30, 0xFFFFFF);
        }

        if (done) {
            String msg; ChatFormatting col;
            if (!InClientUpdater.success) { msg = "Update failed: " + InClientUpdater.error; col = ChatFormatting.RED; }
            else if (InClientUpdater.willClose) { msg = "Update downloaded — closing to apply. Relaunch when it reopens."; col = ChatFormatting.GREEN; }
            else { msg = "Already up to date."; col = ChatFormatting.GREEN; }
            g.drawCenteredString(this.font, Component.literal(msg).withStyle(col), cx, y + 24, 0xFFFFFF);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() { return false; }   // don't let Esc bail mid-update
}
