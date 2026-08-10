package com.coffeesaerosmp.core.client;

import net.minecraft.FileUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * World plumbing for the pack's singleplayer: list existing saves, and create new ones in the two
 * shapes the pack offers — {@link Kind#NORMAL} (the default) and {@link Kind#FLAT}.
 *
 * <p><b>Why this exists instead of a mixin.</b> The obvious way to control world creation is to hook
 * vanilla's {@code CreateWorldScreen} / {@code WorldCreationUiState}. That means fighting a screen
 * built to expose far more than we want — every MC and NeoForge update can move those internals, and
 * a missed target is a hard crash because our mixin config is {@code required:true}. Building the
 * world ourselves through the public
 * {@link net.minecraft.client.gui.screens.worldselection.WorldOpenFlows#createFreshLevel} entry
 * point touches no internals, and the choice is a plain two-option toggle rather than a locked-down
 * copy of vanilla's screen.
 */
public final class FlatWorlds {

    private static final Logger LOG = LoggerFactory.getLogger("CoffeesAeroCore-Singleplayer");

    /** The world shapes singleplayer offers. NORMAL is the default. */
    public enum Kind {
        NORMAL("Normal"),
        FLAT("Flat");

        public final String label;
        Kind(String label) { this.label = label; }

        public Kind next() { return this == NORMAL ? FLAT : NORMAL; }
    }

    private FlatWorlds() {}

    /** Async so the title screen never stalls on disk. Vanilla's world list does the same. */
    public static CompletableFuture<List<LevelSummary>> listAsync() {
        Minecraft mc = Minecraft.getInstance();
        LevelStorageSource source = mc.getLevelSource();
        return CompletableFuture
            .supplyAsync(() -> {
                try {
                    return source.findLevelCandidates();
                } catch (Exception e) {
                    LOG.warn("Could not scan the saves folder: {}", e.toString());
                    return new LevelStorageSource.LevelCandidates(List.of());
                }
            }, net.minecraft.Util.backgroundExecutor())
            .thenCompose(source::loadLevelSummaries)
            .exceptionally(t -> {
                LOG.warn("Could not read world summaries: {}", t.toString());
                return List.of();
            });
    }

    /**
     * Creates a world of the given {@link Kind} and loads straight into it.
     *
     * <p>The display name and the folder name are deliberately separate: two worlds may both be
     * called "New World", so the folder is de-duplicated via {@link FileUtil#findAvailableName}
     * (the same helper vanilla's create screen uses) while the name the player typed is kept intact.
     */
    public static void create(Screen parent, String displayName, GameType mode, Kind kind,
                              Terrain terrain, boolean structures) {
        Minecraft mc = Minecraft.getInstance();
        String name = displayName.isBlank() ? (kind == Kind.FLAT ? "Flat World" : "New World") : displayName.trim();

        String directory;
        try {
            directory = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(),
                FileUtil.sanitizeName(name), "");
        } catch (Exception e) {
            // A name of only illegal characters sanitises to nothing. Don't dead-end the player.
            LOG.warn("Falling back to a generated folder name for '{}': {}", name, e.toString());
            directory = "world-" + System.currentTimeMillis();
        }

        LevelSettings settings = new LevelSettings(
            name,
            mode,
            false,                       // never hardcore — nothing here should be able to end a save
            Difficulty.NORMAL,
            true,                        // allow commands: singleplayer here is for testing builds
            new GameRules(),
            WorldDataConfiguration.DEFAULT);

        WorldOptions options = WorldOptions.defaultWithRandomSeed().withStructures(structures);

        mc.createWorldOpenFlows().createFreshLevel(
            directory, settings, options, access -> dimensions(access, kind, terrain), parent);
    }

    /**
     * How much of the pack's worldgen a NORMAL world gets. Ignored by {@link Kind#FLAT}, which has no
     * terrain to shape.
     */
    public enum Terrain {
        /** Vanilla biomes only — none of the pack's added biomes. */
        VANILLA("Vanilla"),
        /** Whatever the installed worldgen mods generate, i.e. what the server world looks like. */
        MODDED("Modded");

        public final String label;
        Terrain(String label) { this.label = label; }

        public Terrain next() { return this == VANILLA ? MODDED : VANILLA; }
    }

    /**
     * Builds the dimensions for a new world, starting from the vanilla preset for this {@link Kind}
     * and — for {@link Terrain#VANILLA} — swapping the overworld generator for a vanilla-biome one.
     *
     * <p><b>Why the swap is needed.</b> Loading the {@code minecraft:normal} preset is not enough to
     * get a vanilla world here. Terralith ships
     * {@code data/minecraft/worldgen/multi_noise_biome_source_parameter_list/overworld.json}, which
     * <i>replaces</i> the vanilla {@code minecraft:overworld} climate parameter list that the preset
     * points at — so the preset faithfully generates Terralith's biomes. Disabling the mod's datapack
     * per-world is not an option either: NeoForge's {@code DataPackConfig.addModPacks} force-adds every
     * mod pack back into the enabled list at world load and never consults the disabled list.
     *
     * <p>The way through is to not reference that registry entry at all.
     * {@code MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD} is built in <i>code</i> from
     * vanilla's own biome layout, so constructing a parameter list from it yields vanilla's biome
     * distribution over vanilla biomes no matter what any datapack has done to the registry copy.
     *
     * <p><b>What this does not cover:</b> the {@code minecraft:overworld} <i>noise settings</i> —
     * terrain height and surface rules — are also overridden by Terralith, and this keeps using
     * whatever owns that entry. So a VANILLA world has vanilla biomes and (with structures off) no
     * mod structures, but its terrain shaping is still the pack's. Replacing that too would mean
     * hand-rolling the whole vanilla noise router, which is a large, fragile copy of data that
     * changes every MC version.
     */
    private static WorldDimensions dimensions(RegistryAccess access, Kind kind, Terrain terrain) {
        WorldDimensions preset = access.registryOrThrow(Registries.WORLD_PRESET)
            .getHolderOrThrow(kind == Kind.FLAT ? WorldPresets.FLAT : WorldPresets.NORMAL)
            .value()
            .createWorldDimensions();

        if (kind == Kind.FLAT || terrain == Terrain.MODDED) {
            return preset;
        }

        try {
            Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
            // The parameter list wants a HolderGetter, not the registry itself — asLookup() is the
            // registry's own view of that interface, so no separate lookup provider is needed.
            MultiNoiseBiomeSourceParameterList vanillaClimate = new MultiNoiseBiomeSourceParameterList(
                MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD, biomes.asLookup());

            ChunkGenerator generator = new NoiseBasedChunkGenerator(
                MultiNoiseBiomeSource.createFromList(vanillaClimate.parameters()),
                access.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));

            return preset.replaceOverworldGenerator(access, generator);
        } catch (Exception e) {
            // A mod that removes a vanilla biome outright would break the vanilla climate list. Falling
            // back to the modded preset gives the player a world rather than an error screen.
            LOG.warn("Vanilla terrain unavailable, using the pack's worldgen instead: {}", e.toString());
            return preset;
        }
    }
}
