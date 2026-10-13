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
        modBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent e) -> {
            var reg = e.registrar("1.0.0").optional();
            reg.playToClient(
                com.coffeesaerosmp.core.wardrobe.WardrobePayload.TYPE,
                com.coffeesaerosmp.core.wardrobe.WardrobePayload.STREAM_CODEC,
                (payload, ctx) -> com.coffeesaerosmp.core.wardrobe.WardrobeClient.handle(payload, ctx));
            // 1.4.0: the handshake (this Core's version, so the server can require Core) and the cosmetics table
            // this client draws every trail, contrail, wing and aura from. Mirrors of CoffeesAeroAuth's payloads.
            // DEV ONLY -Daerocore.devNoHello: behave like a pre-1.4.0 Core (no handshake) to test the server's gate.
            if (!Boolean.getBoolean("aerocore.devNoHello")) reg.commonToServer(   // configuration AND play: the gate looks for it while configuring
                com.coffeesaerosmp.core.cosmetics.CoreHelloPayload.TYPE,
                com.coffeesaerosmp.core.cosmetics.CoreHelloPayload.STREAM_CODEC,
                (payload, ctx) -> { });
            reg.playToClient(
                com.coffeesaerosmp.core.cosmetics.CosmeticsPayload.TYPE,
                com.coffeesaerosmp.core.cosmetics.CosmeticsPayload.STREAM_CODEC,
                (payload, ctx) -> com.coffeesaerosmp.core.cosmetics.CosmeticsClient.handle(payload, ctx));
        });

        if (FMLEnvironment.dist == Dist.CLIENT) {
            CoffeesAeroCoreClient.init(modBus);
        }
    }
}