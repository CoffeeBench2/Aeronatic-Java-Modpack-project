package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

public class AdminSettingsScreen extends Screen {

    private final Screen parent;
    private EditBox serverIPField;
    private EditBox adminUsernameField;

    public AdminSettingsScreen(Screen parent) {
        super(Component.literal("Coffees Aero SMP — Admin Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;
        int startY = this.height / 2 - 60;

        this.serverIPField = new EditBox(
            this.font,
            centerX - 150, startY,
            300, 20,
            Component.literal("Server IP")
        );
        this.serverIPField.setMaxLength(255);
        this.serverIPField.setValue(AeroConfig.CLIENT.serverIP.get());
        this.serverIPField.setHint(Component.literal("e.g. play.coffeesaerosmp.net or 123.456.7.8:25565"));
        this.addRenderableWidget(this.serverIPField);

        this.adminUsernameField = new EditBox(
            this.font,
            centerX - 150, startY + 40,
            300, 20,
            Component.literal("Admin Username")
        );
        this.adminUsernameField.setMaxLength(16);
        this.adminUsernameField.setValue(AeroConfig.CLIENT.adminUsername.get());
        this.adminUsernameField.setHint(Component.literal("Your exact Minecraft username"));
        this.addRenderableWidget(this.adminUsernameField);

        this.addRenderableWidget(Button.builder(
            Component.literal("§aSave & Return"),
            btn -> saveAndReturn()
        ).bounds(centerX - 80, startY + 80, 160, 20).build());

        this.addRenderableWidget(Button.builder(
            Component.literal("Cancel"),
            btn -> this.minecraft.setScreen(parent)
        ).bounds(centerX - 80, startY + 106, 160, 20).build());
    }

    private void saveAndReturn() {
        String newIP = this.serverIPField.getValue().trim();
        String newAdmin = this.adminUsernameField.getValue().trim();

        if (!newIP.isEmpty()) {
            AeroConfig.CLIENT.serverIP.set(newIP);
        }
        if (!newAdmin.isEmpty()) {
            AeroConfig.CLIENT.adminUsername.set(newAdmin);
        }

        AeroConfig.CLIENT_SPEC.save();
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int startY = this.height / 2 - 60;

        graphics.drawCenteredString(this.font,
            Component.literal("§6§lCoffees Aero SMP Admin Settings"),
            centerX, startY - 24, 0xFFFFFF);

        graphics.drawString(this.font,
            Component.literal("§7Server IP:"),
            centerX - 150, startY - 10, 0xAAAAAA);

        graphics.drawString(this.font,
            Component.literal("§7Admin Username:"),
            centerX - 150, startY + 30, 0xAAAAAA);

        graphics.drawString(this.font,
            Component.literal("§8Changes take effect on next launch."),
            centerX - 150, startY + 130, 0x888888);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
