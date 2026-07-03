package com.coffeesaerosmp.skins;

import com.coffeesaerosmp.skins.network.SkinSyncPayload;
import com.coffeesaerosmp.skins.server.SkinCommands;
import com.coffeesaerosmp.skins.server.SkinService;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Coffees Aero Skins — the pack's skin engine, both sides in one jar.
 *
 * <p>Server: fetches/applies/persists skins ({@link SkinService}) and owns {@code /skin}
 * ({@link SkinCommands}); CoffeesAeroAuth plugs in storage + policy via
 * {@link com.coffeesaerosmp.skins.api.AeroSkinsApi}. Client: {@code skin_sync} payloads override the
 * skin lookup for every synced player (see {@code SkinSyncHandler}).</p>
 *
 * <p>The channel is {@code optional()} so vanilla-ish clients (the gate's NanoLimbo, old pack
 * versions) can still connect; they fall back to the vanilla GameProfile/player-info path.</p>
 */
@Mod("coffeesaeroskins")
public class CoffeesAeroSkins {

    public static final Logger LOGGER = LogUtils.getLogger();

    public CoffeesAeroSkins(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) ->
            event.registrar("1.0.0").optional()
                .playToClient(SkinSyncPayload.TYPE, SkinSyncPayload.STREAM_CODEC, CoffeesAeroSkins::handleSync));

        NeoForge.EVENT_BUS.addListener(SkinService::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(SkinService::onPlayerLeave);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> SkinCommands.register(event.getDispatcher()));

        if (FMLEnvironment.dist.isClient()) SkinsClientInit.init();

        LOGGER.info("[Skins] CoffeesAeroSkins initialized ({}).", FMLEnvironment.dist);
    }

    /** playToClient handler — only ever executed on the client; the indirection keeps client-only
     *  classes out of the dedicated server's classloading. */
    private static void handleSync(SkinSyncPayload payload, IPayloadContext context) {
        com.coffeesaerosmp.skins.client.SkinSyncHandler.onPayload(payload, context);
    }
}
