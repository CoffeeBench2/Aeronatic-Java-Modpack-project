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

    /**
     * One release on the News screen.
     *
     * <p>Only {@code version}/{@code date}/{@code title} and the three lists are required — every
     * field added for the 1.10.9 News redesign ({@code tag}, {@code body}, {@code banner},
     * {@code images}, {@code linkLabel}/{@code linkUrl}) defaults to empty, so a document written
     * for the old schema still parses and still renders. That matters because the file is fetched
     * live from GitHub and an older client can be reading a newer document at any time.
     */
    public record Entry(String version, String date, String title,
                        List<String> added, List<String> fixed, List<String> removed,
                        String tag, String body, String banner, List<String> images,
                        String linkLabel, String linkUrl) {

        public boolean isEmpty() {
            return added.isEmpty() && fixed.isEmpty() && removed.isEmpty()
                && body.isBlank() && banner.isBlank() && images.isEmpty();
        }

        public boolean hasMedia() { return !banner.isBlank() || !images.isEmpty(); }
        public boolean hasLink()  { return !linkLabel.isBlank() && !linkUrl.isBlank(); }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroCore-Announce");
    private static final String CONFIG_FILE   = "coffees_aero_announcements.json";
    private static final String BUNDLED_PATH  = "/announcements.json";   // in Core's jar resources

    private static volatile List<Entry> entries;
    private static volatile boolean githubTried = false;
    /** True once the live fetch has finished — succeeded, failed, or was never attempted. */
    private static volatile boolean githubSettled = false;
    private static volatile Runnable onUpdated;   // screen sets this to re-layout when live news lands

    private AnnouncementData() {}

    public static List<Entry> entries() {
        if (entries == null) load();
        return entries;
    }

    /** Newest entry of ANY kind — including a teaser. Use for DISPLAY order only, never as a key. */
    public static Entry latest() {
        List<Entry> e = entries();
        return e.isEmpty() ? null : e.get(0);
    }

    /**
     * True when this entry is a shipped release rather than a forward-looking teaser.
     *
     * <p>The convention has always been documented in the JSON ("entries whose version does NOT start
     * with a digit render as a TEASER") but was never expressed in code, so every consumer treated
     * {@code "On the horizon…"} as if it were a version number.
     */
    public static boolean isRelease(Entry e) {
        if (e == null) return false;
        String v = e.version();
        return v != null && !v.isBlank() && Character.isDigit(v.trim().charAt(0));
    }

    /**
     * Newest REAL release, skipping teasers — the only thing safe to use as the seen-state key.
     *
     * <h2>🔴 Why this exists (bug found 2026-09-09)</h2>
     * The "What's New" popup and the NEW badge keyed off {@link #latest()}, i.e. {@code entries[0]},
     * and {@code entries[0]} is frequently a <b>teaser</b> whose version is a fixed label. Dismissing
     * the popup wrote {@code "On the horizon…"} into the seen-file — and because a teaser's version
     * string never changes, {@code hasUnseen()} compared equal on every subsequent launch, forever.
     * The popup was permanently dead and <b>every real release after it was silently skipped</b>.
     * Confirmed from a live client: seen-file held {@code "On the horizon…"} and so did
     * {@code entries[0]}.
     *
     * <p>Keying on the newest real release also self-heals those poisoned seen-files: the stored
     * teaser label can never equal a release version, so the next launch shows the popup once and
     * writes a proper version.
     *
     * @return the newest entry with a digit-leading version, or {@code null} if there is none
     */
    public static Entry latestRelease() {
        for (Entry e : entries()) if (isRelease(e)) return e;
        return null;
    }

    /** Force a re-read (e.g. after a pack update swaps the config file mid-session). */
    public static void reload() { entries = null; githubTried = false; githubSettled = false; }

    /**
     * True once the live-news fetch has finished — succeeded, failed, or was never started.
     *
     * <h2>Why the What's New popup must wait for this</h2>
     * The popup used to decide during the title screen's {@code init()}, which reliably beat the
     * off-thread GitHub fetch. So the decision was always made from the LOCAL config, and the local
     * config can be badly stale — on a real client (2026-09-09) it was still on 1.8.0 while the live
     * news had 1.10.12. Combined with a teaser sitting at {@code entries[0]}, that is how "nothing
     * shows up" happened. Waiting for the fetch means the player is shown the release they actually
     * have, not whatever their config file last happened to contain.
     *
     * <p>The caller pairs this with its own deadline so an offline or slow client still gets the
     * popup from the local copy rather than never getting one.
     */
    public static boolean newsSettled() { return githubSettled; }

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
        if (url == null || url.isBlank()) { githubSettled = true; return; }
        Thread t = new Thread(() -> { try { fetchGitHub(url); } finally { githubSettled = true; } },
            "AeroCore-News");
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
                JsonObject link = o.has("link") && o.get("link").isJsonObject()
                    ? o.getAsJsonObject("link") : null;
                out.add(new Entry(
                    str(o, "version"), str(o, "date"), str(o, "title"),
                    list(o, "added"), list(o, "fixed"), list(o, "removed"),
                    str(o, "tag"), str(o, "body"), str(o, "banner"), list(o, "images"),
                    link == null ? "" : str(link, "label"),
                    link == null ? "" : str(link, "url")));
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
