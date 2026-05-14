package com.coffeesaerosmp.core.mixin;

import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Placeholder mixin — actual menu modification is handled by MainMenuEvents
 * via ScreenEvent.Init.Post. This mixin exists to satisfy the mixin config
 * and can be extended for deeper TitleScreen access if needed.
 */
@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        // Main menu changes are applied in MainMenuEvents.onScreenInit
    }
}
