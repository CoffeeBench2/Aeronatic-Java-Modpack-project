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
    /** Set when a mode switch could not be handed off; survives the rebuildLabels() re-init. */
    private String modeError;

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

        // Mode sits below the per-mod toggles and is deliberately NOT one of them: every other
        // switch here applies instantly, this one cannot (FML holds the jars open) and it changes
        // which mods load at all rather than how one behaves.
        int modeY = top + this.toggles.size() * 24 + 8;
        AeroButton mode = AeroButton.aero(modeLabel(), b -> cycleMode())
            .bounds(cx - 130, modeY, 260, 20).build();
        mode.setTooltip(Tooltip.create(Component.literal(
            "Potato turns off the heaviest client mods for low-end PCs:\n"
            + "Distant Horizons, realistic sound, ambient particles,\n"
            + "camera tilt and hit shake. Your video settings are lowered\n"
            + "and your originals are restored when you switch back.\n\n"
            + "Nothing is deleted, and switching back needs no download.")));
        this.addRenderableWidget(mode);

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Done"),
            b -> this.minecraft.setScreen(this.parent)
        ).bounds(cx - 130, this.height - 32, 260, 20).build());
    }

    // ── Client mode (Normal / Potato) ───────────────────────────────────────────

    private java.nio.file.Path gameDir() {
        return this.minecraft.gameDirectory.toPath();
    }

    private Component modeLabel() {
        var dir = gameDir();
        var pending = com.coffeesaerosmp.core.mode.ClientMode.pending(dir);
        var shown = pending != null ? pending : com.coffeesaerosmp.core.mode.ClientMode.current(dir);
        return Component.literal("Mode: " + shown.label() + (pending != null ? " §6(restart)" : ""));
    }

    /**
     * Toggles between Normal and Potato. Clicking again while a switch is already staged CANCELS it
     * rather than staging the opposite — a player who changed their mind should end up back where
     * they started, not with two pending switches fighting over the same jars.
     */
    private void cycleMode() {
        var dir = gameDir();
        var pending = com.coffeesaerosmp.core.mode.ClientMode.pending(dir);
        if (pending != null) {
            com.coffeesaerosmp.core.mode.ClientMode.cancelPending(dir);
            this.modeError = null;
            rebuildLabels();
            return;
        }
        var current = com.coffeesaerosmp.core.mode.ClientMode.current(dir);
        var target = current == com.coffeesaerosmp.core.mode.ClientMode.Mode.POTATO
            ? com.coffeesaerosmp.core.mode.ClientMode.Mode.NORMAL
            : com.coffeesaerosmp.core.mode.ClientMode.Mode.POTATO;
        boolean ok = com.coffeesaerosmp.core.mode.ClientMode.requestSwitch(dir, target);
        // Say so when the handoff could not start, instead of telling the player to restart into a
        // change that will never be applied.
        this.modeError = ok ? null : "§cCould not start the mode switcher. Nothing was changed.";
        rebuildLabels();
    }

    private Component label(ClientToggles.Toggle t) {
        boolean on = t.get().getAsBoolean();
        // Per-toggle wording, not a hardcoded ON/OFF — the recipe-viewer switch reads
        // "Recipe Viewer: EMI" / "Recipe Viewer: JEI", which is the actual choice being made.
        String suffix = "";
        if ("Recipe Viewer".equals(t.label())
                && com.coffeesaerosmp.core.client.RecipeViewer.needsRestart()) {
            suffix = " §6(restart)";
        }
        return Component.literal(t.label() + ": " + (on ? t.onText() : t.offText()) + suffix);
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
            // Only shown while a restart is genuinely outstanding — a permanent notice gets ignored.
            var pendingMode = com.coffeesaerosmp.core.mode.ClientMode.pending(gameDir());
            if (this.modeError != null) {
                g.drawCenteredString(this.font, this.modeError,
                    this.width / 2, this.height - 58, 0xFFFF5555);
                g.drawCenteredString(this.font, "§7Click Mode again to retry.",
                    this.width / 2, this.height - 46, 0xFFAAAAAA);
            } else if (pendingMode != null) {
                // The mode switch outranks the recipe-viewer notice: it is the one that changes
                // which mods load, and it is already staged on disk waiting for this process to exit.
                g.drawCenteredString(this.font,
                    "§6⚠ Quit Minecraft to finish switching to " + pendingMode.label() + ".",
                    this.width / 2, this.height - 58, 0xFFFFD24A);
                g.drawCenteredString(this.font,
                    "§7It is applied after you close the game. Click Mode again to cancel.",
                    this.width / 2, this.height - 46, 0xFFAAAAAA);
            } else if (com.coffeesaerosmp.core.client.RecipeViewer.needsRestart()) {
                g.drawCenteredString(this.font,
                    "§6⚠ Restart Minecraft to switch your recipe viewer.",
                    this.width / 2, this.height - 58, 0xFFFFD24A);
                g.drawCenteredString(this.font,
                    "§7Everything else applies immediately.",
                    this.width / 2, this.height - 46, 0xFFAAAAAA);
            } else {
                g.drawCenteredString(this.font, "§7Changes apply immediately.",
                    this.width / 2, this.height - 46, 0xFFAAAAAA);
            }
            g.drawCenteredString(this.font, diagnostics(), this.width / 2, 38, 0xFF888888);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
