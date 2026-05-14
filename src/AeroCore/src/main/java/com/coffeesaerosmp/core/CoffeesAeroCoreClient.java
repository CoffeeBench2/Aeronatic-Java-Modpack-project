package com.coffeesaerosmp.core;

import com.coffeesaerosmp.core.client.MainMenuEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

public class CoffeesAeroCoreClient {

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(CoffeesAeroCoreClient::onClientSetup);
        NeoForge.EVENT_BUS.register(MainMenuEvents.class);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        // Client setup — nothing needed here currently
    }
}
