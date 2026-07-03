package com.coffeesaerosmp.core;

import com.coffeesaerosmp.core.client.TitleReplacer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ScreenEvent;

public class CoffeesAeroCoreClient {

    public static void init(IEventBus modBus) {
        // Native title screen (replaced FancyMenu in 1.3.0) — panorama + pack logo + cogwheels,
        // Join button with version gating, admin corner buttons.
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Opening e) -> TitleReplacer.onScreenOpening(e));
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e) -> TitleReplacer.onScreenInit(e));

        // (The 1.2.0-era aerosmp:self_skin channel + own-view skin fix moved to the CoffeesAeroSkins
        // mod, which owns skin sync for every player on its aerosmp:skin_sync channel.)
    }
}
