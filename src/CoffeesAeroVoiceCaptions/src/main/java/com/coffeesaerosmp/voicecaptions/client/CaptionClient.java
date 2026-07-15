package com.coffeesaerosmp.voicecaptions.client;

import com.coffeesaerosmp.voicecaptions.CaptionPayload;
import com.coffeesaerosmp.voicecaptions.CoffeesAeroVoiceCaptions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;

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

    /** Live captions can grow long — show the TAIL (what's being said now), like real subtitles. */
    private static final int MAX_CHARS = 44;

    /** One background worker for translation (blocking HTTP on cache miss) — never the render thread. */
    private static final java.util.concurrent.ExecutorService TRANSLATE_WORKER =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VoiceCaptions-Translate");
            t.setDaemon(true);
            return t;
        });

    /** Called on the client network thread's work queue when a caption packet arrives. */
    public static void receive(CaptionPayload payload) {
        String raw = payload.text();
        if (raw == null || raw.isBlank()) {
            CAPTIONS.remove(payload.speaker());
            return;
        }
        CAPTIONS.put(payload.speaker(), new Caption(clip(raw), System.currentTimeMillis() + TTL_MS));

        // Finished utterances ride the Translator mod (if installed + configured): translate off-thread,
        // then swap the caption in place — but only if a newer caption hasn't replaced it meanwhile.
        if (payload.isFinal() && TranslatorBridge.available()) {
            final UUID speaker = payload.speaker();
            final String shown = clip(raw);
            TRANSLATE_WORKER.submit(() -> TranslatorBridge.translate(raw).ifPresent(translated -> {
                Caption current = CAPTIONS.get(speaker);
                if (current != null && current.text().equals(shown)) {
                    CAPTIONS.put(speaker, new Caption(clip(translated), System.currentTimeMillis() + TTL_MS));
                }
            }));
        }
    }

    private static String clip(String text) {
        if (text.length() <= MAX_CHARS) return text;
        int cut = text.length() - MAX_CHARS;
        int space = text.indexOf(' ', cut);                  // trim at a word boundary when possible
        return "…" + text.substring(space > 0 && space < text.length() - 8 ? space + 1 : cut);
    }

    @SubscribeEvent
    static void onRenderNameTag(RenderNameTagEvent event) {
        if (!CaptionSettings.captionsEnabled()) return;   // per-player off switch (default on)
        if (!(event.getEntity() instanceof Player player)) return;
        Caption c = CAPTIONS.get(player.getUUID());
        if (c == null) return;
        if (System.currentTimeMillis() > c.expiresAt()) {
            CAPTIONS.remove(player.getUUID());
            return;
        }
        // Vanilla never renders YOUR own name tag — force it while you're captioned so you can see
        // your own bubble in third person (F5). First person keeps it off (it'd sit inside the
        // camera); the HUD subtitle below covers that view.
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player) {
            if (mc.options.getCameraType().isFirstPerson()) return;
            event.setCanRender(TriState.TRUE);
        }
        // Show the caption where the name tag renders (above the head). Speech-bubble styling.
        event.setContent(Component.literal("💬 ")
            .append(Component.literal(c.text()).withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC)));
    }

    /** First-person view of your own caption: a subtitle just above the hotbar. */
    @SubscribeEvent
    static void onHud(RenderGuiEvent.Post event) {
        if (!CaptionSettings.captionsEnabled()) return;   // per-player off switch (default on)
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!mc.options.getCameraType().isFirstPerson()) return;   // third person uses the head bubble
        Caption c = CAPTIONS.get(mc.player.getUUID());
        if (c == null || System.currentTimeMillis() > c.expiresAt()) return;
        GuiGraphics g = event.getGuiGraphics();
        Component line = Component.literal("💬 ")
            .append(Component.literal(c.text()).withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC));
        int w = mc.font.width(line);
        int x = (g.guiWidth() - w) / 2;
        int y = g.guiHeight() - 68;   // above hotbar + XP bar, below the crosshair area
        g.fill(x - 4, y - 3, x + w + 4, y + 10, 0x90000000);
        g.drawString(mc.font, line, x, y, 0xFFFFFF, true);
    }
}
