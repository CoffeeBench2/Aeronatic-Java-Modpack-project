package com.coffeesaerosmp.powerbridge;

import com.coffeesaerosmp.powerbridge.config.PowerBridgeConfig;
import com.coffeesaerosmp.powerbridge.content.dynamo.KineticDynamoBlockEntity;
import com.coffeesaerosmp.powerbridge.content.motor.FEMotorBlockEntity;
import com.coffeesaerosmp.powerbridge.register.ModBlockEntities;
import com.coffeesaerosmp.powerbridge.register.ModBlocks;
import com.coffeesaerosmp.powerbridge.register.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@Mod(PowerBridgeMod.MOD_ID)
public class PowerBridgeMod {

    public static final String MOD_ID = "coffees_power_bridge";

    public PowerBridgeMod(IEventBus modBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);

        container.registerConfig(ModConfig.Type.COMMON, PowerBridgeConfig.SPEC);

        modBus.addListener(this::onRegisterCapabilities);
    }

    private void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        FEMotorBlockEntity.registerCapabilities(event);
        KineticDynamoBlockEntity.registerCapabilities(event);
    }
}
