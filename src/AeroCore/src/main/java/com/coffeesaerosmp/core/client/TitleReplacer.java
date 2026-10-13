package com.coffeesaerosmp.core.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Swaps every vanilla {@link TitleScreen} for {@link AeroTitleScreen}. Any player can drop back to the
 * untouched vanilla menu ("Vanilla Menu", bottom-right of the custom screen); this class then adds an
 * "Aero Menu" button to the top-right of the vanilla menu so they can return.
 *
 * <p>Was admin-only and forgotten on restart until 1.4.0 (owner 2026-10-13: a corner toggle everyone
 * can see). The choice is remembered in its own file, NOT in {@code coffeesaerosmp_core-client.toml}:
 * that file is in the packwiz index, so the updater would put the shipped value back on every update.
 */
public final class TitleReplacer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroCore-Title");
    private static final String CHOICE_FILE = "coffeesaerosmp_title_menu.txt";

    private TitleReplacer() {}

    /** false while the player has chosen the vanilla menu. */
    public static volatile boolean useCustom = !"vanilla".equals(readChoice());

    /** Switch menus and remember the choice for the next launch. */
    public static void setUseCustom(boolean custom) {
        useCustom = custom;
        try {
            Files.writeString(FMLPaths.CONFIGDIR.get().resolve(CHOICE_FILE), custom ? "aero" : "vanilla",
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Title] menu choice write failed: {}", e.getMessage());
        }
    }

    private static String readChoice() {
        try {
            Path f = FMLPaths.CONFIGDIR.get().resolve(CHOICE_FILE);
            if (Files.isRegularFile(f)) return Files.readString(f, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            LOGGER.warn("[Title] menu choice read failed: {}", e.getMessage());
        }
        return "aero";
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!useCustom) return;
        if (event.getNewScreen() instanceof TitleScreen) {
            event.setNewScreen(new AeroTitleScreen());
        }
    }

    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (useCustom || !(event.getScreen() instanceof TitleScreen screen)) return;
        // Top-right: the vanilla menu's bottom corners already hold its version and copyright lines.
        Button back = Button.builder(
                Component.literal("Aero Menu ►"),
                b -> {
                    setUseCustom(true);
                    Minecraft.getInstance().setScreen(new AeroTitleScreen());
                }
        ).bounds(screen.width - 85, 5, 80, 20).build();
        back.setTooltip(Tooltip.create(Component.literal("Back to the Coffee's Aero SMP menu")));
        event.addListener(back);
    }
}
