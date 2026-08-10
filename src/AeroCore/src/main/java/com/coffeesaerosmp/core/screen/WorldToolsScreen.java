package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.client.AeroButton;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.io.IOException;

/**
 * Per-world tools, opened by the ✎ button beside a save in {@link SingleplayerScreen}.
 *
 * <p>The custom singleplayer menu replaces vanilla's world-select screen, which is where Edit and
 * Delete normally live — so without this there is no route to either. Both actions delegate to
 * vanilla ({@link EditWorldScreen}, {@code LevelStorageAccess#deleteLevel}) rather than
 * reimplementing them: world deletion is irreversible and not worth a bespoke implementation.
 */
public class WorldToolsScreen extends Screen {

    private final SingleplayerScreen parent;
    private final LevelSummary summary;

    public WorldToolsScreen(SingleplayerScreen parent, LevelSummary summary) {
        super(Component.literal(summary.getLevelName()));
        this.parent = parent;
        this.summary = summary;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 34;

        // Vanilla's edit screen: rename, backup, optimize, open folder, resource pack.
        AeroButton edit = AeroButton.aero(
            Component.literal("Edit World"),
            b -> openEdit()
        ).bounds(cx - 100, y, 200, 20).build();
        edit.active = !this.summary.isDisabled();
        this.addRenderableWidget(edit);

        // Deliberately behind a confirm: this deletes the save directory and cannot be undone.
        AeroButton delete = AeroButton.aero(
            Component.literal("§cDelete World"),
            b -> this.minecraft.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) deleteWorld();
                    else this.minecraft.setScreen(this);
                },
                Component.literal("Delete \"" + this.summary.getLevelName() + "\"?"),
                Component.literal("This permanently deletes the world and everything in it."),
                Component.literal("Delete"),
                Component.literal("Cancel")))
        ).bounds(cx - 100, y + 26, 200, 20).build();
        this.addRenderableWidget(delete);

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Back"),
            b -> this.minecraft.setScreen(this.parent)
        ).bounds(cx - 100, y + 58, 200, 20).build());
    }

    /**
     * Opens vanilla's edit screen. The storage access is held open for the screen's lifetime and
     * closed in the callback — closing it early would break every action on that screen.
     */
    private void openEdit() {
        try {
            // Held open for the edit screen's lifetime — closing it early breaks every action there.
            final LevelStorageSource.LevelStorageAccess access =
                this.minecraft.getLevelSource().validateAndCreateAccess(this.summary.getLevelId());
            this.minecraft.setScreen(EditWorldScreen.create(this.minecraft, access, ok -> {
                try {
                    access.close();
                } catch (IOException ignored) {
                    // Nothing useful to do; the save is written and the screen is closing.
                }
                this.parent.refresh();
                this.minecraft.setScreen(this.parent);
            }));
        } catch (IOException | net.minecraft.world.level.validation.ContentValidationException e) {
            failed("Could not open this world for editing.", e);
        }
    }

    private void deleteWorld() {
        try (LevelStorageSource.LevelStorageAccess access =
                 this.minecraft.getLevelSource().createAccess(this.summary.getLevelId())) {
            access.deleteLevel();
        } catch (IOException e) {
            failed("Could not delete this world. It may be open in another instance.", e);
            return;
        }
        this.parent.refresh();
        this.minecraft.setScreen(this.parent);
    }

    private void failed(String message, Exception e) {
        com.mojang.logging.LogUtils.getLogger().error("[AeroCore] {} ({})", message, this.summary.getLevelId(), e);
        this.minecraft.setScreen(new ConfirmScreen(
            ignored -> this.minecraft.setScreen(this.parent),
            Component.literal("§cFailed"),
            Component.literal(message),
            Component.literal("OK"),
            Component.literal("OK")));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
