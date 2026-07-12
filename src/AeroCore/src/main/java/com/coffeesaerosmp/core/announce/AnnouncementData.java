package com.coffeesaerosmp.core.announce;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the pack changelog for the main-menu Announcements screen.
 *
 * <p>BUNDLED, not fetched: the changelog ships WITH the pack (so it arrives exactly when an update
 * does) — no runtime GitHub call, which keeps the CurseForge "no external fetch / no git dependency"
 * rule intact and works offline. Source order: {@code config/coffees_aero_announcements.json} (the
 * pack ships this via overrides — editable per update without a Core rebuild), falling back to a
 * copy bundled in this jar so a fresh install always has something to show.</p>
 *
 * <p>Newest entry first. Schema:
 * <pre>{ "entries": [ { "version": "1.8.0", "date": "2026-07-14", "title": "...",
 *   "added": [..], "fixed": [..], "removed": [..] }, ... ] }</pre></p>
 */
public final class AnnouncementData {

    public record Entry(String version, String date, String title,
                        List<String> added, List<String> fixed, List<String> removed) {
        public boolean isEmpty() { return added.isEmpty() && fixed.isEmpty() && removed.isEmpty(); }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroCore-Announce");
    private static final String CONFIG_FILE   = "coffees_aero_announcements.json";
    private static final String BUNDLED_PATH  = "/announcements.json";   // in Core's jar resources

    private static volatile List<Entry> entries;

    private AnnouncementData() {}

    public static List<Entry> entries() {
        if (entries == null) load();
        return entries;
    }

    /** Newest entry, or {@code null} if the changelog is empty/unreadable. */
    public static Entry latest() {
        List<Entry> e = entries();
        return e.isEmpty() ? null : e.get(0);
    }

    /** Force a re-read (e.g. after a pack update swaps the config file mid-session). */
    public static void reload() { entries = null; }

    private static synchronized void load() {
        if (entries != null) return;
        String json = readConfig();
        if (json == null) json = readBundled();
        entries = json == null ? List.of() : parse(json);
    }

    private static String readConfig() {
        try {
            Path f = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE);
            if (Files.isRegularFile(f)) return Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Announce] config read failed: {}", e.getMessage());
        }
        return null;
    }

    private static String readBundled() {
        try (InputStream in = AnnouncementData.class.getResourceAsStream(BUNDLED_PATH)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Announce] bundled read failed: {}", e.getMessage());
        }
        return null;
    }

    private static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("entries");
            if (arr != null) for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                out.add(new Entry(
                    str(o, "version"), str(o, "date"), str(o, "title"),
                    list(o, "added"), list(o, "fixed"), list(o, "removed")));
            }
        } catch (Exception e) {
            LOGGER.warn("[Announce] parse failed: {}", e.getMessage());
        }
        return List.copyOf(out);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static List<String> list(JsonObject o, String k) {
        List<String> l = new ArrayList<>();
        if (o.has(k) && o.get(k).isJsonArray())
            for (JsonElement e : o.getAsJsonArray(k)) l.add(e.getAsString());
        return l;
    }
}
