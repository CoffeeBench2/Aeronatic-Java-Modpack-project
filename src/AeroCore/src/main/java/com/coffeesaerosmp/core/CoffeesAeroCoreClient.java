package com.coffeesaerosmp.core;

import com.coffeesaerosmp.core.client.MainMenuEvents;
import com.coffeesaerosmp.core.client.SelfSkinHandler;
import com.coffeesaerosmp.core.network.SelfSkinPayload;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public class CoffeesAeroCoreClient {

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(MainMenuEvents.class);

        // aerosmp:self_skin — own-view skin fix on the offline-UUID backend. Registrar version and
        // optional() must match the server side (CoffeesAeroAuth's AeroNetworking).
        modBus.addListener((RegisterPayloadHandlersEvent event) ->
            event.registrar("1.0.0").optional()
                .playToClient(SelfSkinPayload.TYPE, SelfSkinPayload.STREAM_CODEC, SelfSkinHandler::onPayload));
        NeoForge.EVENT_BUS.addListener(SelfSkinHandler::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> SelfSkinHandler.reset());
    }
}