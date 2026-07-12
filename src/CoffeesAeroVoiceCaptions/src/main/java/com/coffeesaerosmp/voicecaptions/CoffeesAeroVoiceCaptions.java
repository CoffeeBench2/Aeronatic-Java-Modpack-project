package com.coffeesaerosmp.voicecaptions;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coffees Aero Voice Captions — speech-to-text for proximity voice chat, rendered as a caption above
 * the speaker's head for nearby players (accessibility: for players who can't hear or don't
 * understand spoken audio).
 *
 * <p><b>Prototype status:</b> step 1 — a Simple Voice Chat plugin that captures each speaker's
 * microphone audio and logs frame stats, to prove the API + Opus-decode pipeline before wiring in
 * offline Vosk speech recognition (step 2) and the client-side caption render (step 3). See the
 * design doc {@code planning/voice-captions-design.md}.</p>
 */
@Mod(CoffeesAeroVoiceCaptions.MODID)
public class CoffeesAeroVoiceCaptions {

    public static final String MODID = "coffeesaerovoicecaptions";
    public static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroVoiceCaptions");

    public CoffeesAeroVoiceCaptions() {
        LOGGER.info("[VoiceCaptions] Loaded (prototype). Voice-chat plugin registers via SVC's service discovery.");
    }
}
