package com.coffeesaerosmp.voicecaptions.client;

import com.coffeesaerosmp.voicecaptions.CoffeesAeroVoiceCaptions;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * "Toggle Voice Captions" keybind — lives in Options → Controls (the vanilla settings menu), so the
 * off switch is discoverable where players already look for toggles. Unbound by default (no key stolen);
 * bind it once to get a hotkey. Captions are ON by default; toggling persists via {@link CaptionSettings}.
 */
@EventBusSubscriber(modid = CoffeesAeroVoiceCaptions.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CaptionKeybinds {

    private CaptionKeybinds() {}

    public static final KeyMapping TOGGLE = new KeyMapping(
        "key.coffeesaerovoicecaptions.toggle",       // Options → Controls label (lang)
        KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_UNKNOWN,                        // unbound by default
        "key.categories.coffeesaerovoicecaptions");  // category header

    @SubscribeEvent
    static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        // Game-bus tick handler for consuming presses — registered here so it's client-only.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(TickHandler.class);
    }

    public static final class TickHandler {
        private TickHandler() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            while (TOGGLE.consumeClick()) {
                boolean on = CaptionSettings.toggle();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(
                        on ? "§a💬 Voice captions ON" : "§7💬 Voice captions OFF"), true);
                }
            }
        }
    }
}
