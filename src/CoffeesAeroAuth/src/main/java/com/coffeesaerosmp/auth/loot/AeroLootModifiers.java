package com.coffeesaerosmp.auth.loot;

import com.mojang.serialization.MapCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.loot.IGlobalLootModifier;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Registers the mod's Global Loot Modifier codecs.
 *
 * <p>A GLM's JSON lives in a datapack, but its TYPE has to be registered in code — that is the whole
 * reason this sits in the mod rather than in CoffeesAeroTweaks, which is datapack-only. Registration
 * is on the MOD bus (not the game bus) because registry population happens during mod loading.
 */
public final class AeroLootModifiers {

    private AeroLootModifiers() {}

    private static final DeferredRegister<MapCodec<? extends IGlobalLootModifier>> MODIFIERS =
        DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS,
                                com.coffeesaerosmp.auth.CoffeesAeroAuth.MOD_ID);

    /** {@code coffees_aero_auth:rarity_nerf} — referenced by the datapack JSON's "type" field. */
    public static final Supplier<MapCodec<RarityNerfModifier>> RARITY_NERF =
        MODIFIERS.register("rarity_nerf", () -> RarityNerfModifier.CODEC);

    public static void register(IEventBus modBus) {
        MODIFIERS.register(modBus);
    }
}
