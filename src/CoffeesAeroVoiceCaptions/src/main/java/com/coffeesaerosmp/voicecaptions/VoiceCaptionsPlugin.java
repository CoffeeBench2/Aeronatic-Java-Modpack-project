package com.coffeesaerosmp.voicecaptions;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple Voice Chat plugin — prototype step 1.
 *
 * <p>Registers for {@link MicrophonePacketEvent} (fired per 20 ms Opus frame for each speaking
 * player) and decodes the Opus payload to raw PCM, proving the capture + decode pipeline works
 * before Vosk is added. It keeps a per-speaker {@link OpusDecoder} (stateful — frames must be
 * decoded in order per player) and logs a one-line summary at most once per second per speaker so
 * the server log shows "hearing" someone without spamming.</p>
 *
 * <p>Voice audio is 48 kHz mono; each 20 ms frame decodes to ~960 samples. Step 2 will resample to
 * 16 kHz and feed a Vosk recognizer; step 3 will broadcast the transcript to nearby clients.</p>
 */
@ForgeVoicechatPlugin
public class VoiceCaptionsPlugin implements VoicechatPlugin {

    private volatile VoicechatApi api;

    // Per-speaker Opus decoder (stateful) + light rate-limit for the proof-of-concept log.
    private final ConcurrentHashMap<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, long[]>      stats    = new ConcurrentHashMap<>(); // [lastLogMs, frames, samples]

    @Override
    public String getPluginId() {
        return CoffeesAeroVoiceCaptions.MODID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        CoffeesAeroVoiceCaptions.LOGGER.info("[VoiceCaptions] SVC plugin initialized — mic capture armed.");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (api == null) return;
        if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) return;

        UUID speaker = event.getSenderConnection().getPlayer().getUuid();
        byte[] opus = event.getPacket().getOpusEncodedData();
        if (opus == null || opus.length == 0) return;   // silence / keep-alive frame

        try {
            OpusDecoder decoder = decoders.computeIfAbsent(speaker, k -> api.createDecoder());
            short[] pcm = decoder.decode(opus);          // 48 kHz mono PCM for this 20 ms frame
            if (pcm == null) return;

            long now = System.currentTimeMillis();
            long[] s = stats.computeIfAbsent(speaker, k -> new long[]{0L, 0L, 0L});
            s[1]++;                 // frames
            s[2] += pcm.length;     // samples
            if (now - s[0] >= 1000L) {
                CoffeesAeroVoiceCaptions.LOGGER.info(
                    "[VoiceCaptions] capturing {} — {} frames / {} samples (~{} ms) this second [STT pipeline TODO]",
                    event.getSenderConnection().getPlayer().getUuid(),
                    s[1], s[2], s[2] * 1000L / 48000L);
                s[0] = now; s[1] = 0; s[2] = 0;
            }
        } catch (Exception e) {
            CoffeesAeroVoiceCaptions.LOGGER.debug("[VoiceCaptions] decode failed for {}: {}", speaker, e.toString());
        }
    }
}
