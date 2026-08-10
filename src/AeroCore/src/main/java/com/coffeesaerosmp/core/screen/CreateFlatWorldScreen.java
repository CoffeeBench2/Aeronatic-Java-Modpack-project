package com.coffeesaerosmp.core.screen;

import com.coffeesaerosmp.core.client.AeroButton;
import com.coffeesaerosmp.core.client.FlatWorlds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

/**
 * Name, world type, terrain, structures and game mode, then build.
 *
 * <p>Two world types only — Normal and Flat — because those are the two things people actually want
 * out of singleplayer here: somewhere to play, and a bench to test contraptions on. Normal is the
 * default so the button people press without reading gives them an ordinary world.
 *
 * <p>Terrain and Structures default to the clean end: a fresh Normal world gets vanilla biomes and no
 * structures, so it is a quiet place to build rather than a copy of the server's world. Both are
 * toggles rather than a fixed policy — turning structures off also turns off <i>vanilla</i> villages
 * and strongholds, which is a real trade-off and belongs to the player, not to us.
 */
public class CreateFlatWorldScreen extends Screen {

    private final Screen parent;
    private EditBox nameBox;
    private FlatWorlds.Kind kind = FlatWorlds.Kind.NORMAL;
    private FlatWorlds.Terrain terrain = FlatWorlds.Terrain.VANILLA;
    private boolean structures = false;
    private GameType mode = GameType.SURVIVAL;
    private AeroButton kindButton;
    private AeroButton terrainButton;
    private AeroButton structuresButton;
    private AeroButton modeButton;

    public CreateFlatWorldScreen(Screen parent) {
        super(Component.literal("New World"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 70;

        this.nameBox = new EditBox(this.font, cx - 100, y, 200, 20, Component.literal("World name"));
        this.nameBox.setValue("New World");
        this.nameBox.setMaxLength(48);
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);

        this.kindButton = AeroButton.aero(kindLabel(), b -> {
            this.kind = this.kind.next();
            this.kindButton.setMessage(kindLabel());
            refreshTerrainState();
            // Only retitle while the player hasn't made the name their own.
            if (isDefaultName(this.nameBox.getValue())) {
                this.nameBox.setValue(this.kind == FlatWorlds.Kind.FLAT ? "Flat World" : "New World");
            }
        }).bounds(cx - 100, y + 26, 200, 20).build();
        this.addRenderableWidget(this.kindButton);

        this.terrainButton = AeroButton.aero(terrainLabel(), b -> {
            this.terrain = this.terrain.next();
            this.terrainButton.setMessage(terrainLabel());
        }).bounds(cx - 100, y + 50, 200, 20).build();
        this.addRenderableWidget(this.terrainButton);

        this.structuresButton = AeroButton.aero(structuresLabel(), b -> {
            this.structures = !this.structures;
            this.structuresButton.setMessage(structuresLabel());
        }).bounds(cx - 100, y + 74, 200, 20).build();
        this.addRenderableWidget(this.structuresButton);

        this.modeButton = AeroButton.aero(modeLabel(), b -> {
            this.mode = (this.mode == GameType.CREATIVE) ? GameType.SURVIVAL : GameType.CREATIVE;
            this.modeButton.setMessage(modeLabel());
        }).bounds(cx - 100, y + 98, 200, 20).build();
        this.addRenderableWidget(this.modeButton);

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Create World"),
            b -> FlatWorlds.create(this.parent, this.nameBox.getValue(), this.mode, this.kind,
                this.terrain, this.structures)
        ).bounds(cx - 100, y + 128, 200, 20).build());

        this.addRenderableWidget(AeroButton.aero(
            Component.literal("Back"),
            b -> this.minecraft.setScreen(this.parent)
        ).bounds(cx - 100, y + 152, 200, 20).build());

        refreshTerrainState();
    }

    /** A superflat world has no biome layout to choose, so the terrain toggle would be a lie. */
    private void refreshTerrainState() {
        this.terrainButton.active = this.kind != FlatWorlds.Kind.FLAT;
    }

    private static boolean isDefaultName(String s) {
        return s.equals("New World") || s.equals("Flat World");
    }

    private Component kindLabel() {
        return Component.literal("World Type: " + kind.label);
    }

    private Component terrainLabel() {
        return Component.literal("Terrain: " + terrain.label);
    }

    private Component structuresLabel() {
        return Component.literal("Structures: " + (structures ? "On" : "Off"));
    }

    private Component modeLabel() {
        return Component.literal("Mode: " + (mode == GameType.CREATIVE ? "Creative" : "Survival"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, this.height / 2 - 100, 0xFFF0C05A);
        g.drawCenteredString(this.font, hint(), cx, this.height / 2 - 88, 0xFF9A8B76);

        // The consequences of the two toggles, spelled out under the buttons rather than in a wiki.
        int footer = this.height / 2 + 100;
        g.drawCenteredString(this.font, terrainNote(), cx, footer, 0xFF7E7062);
        g.drawCenteredString(this.font, structuresNote(), cx, footer + 11, 0xFF7E7062);
    }

    private String hint() {
        return kind == FlatWorlds.Kind.FLAT
            ? "Superflat — a bench for testing contraptions"
            : "Normal — full worldgen, same mods as the server";
    }

    private String terrainNote() {
        if (kind == FlatWorlds.Kind.FLAT) return "Superflat has no biome layout to choose.";
        return terrain == FlatWorlds.Terrain.VANILLA
            ? "Vanilla: vanilla biomes only, none of the pack's added biomes."
            : "Modded: the pack's biomes, like the server world.";
    }

    private String structuresNote() {
        return structures
            ? "On: every structure, including Railways Untold and the pack's."
            : "Off: no structures at all — including vanilla villages.";
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}
