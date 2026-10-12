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

        // aerosmp:wardrobe (server -> client): the shop's Wardrobe screen. optional(): servers without it (the lobby,
        // older auth) are unaffected. The handler class is client-only and only referenced on the client.
        modBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent e) ->
            e.registrar("1.0.0").optional().playToClient(
                com.coffeesaerosmp.core.wardrobe.WardrobePayload.TYPE,
                com.coffeesaerosmp.core.wardrobe.WardrobePayload.STREAM_CODEC,
                (payload, ctx) -> com.coffeesaerosmp.core.wardrobe.WardrobeClient.handle(payload, ctx)));

        if (FMLEnvironment.dist == Dist.CLIENT) {
            CoffeesAeroCoreClient.init(modBus);
        }
    }
}