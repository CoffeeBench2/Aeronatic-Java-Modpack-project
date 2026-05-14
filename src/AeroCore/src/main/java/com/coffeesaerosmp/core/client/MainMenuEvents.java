package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.screen.AdminSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class MainMenuEvents {

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen)) return;

        Minecraft mc = Minecraft.getInstance();
        String playerName = mc.getUser().getName();
        boolean isAdmin = playerName.equals(AeroConfig.CLIENT.adminUsername.get());

        List<AbstractWidget> toRemove = new ArrayList<>();
        AbstractWidget multiplayerButton = null;

        for (var listener : event.getListenersList()) {
            if (!(listener instanceof AbstractWidget widget)) continue;
            String msg = widget.getMessage().getString();

            switch (msg) {
                case "Singleplayer" -> {
                    if (!isAdmin) toRemove.add(widget);
                }
                case "Multiplayer", "Multiplayer (disabled)" -> {
                    multiplayerButton = widget;
                    toRemove.add(widget);
                }
            }
        }

        toRemove.forEach(event.getListenersList()::remove);

        if (multiplayerButton != null) {
            final int x = multiplayerButton.getX();
            final int y = multiplayerButton.getY();
            final int w = multiplayerButton.getWidth();
            final int h = multiplayerButton.getHeight();

            Button joinButton = Button.builder(
                Component.literal("§6▶ Join Coffees Aero SMP"),
                btn -> connectToServer(mc, screen)
            ).bounds(x, y, w, h).build();

            event.addListener(joinButton);

            if (isAdmin) {
                Button adminBtn = Button.builder(
                    Component.literal("§cAdmin Settings"),
                    btn -> mc.setScreen(new AdminSettingsScreen(screen))
                ).bounds(x, y + h + 4, w, h).build();

                event.addListener(adminBtn);
            }
        }
    }

    private static void connectToServer(Minecraft mc, TitleScreen parent) {
        String ip = AeroConfig.CLIENT.serverIP.get();
        ServerData serverData = new ServerData("Coffees Aero SMP", ip, ServerData.Type.OTHER);
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
            parent, mc, ServerAddress.parseString(ip), serverData, false
        );
    }
}
