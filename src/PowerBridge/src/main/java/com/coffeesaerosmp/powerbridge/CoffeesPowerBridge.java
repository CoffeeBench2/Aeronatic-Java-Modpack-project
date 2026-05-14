package com.coffeesaerosmp.powerbridge;

import com.coffeesaerosmp.powerbridge.config.BridgeConfig;
import com.coffeesaerosmp.powerbridge.registry.ModBlockEntities;
import com.coffeesaerosmp.powerbridge.registry.ModBlocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.Capabilities;

@Mod(CoffeesPowerBridge.MODID)
public class CoffeesPowerBridge {

    public static final String MODID = "coffees_power_bridge";

    public CoffeesPowerBridge(IEventBus modEventBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BridgeConfig.SPEC);

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);

        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(this::onRegisterCapabilities);
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // IE wire compat is registered lazily — see IECompatHandler
    }

    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        // FE Kinetic Importer — accepts FE input
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            ModBlockEntities.FE_KINETIC_IMPORTER.get(),
            (be, side) -> be.getEnergyStorage()
        );

        // Kinetic FE Exporter — provides FE output
        event.registerBlockEntity(
            Capabilities.EnergyStorage.BLOCK,
            ModBlockEntities.KINETIC_FE_EXPORTER.get(),
            (be, side) -> be.getEnergyStorage()
        );
    }
}
