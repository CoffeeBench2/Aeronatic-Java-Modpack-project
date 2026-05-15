package com.coffeesaerosmp.core;

import com.coffeesaerosmp.core.client.MainMenuEvents;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

public class CoffeesAeroCoreClient {

    public static void init(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(MainMenuEvents.class);
    }
}