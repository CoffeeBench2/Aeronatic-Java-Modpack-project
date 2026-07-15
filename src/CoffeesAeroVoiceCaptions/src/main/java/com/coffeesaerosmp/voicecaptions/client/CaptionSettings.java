package com.coffeesaerosmp.voicecaptions.client;

import com.coffeesaerosmp.voicecaptions.CoffeesAeroVoiceCaptions;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side, per-player caption preference. Captions are ON by default; a player can turn them off
 * with the "Toggle Voice Captions" keybind (Options → Controls). The choice persists across restarts
 * in {@code config/coffees_voice_captions_client.properties} — a plain properties file, no config-lib
 * dependency. This is purely a render gate: the server keeps transcribing and sending; a client that
 * has captions off just doesn't draw them.
 */
public final class CaptionSettings {

    private CaptionSettings() {}

    private static final Path FILE =
        FMLPaths.CONFIGDIR.get().resolve("coffees_voice_captions_client.properties");

    private static volatile boolean enabled = true;
    private static volatile boolean loaded  = false;

    public static boolean captionsEnabled() {
        if (!loaded) load();
        return enabled;
    }

    /** Flip the setting and persist. Returns the new state. */
    public static boolean toggle() {
        if (!loaded) load();
        enabled = !enabled;
        save();
        return enabled;
    }

    private static synchronized void load() {
        if (loaded) return;
        try {
            if (Files.exists(FILE)) {
                Properties p = new Properties();
                try (var in = Files.newInputStream(FILE)) { p.load(in); }
                enabled = Boolean.parseBoolean(p.getProperty("captionsEnabled", "true"));
            }
        } catch (Exception e) {
            CoffeesAeroVoiceCaptions.LOGGER.warn("[VoiceCaptions] Could not read client settings: {}", e.getMessage());
        }
        loaded = true;
    }

    private static synchronized void save() {
        try {
            Properties p = new Properties();
            p.setProperty("captionsEnabled", Boolean.toString(enabled));
            Files.createDirectories(FILE.getParent());
            try (var out = Files.newOutputStream(FILE)) {
                p.store(out, "Coffees Aero Voice Captions — client display preference");
            }
        } catch (Exception e) {
            CoffeesAeroVoiceCaptions.LOGGER.warn("[VoiceCaptions] Could not save client settings: {}", e.getMessage());
        }
    }
}
