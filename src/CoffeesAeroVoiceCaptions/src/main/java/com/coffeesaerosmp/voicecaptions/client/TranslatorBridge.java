package com.coffeesaerosmp.voicecaptions.client;

import com.coffeesaerosmp.voicecaptions.CoffeesAeroVoiceCaptions;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Soft bridge to the pack's Translator mod (modid {@code translator}, kgg.translator) — entirely via
 * reflection so VoiceCaptions has NO compile or runtime dependency on it. When the mod is present and
 * the player has a target language configured (the same {@code TranslatorManager.getTo()} their chat
 * translation uses), final captions are run through {@code TranslateService.cachedTranslate} — cached,
 * so repeated phrases don't re-hit the endpoint. Absent mod / blank target / any error = no-op, the
 * original caption is shown.
 */
final class TranslatorBridge {

    private static final Method CACHED_TRANSLATE;
    private static final Method GET_TO;
    private static final boolean AVAILABLE;

    static {
        Method cached = null, getTo = null;
        boolean ok = false;
        try {
            if (ModList.get().isLoaded("translator")) {
                Class<?> svc = Class.forName("kgg.translator.TranslateService");
                Class<?> mgr = Class.forName("kgg.translator.TranslatorManager");
                cached = svc.getMethod("cachedTranslate", String.class, String.class);
                getTo  = mgr.getMethod("getTo");
                ok = true;
                CoffeesAeroVoiceCaptions.LOGGER.info(
                    "[VoiceCaptions] Translator mod detected — final captions will follow its target language.");
            }
        } catch (Throwable t) {
            CoffeesAeroVoiceCaptions.LOGGER.info(
                "[VoiceCaptions] Translator bridge unavailable ({}) — captions stay untranslated.", t.toString());
        }
        CACHED_TRANSLATE = cached;
        GET_TO = getTo;
        AVAILABLE = ok;
    }

    private TranslatorBridge() {}

    static boolean available() { return AVAILABLE; }

    /**
     * Translate to the player's configured target language. BLOCKING (does HTTP on a cache miss) —
     * only ever call from the caption worker thread. Empty = keep the original.
     */
    static Optional<String> translate(String text) {
        if (!AVAILABLE || text == null || text.isBlank()) return Optional.empty();
        try {
            String to = (String) GET_TO.invoke(null);
            if (to == null || to.isBlank()) return Optional.empty();
            String out = (String) CACHED_TRANSLATE.invoke(null, text, to);
            if (out == null || out.isBlank() || out.equalsIgnoreCase(text)) return Optional.empty();
            return Optional.of(out);
        } catch (Throwable t) {
            return Optional.empty();   // endpoint down / not configured — never break captions
        }
    }
}
