package com.coffeesaerosmp.core.wardrobe;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client handler for {@link WardrobePayload}: opens the screen, or refreshes it if it's already open. */
public final class WardrobeClient {

    private WardrobeClient() {}

    public static void handle(WardrobePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> apply(payload));
    }

    /** Main thread: open the screen, or refresh it if it's already open. */
    static void apply(WardrobePayload payload) {
        WardrobeData data;
        try {
            data = WardrobeData.parse(payload.json());
        } catch (Exception e) {
            com.mojang.logging.LogUtils.getLogger().warn("[Wardrobe] bad payload: {}", e.toString());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof WardrobeScreen open) {
            open.refresh(data);
        } else if (data.open) {
            mc.setScreen(new WardrobeScreen(data));
        }
    }
}
