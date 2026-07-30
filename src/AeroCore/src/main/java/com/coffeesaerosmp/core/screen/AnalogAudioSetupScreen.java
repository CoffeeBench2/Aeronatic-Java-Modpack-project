package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;

/**
 * One-time helper shown ONLY on Core builds that do not bundle Analog Audio.
 *
 * <p>The CurseForge-website download strips Analog Audio ({@code palm1}'s mod) because CF moderation
 * won't host it; the Modrinth/GitHub builds include it, so this screen never appears there (the title
 * screen only opens it when {@code analogaudio} is NOT loaded). Analog Audio registers a required
 * network channel, so a CF-website install can't join the server and its radios/cassettes are dead —
 * this screen gives the player a one-click link to the mod's Modrinth page plus a shortcut to their
 * mods folder so they can install it themselves.</p>
 *
 * <p>It NEVER downloads anything itself — opening the link goes through the vanilla
 * {@link ConfirmLinkScreen} and only launches the player's browser. (Auto-fetching a jar from outside
 * the pack is exactly what CurseForge policy forbids, and what got an earlier Core build rejected.)
 * It shows at most once per launch and can be silenced permanently via "Don't show again".</p>
 */
public class AnalogAudioSetupScreen extends Screen {

    private static final String MODRINTH_URL = "https://modrinth.com/mod/analog-audio";
    private final Screen parent;

    public AnalogAudioSetupScreen(Screen parent) {
        super(Component.literal("Optional: Analog Audio"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y  = this.height / 2 + 16;

        this.addRenderableWidget(Button.builder(
                Component.literal("Get Analog Audio").withStyle(ChatFormatting.GREEN),
                b -> this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
                        if (confirmed) Util.getPlatform().openUri(MODRINTH_URL);
                        this.minecraft.setScreen(this);
                    }, MODRINTH_URL, true)))
            .bounds(cx - 100, y, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Open mods folder"),
                b -> {
                    File mods = new File(this.minecraft.gameDirectory, "mods");
                    if (!mods.isDirectory()) mods = this.minecraft.gameDirectory;
                    Util.getPlatform().openUri(mods.toURI());
                })
            .bounds(cx - 100, y + 24, 200, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Not now"),
                b -> this.minecraft.setScreen(parent))
            .bounds(cx - 100, y + 52, 98, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Don't show again"),
                b -> {
                    try { AeroConfig.ANALOG_AUDIO_PROMPT_SHOWN.set(true); } catch (Throwable ignored) {}
                    this.minecraft.setScreen(parent);
                })
            .bounds(cx + 2, y + 52, 98, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = this.width / 2;
        int y  = this.height / 2 - 70;

        g.drawCenteredString(this.font,
            Component.literal("Analog Audio isn't installed").withStyle(ChatFormatting.YELLOW),
            cx, y, 0xFFFFFF);

        String[] lines = {
            "This pack uses Analog Audio for the in-game radios and",
            "cassettes, and the server needs it before you can join.",
            "",
            "Click \"Get Analog Audio\", download the 1.21.1 NeoForge",
            "file, and drop the .jar into your mods folder — then relaunch."
        };
        int ly = y + 18;
        for (String s : lines) {
            g.drawCenteredString(this.font,
                Component.literal(s).withStyle(ChatFormatting.WHITE), cx, ly, 0xFFFFFF);
            ly += 12;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
