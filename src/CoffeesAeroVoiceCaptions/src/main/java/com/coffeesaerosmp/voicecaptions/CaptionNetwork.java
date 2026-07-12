package com.coffeesaerosmp.voicecaptions;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers the {@link CaptionPayload} (server → client). The client handler lives in the client-only
 * {@code client.CaptionClient}; it is referenced ONLY inside the play-to-client lambda, which is
 * never invoked on the dedicated server — so that class is never loaded server-side (lambdas link
 * lazily), keeping the server free of client imports.
 */
@EventBusSubscriber(modid = CoffeesAeroVoiceCaptions.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class CaptionNetwork {

    private CaptionNetwork() {}

    @SubscribeEvent
    static void onRegister(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
            .optional()   // players/servers without the mod simply don't send/receive captions
            .playToClient(CaptionPayload.TYPE, CaptionPayload.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                    com.coffeesaerosmp.voicecaptions.client.CaptionClient.receive(payload)));
    }
}
