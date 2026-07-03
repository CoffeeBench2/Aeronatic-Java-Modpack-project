package com.coffeesaerosmp.skins;

import com.coffeesaerosmp.skins.client.SkinSyncHandler;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only wiring, kept in its own class so the dedicated server never classloads client events. */
final class SkinsClientInit {

    private SkinsClientInit() {}

    static void init() {
        NeoForge.EVENT_BUS.addListener(SkinSyncHandler::onClientTick);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> SkinSyncHandler.reset());
    }
}
