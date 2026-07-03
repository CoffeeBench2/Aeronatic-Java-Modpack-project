package com.coffeesaerosmp.core.client;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Swaps every vanilla {@link TitleScreen} for {@link AeroTitleScreen}. Admins can drop back to the
 * untouched vanilla menu ("◄ Vanilla Menu" on the custom screen); this class then adds a small
 * "► Custom" button to the vanilla menu so they can return.
 */
public final class TitleReplacer {

    private TitleReplacer() {}

    /** false while an admin is intentionally on the vanilla menu. */
    public static volatile boolean useCustom = true;

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!useCustom) return;
        if (event.getNewScreen() instanceof TitleScreen) {
            event.setNewScreen(new AeroTitleScreen());
        }
    }

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (useCustom || !(event.getScreen() instanceof TitleScreen screen)) return;
        boolean isAdmin = Minecraft.getInstance().getUser().getName()
            .equalsIgnoreCase(AeroConfig.ADMIN_USERNAME.get());
        if (!isAdmin) return;
        event.addListener(Button.builder(
                Component.literal("► Custom"),
                b -> {
                    useCustom = true;
                    Minecraft.getInstance().setScreen(new AeroTitleScreen());
                }
        ).bounds(5, screen.height - 25, 75, 20).build());
    }
}
