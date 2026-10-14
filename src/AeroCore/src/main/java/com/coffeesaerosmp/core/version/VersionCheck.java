package com.coffeesaerosmp.core.version;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Polls a small {@code version.json} (hosted in the pack repo) once per session and compares the
 * latest published pack version to the version baked into this client by the build script
 * ({@link AeroConfig#PACK_VERSION}). The Join button and the disconnect screen read {@link #isOutdated()}
 * to stop testers from connecting on a stale pack (which otherwise produces the raw registry-mismatch wall).
 *
 * <p>Fully fail-open: any network/parse error leaves the state UNKNOWN so legitimate players are never
 * blocked by a hiccup. The fetch runs on a daemon thread and never touches the render thread.</p>
 */
public final class VersionCheck {

    public enum State { UNKNOWN, CHECKING, UP_TO_DATE, OUTDATED, ERROR }

    private static volatile State  state         = State.UNKNOWN;
    private static volatile String latestVersion = "";
    /** NeoForge version the pack now requires, from version.json. Blank when the field is absent. */
    private static volatile String latestNeoForge = "";
    private static volatile String downloadUrl   = "";
    private static volatile boolean started       = false;

    private VersionCheck() {}

    /** Kicks off the check once per game session. Safe to call repeatedly (e.g. every title-screen init). */
    public static void startAsync() {
        if (started) return;
        started = true;
        spawn();
    }

    /**
     * Forces a fresh check, ignoring the once-per-session guard. Backs the manual "Updates" button.
     *
     * <p>The automatic check deliberately runs once — it fires from {@code init()}, which re-runs on
     * every window resize, and re-polling on each resize would be silly. A player pressing a button
     * is the opposite situation: they are asking BECAUSE they think the cached answer is stale, so
     * the guard has to be bypassed rather than worked around.
     *
     * <p>Re-entry is blocked while a fetch is in flight, so mashing the button cannot spawn threads.
     */
    public static void recheckAsync() {
        if (state == State.CHECKING) return;
        started = true;
        spawn();
    }

    private static void spawn() {
        String url = AeroConfig.VERSION_CHECK_URL.get();
        if (url == null || url.isBlank()) { state = State.UNKNOWN; return; }
        state = State.CHECKING;
        Thread t = new Thread(() -> fetch(url), "AeroCore-VersionCheck");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Current state as a plain String.
     *
     * <p>String rather than the enum on purpose: {@link com.coffeesaerosmp.core.UpdaterBridge} reads
     * this reflectively, and the CurseForge build ships without this package entirely. Returning an
     * enum would force the bridge to load a class that may not exist.
     */
    public static String stateName() { return state.name(); }

    private static void fetch(String url) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            // Cache-bust version.json too (like the updater): GitHub's raw CDN caches each path ~300s and
            // ignores no-cache, so without this a version.json flip isn't seen for ~5 min → players relaunch
            // and think nothing happened. One tiny request per session, so no rate-limit concern.
            String bustedUrl = url + (url.indexOf('?') < 0 ? "?" : "&") + "aerocb=" + System.nanoTime();
            HttpRequest req = HttpRequest.newBuilder(URI.create(bustedUrl))
                .timeout(Duration.ofSeconds(8)).header("Cache-Control", "no-cache").GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) { state = State.ERROR; return; }

            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            latestVersion = o.has("version") ? o.get("version").getAsString() : "";
            latestNeoForge = o.has("neoforge") ? o.get("neoforge").getAsString() : "";
            downloadUrl   = o.has("url")     ? o.get("url").getAsString()     : "";

            String local = AeroConfig.PACK_VERSION.get();
            state = compare(local, latestVersion) < 0 ? State.OUTDATED : State.UP_TO_DATE;

            // Remote one-shot switch for wiping Distant Horizons' stale LOD after a world reset.
            // Rides this fetch on purpose: it already runs once per session, off the render thread,
            // and cache-busted — so flipping the token in version.json takes effect the same day
            // without shipping a jar. See DhLodReset for why the trigger cannot be "on update".
            java.nio.file.Path gameDir = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath();
            com.coffeesaerosmp.core.client.DhLodReset.applyIfRequested(
                gameDir, o.has("lodReset") ? o.get("lodReset").getAsString() : "");

            // Remote retired-mod list. Cached for StaleMods, which sweeps from the mod constructor
            // before this fetch can finish — so a newly listed mod is queued on the next launch and
            // removed at that session's exit, with NO Core release. (The updater fetches it fresh
            // itself, so an update removes it in the same apply.)
            List<String> retired = parseRetired(resp.body());
            if (retired != null) cacheRetired(gameDir, retired);
        } catch (Exception e) {
            state = State.ERROR;
        }
    }

    /**
     * {@code "retired": ["prefix-", ...]} from version.json, lower-cased and filtered.
     * Returns {@code null} when the field is absent (keep whatever is cached), an empty list when it
     * is present and empty (clear the cache).
     */
    public static List<String> parseRetired(String versionJson) {
        try {
            JsonObject o = JsonParser.parseString(versionJson).getAsJsonObject();
            if (!o.has("retired") || !o.get("retired").isJsonArray()) return null;
            List<String> out = new ArrayList<>();
            for (var e : o.getAsJsonArray("retired")) {
                if (!e.isJsonPrimitive()) continue;
                String p = e.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
                // Same guards as AeroState.remoteRetiredPrefixes: no tiny prefixes, no paths.
                if (p.length() >= 4 && !p.contains("/") && !p.contains("\\")) out.add(p);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    public static void cacheRetired(java.nio.file.Path gameDir, List<String> prefixes) {
        try {
            com.coffeesaerosmp.core.util.AeroState.writeLines(
                com.coffeesaerosmp.core.util.AeroState.remoteRetired(gameDir), prefixes);
        } catch (Exception ignored) {
            // A failed cache write only delays a removal by one launch.
        }
    }

    public static boolean isOutdated()    { return state == State.OUTDATED; }
    public static String  latestVersion() { return latestVersion; }
    public static String  latestNeoForge() { return latestNeoForge; }

    public static String downloadUrl() {
        return (downloadUrl == null || downloadUrl.isBlank()) ? AeroConfig.UPDATE_URL.get() : downloadUrl;
    }

    /** Numeric-aware version compare on dotted segments. Returns &lt;0 if {@code a} is older than {@code b}. */
    static int compare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] pa = a.split("[.\\-+]");
        String[] pb = b.split("[.\\-+]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? num(pa[i]) : 0;
            int vb = i < pb.length ? num(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int num(String s) {
        String digits = s.replaceAll("\\D", "");
        if (digits.isEmpty()) return 0;
        try { return Integer.parseInt(digits); } catch (NumberFormatException e) { return 0; }
    }
}
