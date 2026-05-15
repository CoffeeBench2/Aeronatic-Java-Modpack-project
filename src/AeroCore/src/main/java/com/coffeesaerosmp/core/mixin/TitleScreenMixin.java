package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.screen.AdminSettingsScreen;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.callback.CallbackInfo;

import java.util.ArrayList;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        Button[] mpSlot = {null};

        new ArrayList<>(this.children()).forEach(child -> {
            if (!(child instanceof Button btn)) return;
            String key = btn.getMessage().getString();
            if (key.equals(Component.translatable("menu.singleplayer").getString())) {
                this.removeWidget(btn);
            } else if (key.equals(Component.translatable("menu.multiplayer").getString())) {
                mpSlot[0] = btn;
            }
        });

        if (mpSlot[0] != null) {
            Button old = mpSlot[0];
            this.removeWidget(old);
            this.addRenderableWidget(Button.builder(
                Component.literal("Join Coffees Aero SMP"),
                b -> connectToServer()
            ).bounds(old.getX(), old.getY(), old.getWidth(), old.getHeight()).build());
        }

        String username = Minecraft.getInstance().getUser().getName();
        if (username.equalsIgnoreCase(AeroConfig.ADMIN_USERNAME.get())) {
            this.addRenderableWidget(Button.builder(
                Component.literal("Admin Settings"),
                b -> Minecraft.getInstance().setScreen(new AdminSettingsScreen(this))
            ).bounds(this.width / 2 + 4, this.height / 4 + 48 + 29, 96, 20).build());
        }
    }

    private void connectToServer() {
        Minecraft mc = Minecraft.getInstance();
        String ip = AeroConfig.SERVER_IP.get();
        ServerAddress address = ServerAddress.parseString(ip);
        ServerData data = new ServerData("Coffees Aero SMP", ip, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(this, mc, address, data, false, null);
    }
}