package com.coffeesaerosmp.core;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(CoffeesAeroCore.MOD_ID)
public class CoffeesAeroCore {

    public static final String MOD_ID = "coffeesaerosmp_core";

    public CoffeesAeroCore(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, AeroConfig.CLIENT_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            CoffeesAeroCoreClient.init(modBus);
        }
    }
}