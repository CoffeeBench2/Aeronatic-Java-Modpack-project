package com.coffeesaerosmp.core.mixin;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Compat patch: Alex's Mobs 1.22.17 ↔ Citadel 2.7.0.
 *
 * <p>Citadel 2.7.0 changed {@code GuiBasicBook}'s abstract {@code getTextFileDirectory()} from
 * returning {@code String} to returning {@code ResourceLocation}. Alex's Mobs 1.22.17 still only
 * implements the old {@code String} version, so opening the Animal Dictionary throws
 * {@code AbstractMethodError} (the class never implements the new {@code ResourceLocation} signature).</p>
 *
 * <p>We add the missing {@code ResourceLocation}-returning method via a string-targeted mixin. At the
 * bytecode level it coexists with the original {@code String} method (different descriptors). The value
 * reproduces Alex's Mobs' old directory string exactly — Citadel does
 * {@code getTextFileDirectory().withSuffix(parent)} and {@code String.valueOf(dir) + file}, so the same
 * "alexsmobs:book/animal_dictionary/" prefix yields the same page paths → the book renders normally.</p>
 *
 * <p>String target + {@code remap = false} so we don't need Alex's Mobs/Citadel on the compile
 * classpath and Mixin doesn't try to SRG-remap a mod method. Listed under {@code client} — the target
 * GUI is client-only, so this is skipped on a dedicated server.</p>
 */
@Mixin(targets = "com.github.alexthe666.alexsmobs.client.gui.GUIAnimalDictionary", remap = false)
public class GUIAnimalDictionaryMixin {

    public ResourceLocation getTextFileDirectory() {
        return ResourceLocation.parse("alexsmobs:book/animal_dictionary/");
    }
}
