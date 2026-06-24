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
 * Shown when the player's pack is out of date — either because they clicked "Join" on a stale pack
 * (blocked up front by {@link com.coffeesaerosmp.core.mixin.TitleScreenMixin}) or because they were
 * registry-kicked. Replaces the cryptic mod/registry wall with a clear instruction to re-import.
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
                Component.literal("Copy download link"),
                b -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(VersionCheck.downloadUrl());
                    b.setMessage(Component.literal("Link copied!").withStyle(ChatFormatting.GREEN));
                })
            .bounds(cx - 154, y, 150, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Back"),
                b -> Minecraft.getInstance().setScreen(parent))
            .bounds(cx + 4, y, 150, 20).build());
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
            Component.literal("Re-import " + ver + " to join.").withStyle(ChatFormatting.WHITE),
            cx, y + 16, 0xFFFFFF);
        g.drawCenteredString(this.font,
            Component.literal("Current bundled version: v" + AeroConfig.PACK_VERSION.get()).withStyle(ChatFormatting.GRAY),
            cx, y + 36, 0xFFFFFF);
        g.drawCenteredString(this.font,
            Component.literal(VersionCheck.downloadUrl()).withStyle(ChatFormatting.AQUA),
            cx, y + 56, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
