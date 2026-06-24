package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.version.VersionCheck;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shown when the player's pack is out of date (clicking "Join" on a stale pack, or a registry kick).
 * "Update Now" hands off to {@link UpdatingScreen}, which downloads the changed files in-client (with a
 * progress bar) and applies them via a windowless helper after the game closes — no console, no link.
 */
public class UpdateScreen extends Screen {

    private final Screen parent;

    public UpdateScreen(Screen parent) {
        super(Component.literal("Pack Update Required"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y  = this.height / 2 + 24;

        this.addRenderableWidget(Button.builder(
                Component.literal("Update Now").withStyle(ChatFormatting.GREEN),
                b -> Minecraft.getInstance().setScreen(new UpdatingScreen(parent)))
            .bounds(cx - 100, y, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Back"),
                b -> Minecraft.getInstance().setScreen(parent))
            .bounds(cx - 100, y + 24, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = this.width / 2;
        int y  = this.height / 2 - 60;

        String latest = VersionCheck.latestVersion();
        String ver    = (latest == null || latest.isBlank()) ? "the latest version" : "v" + latest;

        g.drawCenteredString(this.font,
            Component.literal("Your Coffees Aero SMP pack is outdated").withStyle(ChatFormatting.YELLOW),
            cx, y, 0xFFFFFF);
        g.drawCenteredString(this.font,
            Component.literal("Click \"Update Now\" to download " + ver + " and relaunch.").withStyle(ChatFormatting.WHITE),
            cx, y + 16, 0xFFFFFF);
        g.drawCenteredString(this.font,
            Component.literal("Current bundled version: v" + AeroConfig.PACK_VERSION.get()).withStyle(ChatFormatting.GRAY),
            cx, y + 36, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
