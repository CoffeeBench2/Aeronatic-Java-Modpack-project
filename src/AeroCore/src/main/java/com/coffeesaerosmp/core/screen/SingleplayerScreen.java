package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.client.AeroButton;
import com.coffeesaerosmp.core.client.FlatWorlds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The pack's singleplayer menu: play a world you already have, or make a new one.
 *
 * <p>Every save is listed whatever its generator, including worlds made outside this screen —
 * silently hiding saves would be indistinguishable from losing them.
 */
public class SingleplayerScreen extends Screen {

    private static final int PER_PAGE = 5;

    private final Screen parent;
    private CompletableFuture<List<LevelSummary>> pending;
    private List<LevelSummary> worlds = null;
    private int page = 0;

    public SingleplayerScreen(Screen parent) {
        super(Component.literal("Singleplayer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (this.worlds == null && this.pending == null) {
            this.pending = FlatWorlds.listAsync();
        }
        rebuild();
    }

    /**
     * Re-reads the save list. Called after a world is edited or deleted so the list can't keep
     * offering a save that no longer exists (clicking one would fail on disk, not in the UI).
     */
    void refresh() {
        this.worlds = null;
        this.pending = FlatWorlds.listAsync();
        this.page = 0;
        rebuild();
    }

    /** Called again whenever the list arrives or the page changes. */
    private void rebuild() {
        this.clearWidgets();
        int cx = this.width / 2;
        int top = 56;

        if (this.worlds != null) {
            int from = this.page * PER_PAGE;
            int shown = Math.min(PER_PAGE, this.worlds.size() - from);
            for (int i = 0; i < shown; i++) {
                LevelSummary s = this.worlds.get(from + i);
                int rowY = top + i * 24;
                // Row is split: the name plays the world, the ✎ opens per-world tools. Narrowed
                // from 260 to 234 to make room; the icon sits flush with the old right edge.
                AeroButton b = AeroButton.aero(
                    Component.literal(s.getLevelName()),
                    x -> this.minecraft.createWorldOpenFlows().openWorld(s.getLevelId(), () -> this.minecraft.setScreen(this))
                ).bounds(cx - 130, rowY, 234, 20).build();
                // A world mid-conversion, or open in another instance, must not be launched.
                b.active = !s.isDisabled();
                this.addRenderableWidget(b);

                // Edit / delete. Stays enabled for disabled worlds on purpose — a save that is
                // broken or mid-conversion is exactly the one you may need to delete.
                this.addRenderableWidget(AeroButton.aero(
                    Component.literal("✎"),
                    x -> this.minecraft.setScreen(new WorldToolsScreen(this, s))
                ).bounds(cx + 106, rowY, 24, 20).plain().build());
            }

            int pages = Math.max(1, (this.worlds.size() + PER_PAGE - 1) / PER_PAGE);
            if (pages > 1) {
                int py = top + PER_PAGE * 24;
                AeroButton prev = AeroButton.aero(Component.literal("◄"), x -> { this.page--; rebuild(); })
                    .bounds(cx - 130, py, 40, 20).build();
                prev.active = this.page > 0;
                this.addRenderableWidget(prev);

                AeroButton next = AeroButton.aero(Component.literal("►"), x -> { this.page++; rebuild(); })
                    .bounds(cx + 90, py, 40, 20).build();
                next.active = this.page < pages - 1;
                this.addRenderableWidget(next);
            }
        }

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Create New World"),
            b -> this.minecraft.setScreen(new CreateFlatWorldScreen(this))
        ).bounds(cx - 130, this.height - 52, 260, 20).build());

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Back"),
            b -> this.minecraft.setScreen(this.parent)
        ).bounds(cx - 130, this.height - 28, 260, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.pending != null && this.pending.isDone()) {
            this.worlds = this.pending.join();
            this.pending = null;
            this.page = 0;
            rebuild();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFF0C05A);
        g.drawCenteredString(this.font, "Normal or Flat — your choice",
            this.width / 2, 32, 0xFF9A8B76);

        if (this.worlds == null) {
            g.drawCenteredString(this.font, "Reading your saves…", this.width / 2, 90, 0xFFBFBFBF);
        } else if (this.worlds.isEmpty()) {
            g.drawCenteredString(this.font, "No worlds yet — create one below.",
                this.width / 2, 90, 0xFFBFBFBF);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
