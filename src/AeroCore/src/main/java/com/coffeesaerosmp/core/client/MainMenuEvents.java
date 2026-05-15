package com.coffeesaerosmp.core.client;

import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.minecraft.client.gui.screens.TitleScreen;

public class MainMenuEvents {

    // Primary button manipulation is handled by TitleScreenMixin.
    // This class provides a secondary NeoForge event hook for future extensions.
    @SubscribeEvent
    public static void onTitleScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen)) return;
        // Intentionally empty — button logic lives in TitleScreenMixin
        // to ensure deterministic ordering relative to vanilla widget init.
    }
}