package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.client.AeroButton;
import com.coffeesaerosmp.core.client.ClientToggles;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Aero settings: on/off switches for the pack's client-side cosmetic mods.
 *
 * <p>The point is that a player can turn off Realistic Sounds or Punchy without hunting through
 * each mod's own config screen. Every switch takes effect immediately — see {@link ClientToggles}
 * for why that means writing the live field rather than the config file.
 */
public class AeroSettingsScreen extends Screen {

    private final Screen parent;
    private List<ClientToggles.Toggle> toggles;

    public AeroSettingsScreen(Screen parent) {
        super(Component.literal("Aero Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.toggles = ClientToggles.all();
        int cx = this.width / 2;
        int top = 50;

        for (int i = 0; i < this.toggles.size(); i++) {
            ClientToggles.Toggle t = this.toggles.get(i);
            AeroButton b = AeroButton.aero(label(t), btn -> {
                t.set().accept(!t.get().getAsBoolean());
                rebuildLabels();
            }).bounds(cx - 130, top + i * 24, 260, 20).build();
            b.setTooltip(Tooltip.create(Component.literal(t.description())));
            this.addRenderableWidget(b);
        }

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Done"),
            b -> this.minecraft.setScreen(this.parent)
        ).bounds(cx - 130, this.height - 32, 260, 20).build());
    }

    private Component label(ClientToggles.Toggle t) {
        boolean on = t.get().getAsBoolean();
        return Component.literal(t.label() + ": " + (on ? "§aON" : "§cOFF"));
    }

    /** Shown under the title so a toggle that found zero flags is visible, not silent. */
    private String diagnostics() {
        return this.toggles == null ? "" : ("§8" + this.toggles.size() + " toggles detected");
    }

    /** Cheapest correct refresh — the toggle list is tiny and rebuilding keeps labels honest. */
    private void rebuildLabels() {
        this.clearWidgets();
        init();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        if (this.toggles != null && this.toggles.isEmpty()) {
            g.drawCenteredString(this.font, "§7No toggleable client mods are installed.",
                this.width / 2, 60, 0xFFAAAAAA);
        } else {
            g.drawCenteredString(this.font, "§7Changes apply immediately.",
                this.width / 2, this.height - 46, 0xFFAAAAAA);
            g.drawCenteredString(this.font, diagnostics(), this.width / 2, 38, 0xFF888888);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
