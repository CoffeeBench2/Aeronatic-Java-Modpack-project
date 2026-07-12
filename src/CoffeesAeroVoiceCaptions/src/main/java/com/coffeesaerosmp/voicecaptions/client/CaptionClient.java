package com.coffeesaerosmp.voicecaptions.client;

import com.coffeesaerosmp.voicecaptions.CaptionPayload;
import com.coffeesaerosmp.voicecaptions.CoffeesAeroVoiceCaptions;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of the voice captions (prototype step 3). Holds the latest caption per speaker with an
 * expiry, and renders it above that player's head by overriding the name-tag content while active
 * (vanilla handles the billboard/depth/culling, and only shows nametags within range — which suits a
 * proximity feature). When no caption is active the normal name tag shows unchanged.
 */
@EventBusSubscriber(modid = CoffeesAeroVoiceCaptions.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CaptionClient {

    /** How long a caption stays on screen after the last update. */
    private static final long TTL_MS = 4000L;

    private record Caption(String text, long expiresAt) {}

    private static final Map<UUID, Caption> CAPTIONS = new ConcurrentHashMap<>();

    private CaptionClient() {}

    /** Called on the client network thread's work queue when a caption packet arrives. */
    public static void receive(CaptionPayload payload) {
        if (payload.text() == null || payload.text().isBlank()) {
            CAPTIONS.remove(payload.speaker());
        } else {
            CAPTIONS.put(payload.speaker(), new Caption(payload.text(), System.currentTimeMillis() + TTL_MS));
        }
    }

    @SubscribeEvent
    static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Caption c = CAPTIONS.get(player.getUUID());
        if (c == null) return;
        if (System.currentTimeMillis() > c.expiresAt()) {
            CAPTIONS.remove(player.getUUID());
            return;
        }
        // Show the caption where the name tag renders (above the head). Speech-bubble styling.
        event.setContent(Component.literal("💬 ")
            .append(Component.literal(c.text()).withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC)));
    }
}
