package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper for server-required mods that a store download cannot ship, listed in the client config
 * {@code manualMods}. Replaces the Analog Audio–only helper (2026-10-05) so Season 3's SSRD, which is
 * All Rights Reserved and not hosted on CurseForge, gets the same treatment.
 *
 * <p>Each mod here registers a REQUIRED network channel, so a store install without it is refused by
 * the server. The screen gives one button per missing mod that opens its page through the vanilla
 * {@link ConfirmLinkScreen}, plus a shortcut to the mods folder. It NEVER downloads anything itself:
 * auto-fetching a jar from outside the pack is exactly what CurseForge policy forbids, and what got an
 * earlier Core build rejected.</p>
 *
 * <p>Shown at most once per launch while something is missing. There is deliberately no "don't show
 * again": without the mod the pack cannot join its own server.</p>
 */
public class MissingModsScreen extends Screen {

    public record ManualMod(String modId, String name, String url, String purpose) {}

    private final Screen parent;
    private final List<ManualMod> missing;

    public MissingModsScreen(Screen parent, List<ManualMod> missing) {
        super(Component.literal("Mods to install"));
        this.parent = parent;
        this.missing = missing;
    }

    /** Config entries whose mod is not loaded. Malformed entries are skipped, never fatal. */
    public static List<ManualMod> missing() {
        List<ManualMod> out = new ArrayList<>();
        try {
            for (String raw : AeroConfig.MANUAL_MODS.get()) {
                String[] p = raw.split("\\|");
                if (p.length < 3 || ModList.get().isLoaded(p[0].trim())) continue;
                // A player who permanently dismissed the old Analog Audio helper keeps that choice.
                if (p[0].trim().equals("analogaudio") && AeroConfig.ANALOG_AUDIO_PROMPT_SHOWN.get()) continue;
                out.add(new ManualMod(p[0].trim(), p[1].trim(), p[2].trim(), p.length > 3 ? p[3].trim() : ""));
            }
        } catch (Throwable ignored) {
            // Config not readable: show nothing rather than block the title screen.
        }
        return out;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        // Text block (render) ends at height/2 + 1 + 5n; start the buttons clear of it.
        int y = this.height / 2 + 8 + missing.size() * 5;

        for (ManualMod m : missing) {
            this.addRenderableWidget(Button.builder(
                    Component.literal("Get " + m.name()).withStyle(ChatFormatting.GREEN),
                    b -> this.minecraft.setScreen(new ConfirmLinkScreen(confirmed -> {
                            if (confirmed) Util.getPlatform().openUri(m.url());
                            this.minecraft.setScreen(this);
                        }, m.url(), true)))
                .bounds(cx - 110, y, 220, 20).build());
            y += 24;
        }

        this.addRenderableWidget(Button.builder(
                Component.literal("Open mods folder"),
                b -> {
                    File mods = new File(this.minecraft.gameDirectory, "mods");
                    if (!mods.isDirectory()) mods = this.minecraft.gameDirectory;
                    Util.getPlatform().openUri(mods.toURI());
                })
            .bounds(cx - 110, y + 4, 108, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal("Not now"),
                b -> this.minecraft.setScreen(parent))
            .bounds(cx + 2, y + 4, 108, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        int cx = this.width / 2;
        int y = this.height / 2 - 70 - missing.size() * 6;

        String title = missing.size() == 1
            ? missing.get(0).name() + " isn't installed"
            : missing.size() + " required mods aren't installed";
        g.drawCenteredString(this.font, Component.literal(title).withStyle(ChatFormatting.YELLOW), cx, y, 0xFFFFFF);

        List<String> lines = new ArrayList<>();
        lines.add("The server needs these before you can join, but this download");
        lines.add("isn't allowed to include them:");
        for (ManualMod m : missing) {
            lines.add("• " + m.name() + (m.purpose().isEmpty() ? "" : " — " + m.purpose()));
        }
        lines.add("");
        lines.add("Click each button, download the 1.21.1 NeoForge file,");
        lines.add("drop the .jar into your mods folder, then relaunch.");
        int ly = y + 16;
        for (String s : lines) {
            g.drawCenteredString(this.font, Component.literal(s), cx, ly, 0xFFFFFF);
            ly += 11;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
