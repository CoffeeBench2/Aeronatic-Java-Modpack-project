package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AdminSettingsScreen extends Screen {

    private final Screen parent;
    private EditBox serverIpBox;
    private EditBox adminUsernameBox;

    public AdminSettingsScreen(Screen parent) {
        super(Component.literal("Admin Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;

        serverIpBox = new EditBox(this.font, cx - 100, 90, 200, 20, Component.literal("Server IP"));
        serverIpBox.setMaxLength(256);
        serverIpBox.setValue(AeroConfig.SERVER_IP.get());
        this.addRenderableWidget(serverIpBox);

        adminUsernameBox = new EditBox(this.font, cx - 100, 140, 200, 20, Component.literal("Admin Username"));
        adminUsernameBox.setMaxLength(64);
        adminUsernameBox.setValue(AeroConfig.ADMIN_USERNAME.get());
        this.addRenderableWidget(adminUsernameBox);

        this.addRenderableWidget(Button.builder(Component.literal("Save"), btn -> {
            AeroConfig.SERVER_IP.set(serverIpBox.getValue().trim());
            AeroConfig.ADMIN_USERNAME.set(adminUsernameBox.getValue().trim());
            AeroConfig.CLIENT_SPEC.save();
            Minecraft.getInstance().setScreen(parent);
        }).bounds(cx - 100, 175, 97, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
            Minecraft.getInstance().setScreen(parent);
        }).bounds(cx + 3, 175, 97, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 30, 0xFFFFFF);
        graphics.drawString(this.font, "Server IP:", this.width / 2 - 100, 75, 0xAAAAAA, false);
        graphics.drawString(this.font, "Admin Username:", this.width / 2 - 100, 125, 0xAAAAAA, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}