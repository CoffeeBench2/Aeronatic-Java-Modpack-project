package com.coffeesaerosmp.core.announce;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the pack changelog for the main-menu Announcements screen.
 *
 * <p>LIVE from GitHub, with an offline fallback: on the title screen the client fetches the News JSON
 * from {@link AeroConfig#NEWS_URL} (raw GitHub {@code main}) so the news can be edited on GitHub and
 * seen by players immediately — no pack rebuild, no version bump, same pack version. The fetch reuses
 * the updater's cache-busting ({@code ?aerocb=}) because GitHub's raw CDN caches each path ~300s and
 * ignores {@code no-cache}. If the fetch fails or the player is offline, it falls back to the bundled
 * {@code config/coffees_aero_announcements.json} (shipped via overrides), then to a copy baked into
 * this jar — so the scroll never renders empty. The CF build keeps the local files, so CF's
 * "self-contained" pack still shows news offline; the live fetch is purely additive.</p>
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
    private static volatile boolean githubTried = false;
    private static volatile Runnable onUpdated;   // screen sets this to re-layout when live news lands

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
    public static void reload() { entries = null; githubTried = false; }

    /** Called by the screen so it can relayout when the async GitHub news arrives. */
    public static void setOnUpdated(Runnable r) { onUpdated = r; }

    private static synchronized void load() {
        if (entries != null) return;
        String json = readConfig();
        if (json == null) json = readBundled();
        entries = json == null ? List.of() : parse(json);
    }

    /**
     * Kick off a one-shot, off-thread fetch of the live news from GitHub. Safe to call repeatedly
     * (only the first per session hits the network). On success it replaces {@link #entries} with the
     * live copy and fires {@link #onUpdated}; on any failure it leaves the local fallback in place.
     */
    public static void refreshFromGitHub() {
        if (githubTried) return;
        githubTried = true;
        String url = AeroConfig.NEWS_URL.get();
        if (url == null || url.isBlank()) return;
        Thread t = new Thread(() -> fetchGitHub(url), "AeroCore-News");
        t.setDaemon(true);
        t.start();
    }

    private static void fetchGitHub(String url) {
        try {
            // Cache-bust: raw.githubusercontent caches each path ~300s and ignores no-cache, so an edit
            // to the news on main wouldn't be seen for ~5 min without this. One request per session.
            String busted = url + (url.indexOf('?') < 0 ? "?" : "&") + "aerocb=" + System.nanoTime();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(busted))
                .timeout(Duration.ofSeconds(8))
                .header("Cache-Control", "no-cache").header("Pragma", "no-cache").GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOGGER.info("[Announce] live news HTTP {} — using local copy.", resp.statusCode());
                return;
            }
            List<Entry> live = parse(resp.body());
            if (live.isEmpty()) return;   // don't blank out a good local copy with an empty/broken fetch
            entries = live;
            LOGGER.info("[Announce] loaded {} live news entries from GitHub.", live.size());
            Runnable cb = onUpdated;
            if (cb != null) cb.run();
        } catch (Exception e) {
            LOGGER.info("[Announce] live news fetch failed ({}) — using local copy.", e.getMessage());
        }
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
