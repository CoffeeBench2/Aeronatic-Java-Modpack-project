package com.coffeesaerosmp.voicecaptions;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import org.vosk.Recognizer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple Voice Chat plugin — prototype steps 1-2.
 *
 * <p>Per 20 ms Opus frame from a speaker: decode → 48 kHz PCM (step 1) → decimate to 16 kHz and feed
 * a per-speaker Vosk recognizer → log partial/final transcripts (step 2). Step 3 will broadcast the
 * final text to nearby clients to render above the speaker's head.</p>
 *
 * <p>Everything is per-speaker and stateful (Opus decoder + Vosk recognizer must see frames in
 * order). If Vosk/model is unavailable, transcription silently no-ops and only capture stats log —
 * the server is never put at risk by the STT layer.</p>
 */
@ForgeVoicechatPlugin
public class VoiceCaptionsPlugin implements VoicechatPlugin {

    private volatile VoicechatApi api;

    private final ConcurrentHashMap<UUID, OpusDecoder> decoders    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Recognizer>  recognizers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String>      lastPartial = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() {
        return CoffeesAeroVoiceCaptions.MODID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        boolean stt = VoskTranscriber.get() != null;
        CoffeesAeroVoiceCaptions.LOGGER.info(
            "[VoiceCaptions] SVC plugin initialized — mic capture armed. Speech-to-text: {}.",
            stt ? "ENABLED (Vosk)" : "disabled (no model)");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (api == null) return;
        if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) return;

        UUID speaker = event.getSenderConnection().getPlayer().getUuid();
        Object mcPlayer = event.getSenderConnection().getPlayer().getPlayer();   // underlying MC ServerPlayer
        byte[] opus = event.getPacket().getOpusEncodedData();

        // Empty payload = end-of-speech marker: flush the recognizer's final result, then reset.
        if (opus == null || opus.length == 0) {
            finishUtterance(speaker, mcPlayer);
            return;
        }

        try {
            OpusDecoder decoder = decoders.computeIfAbsent(speaker, k -> api.createDecoder());
            short[] pcm48 = decoder.decode(opus);
            if (pcm48 == null || pcm48.length == 0) return;

            VoskTranscriber vosk = VoskTranscriber.get();
            if (vosk == null) return;   // capture-only (no model) — step 1 behaviour

            Recognizer rec = recognizers.get(speaker);
            if (rec == null) {
                rec = vosk.newRecognizer();
                recognizers.put(speaker, rec);
            }
            byte[] pcm16 = VoskTranscriber.to16kBytes(pcm48);
            if (rec.acceptWaveForm(pcm16, pcm16.length)) {
                logText(speaker, mcPlayer, "final", rec.getResult());   // utterance boundary detected
                lastPartial.remove(speaker);
            } else {
                String partial = extract(rec.getPartialResult(), "partial");
                if (!partial.isBlank() && !partial.equals(lastPartial.get(speaker))) {
                    lastPartial.put(speaker, partial);
                    CoffeesAeroVoiceCaptions.LOGGER.info("[VoiceCaptions] {} (partial): {}", speaker, partial);
                }
            }
        } catch (Throwable t) {
            CoffeesAeroVoiceCaptions.LOGGER.debug("[VoiceCaptions] transcribe error for {}: {}", speaker, t.toString());
        }
    }

    private void finishUtterance(UUID speaker, Object mcPlayer) {
        Recognizer rec = recognizers.get(speaker);
        if (rec == null) return;
        try {
            logText(speaker, mcPlayer, "final", rec.getFinalResult());
        } catch (Throwable ignored) {
        } finally {
            lastPartial.remove(speaker);
        }
    }

    private void logText(UUID speaker, Object mcPlayer, String kind, String json) {
        String text = extract(json, "text");
        if (text.isBlank()) return;
        CoffeesAeroVoiceCaptions.LOGGER.debug("[VoiceCaptions] {} ({}): {}", speaker, kind, text);
        broadcastCaption(speaker, mcPlayer, text);
    }

    /** Send the caption to every player near the speaker (within voice-chat distance), on the server thread. */
    private void broadcastCaption(UUID speaker, Object mcPlayer, String text) {
        if (!(mcPlayer instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        net.minecraft.server.MinecraftServer server = sp.getServer();
        if (server == null) return;
        double range = api != null ? api.getVoiceChatDistance() : 48.0;
        final double r2 = (range > 0 ? range : 48.0) * (range > 0 ? range : 48.0);
        server.execute(() -> {
            try {
                var level = sp.level();
                var pos = sp.position();
                CaptionPayload payload = new CaptionPayload(speaker, text);
                for (net.minecraft.server.level.ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                    if (viewer.level() != level) continue;
                    if (viewer.position().distanceToSqr(pos) > r2) continue;
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer, payload);
                }
            } catch (Throwable t) {
                CoffeesAeroVoiceCaptions.LOGGER.debug("[VoiceCaptions] caption broadcast failed: {}", t.toString());
            }
        });
    }

    private static String extract(String voskJson, String field) {
        try {
            JsonObject o = JsonParser.parseString(voskJson).getAsJsonObject();
            return o.has(field) ? o.get(field).getAsString() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
