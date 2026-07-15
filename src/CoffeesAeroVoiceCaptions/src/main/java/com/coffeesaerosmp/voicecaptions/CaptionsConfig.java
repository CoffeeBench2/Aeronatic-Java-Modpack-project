package com.coffeesaerosmp.voicecaptions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side captions config — {@code config/coffees_voice_captions/captions.json}, auto-created
 * with defaults on first run. Read once at startup (restart to apply — matches how the Vosk model
 * itself loads). Every knob exists to protect the 6 GB production box:
 *
 * <ul>
 *   <li>{@code enabled} — master switch; off = capture-only, zero STT cost.</li>
 *   <li>{@code maxConcurrentSpeakers} — hard cap on simultaneous Vosk recognizers; speakers beyond
 *       the cap simply aren't transcribed until a slot frees (idle speakers are evicted).</li>
 *   <li>{@code livePartials} — stream in-progress text so captions appear WHILE talking; costs only
 *       network (Vosk computes partials either way), throttled by {@code partialMinIntervalMs}.</li>
 *   <li>{@code captionRangeBlocks} — 0 = follow the voice-chat distance; otherwise an override.</li>
 *   <li>{@code speakerIdleEvictSeconds} — recognizer+decoder for a speaker silent this long are
 *       closed and freed.</li>
 * </ul>
 */
public final class CaptionsConfig {

    public boolean enabled                 = true;
    public int     maxConcurrentSpeakers   = 4;
    public boolean livePartials            = true;
    public int     partialMinIntervalMs    = 350;
    public double  captionRangeBlocks      = 0;
    public int     speakerIdleEvictSeconds = 60;

    private static volatile CaptionsConfig instance;

    public static CaptionsConfig get() {
        CaptionsConfig c = instance;
        if (c == null) {
            synchronized (CaptionsConfig.class) {
                if (instance == null) instance = load();
                c = instance;
            }
        }
        return c;
    }

    private static CaptionsConfig load() {
        CaptionsConfig defaults = new CaptionsConfig();
        Path file = FMLPaths.CONFIGDIR.get().resolve("coffees_voice_captions").resolve("captions.json");
        try {
            if (Files.isRegularFile(file)) {
                JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                CaptionsConfig c = new CaptionsConfig();
                if (o.has("enabled"))                 c.enabled                 = o.get("enabled").getAsBoolean();
                if (o.has("maxConcurrentSpeakers"))   c.maxConcurrentSpeakers   = Math.max(1, o.get("maxConcurrentSpeakers").getAsInt());
                if (o.has("livePartials"))            c.livePartials            = o.get("livePartials").getAsBoolean();
                if (o.has("partialMinIntervalMs"))    c.partialMinIntervalMs    = Math.max(100, o.get("partialMinIntervalMs").getAsInt());
                if (o.has("captionRangeBlocks"))      c.captionRangeBlocks      = Math.max(0, o.get("captionRangeBlocks").getAsDouble());
                if (o.has("speakerIdleEvictSeconds")) c.speakerIdleEvictSeconds = Math.max(10, o.get("speakerIdleEvictSeconds").getAsInt());
                CoffeesAeroVoiceCaptions.LOGGER.info("[VoiceCaptions] config loaded: {}", file);
                return c;
            }
            Files.createDirectories(file.getParent());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonObject o = new JsonObject();
            o.addProperty("_comment", "Voice captions (accessibility). enabled=master switch; "
                + "maxConcurrentSpeakers caps CPU (extra speakers aren't transcribed); livePartials "
                + "streams text while talking; captionRangeBlocks 0 = voice-chat distance; restart to apply.");
            o.addProperty("enabled",                 defaults.enabled);
            o.addProperty("maxConcurrentSpeakers",   defaults.maxConcurrentSpeakers);
            o.addProperty("livePartials",            defaults.livePartials);
            o.addProperty("partialMinIntervalMs",    defaults.partialMinIntervalMs);
            o.addProperty("captionRangeBlocks",      defaults.captionRangeBlocks);
            o.addProperty("speakerIdleEvictSeconds", defaults.speakerIdleEvictSeconds);
            Files.writeString(file, gson.toJson(o));
            CoffeesAeroVoiceCaptions.LOGGER.info("[VoiceCaptions] wrote default config: {}", file);
        } catch (Throwable t) {
            CoffeesAeroVoiceCaptions.LOGGER.warn("[VoiceCaptions] config load failed ({}), using defaults", t.toString());
        }
        return defaults;
    }

    private CaptionsConfig() {}
}
