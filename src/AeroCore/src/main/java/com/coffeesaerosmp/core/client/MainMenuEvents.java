package com.coffeesaerosmp.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

public class MainMenuEvents {

    // Primary button manipulation is handled by TitleScreenMixin.
    @SubscribeEvent
    public static void onTitleScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        // Intentionally empty — button logic lives in TitleScreenMixin
        // to ensure deterministic ordering relative to vanilla widget init.
    }

    /**
     * Secret access combo: <b>Ctrl + Alt + M</b> on the title screen opens the vanilla
     * Multiplayer screen (server list + Direct Connection). Works from ANY account —
     * including a non-admin / offline test account that doesn't get the "Vanilla Menu"
     * button — so the dev can reach the server list to test-connect.
     */
    @SubscribeEvent
    public static void onTitleScreenKey(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        boolean ctrl = (event.getModifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean alt  = (event.getModifiers() & GLFW.GLFW_MOD_ALT) != 0;
        if (ctrl && alt && event.getKeyCode() == GLFW.GLFW_KEY_M) {
            Minecraft.getInstance().setScreen(new JoinMultiplayerScreen(event.getScreen()));
            event.setCanceled(true);
        }
    }
}
