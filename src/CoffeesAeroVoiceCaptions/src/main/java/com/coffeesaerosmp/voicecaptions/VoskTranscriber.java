package com.coffeesaerosmp.voicecaptions;

import net.neoforged.fml.loading.FMLPaths;
import org.vosk.LogLevel;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline speech-to-text via Vosk (prototype step 2). Wraps a shared {@link Model} loaded once from
 * {@code config/coffees_voice_captions/model/} — if that directory is absent, STT degrades to OFF
 * (the plugin still captures audio) and logs a one-time note telling the admin what to drop in.
 *
 * <p>A {@link Recognizer} is per-speaker (Vosk recognizers are stateful and single-threaded). Vosk
 * wants 16 kHz, 16-bit, mono, little-endian PCM; callers hand us 16 kHz {@code short[]} (already
 * decimated from voice-chat's 48 kHz) and we pack it to bytes.</p>
 */
public final class VoskTranscriber {

    /** Voice chat decodes to 48 kHz; Vosk wants 16 kHz. Integer 3:1 decimation. */
    public static final int VOICE_SAMPLE_RATE = 48000;
    public static final int VOSK_SAMPLE_RATE  = 16000;
    public static final int DECIMATION        = VOICE_SAMPLE_RATE / VOSK_SAMPLE_RATE;   // 3

    private static volatile VoskTranscriber instance;
    private static volatile boolean triedInit;

    private final Model model;

    private VoskTranscriber(Model model) { this.model = model; }

    /** Shared instance, or {@code null} when Vosk/model are unavailable (STT disabled). */
    public static VoskTranscriber get() {
        if (instance == null && !triedInit) init();
        return instance;
    }

    private static synchronized void init() {
        if (triedInit) return;
        triedInit = true;
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve("coffees_voice_captions").resolve("model");
            if (!Files.isDirectory(dir)) {
                CoffeesAeroVoiceCaptions.LOGGER.warn(
                    "[VoiceCaptions] No Vosk model at {} — speech-to-text DISABLED (audio still captured). "
                    + "Drop an unzipped model there (e.g. vosk-model-small-en-us-0.15) to enable captions.", dir);
                return;
            }
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            Model m = new Model(dir.toString());
            instance = new VoskTranscriber(m);
            CoffeesAeroVoiceCaptions.LOGGER.info("[VoiceCaptions] Vosk model loaded from {} — STT enabled.", dir);
        } catch (Throwable t) {
            // Native lib missing / model corrupt / OOM — never break the server; just no captions.
            CoffeesAeroVoiceCaptions.LOGGER.error(
                "[VoiceCaptions] Vosk init failed — speech-to-text disabled: {}", t.toString());
        }
    }

    /** New per-speaker recognizer at the Vosk sample rate. Caller owns/closes it. */
    public Recognizer newRecognizer() throws Exception {
        return new Recognizer(model, (float) VOSK_SAMPLE_RATE);
    }

    /** Decimate 48 kHz {@code short[]} → 16 kHz little-endian 16-bit PCM bytes for Vosk. */
    public static byte[] to16kBytes(short[] pcm48) {
        int n = pcm48.length / DECIMATION;
        byte[] out = new byte[n * 2];
        for (int i = 0, o = 0; i < n; i++) {
            short s = pcm48[i * DECIMATION];   // naive decimation (fine for the prototype)
            out[o++] = (byte) (s & 0xFF);
            out[o++] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }
}
