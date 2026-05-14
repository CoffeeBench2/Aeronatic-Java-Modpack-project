package com.coffeesaerosmp.core;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(CoffeesAeroCore.MODID)
public class CoffeesAeroCore {

    public static final String MODID = "coffeesaerosmp_core";

    public CoffeesAeroCore(IEventBus modEventBus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, AeroConfig.CLIENT_SPEC);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            CoffeesAeroCoreClient.init(modEventBus);
        }
    }
}
