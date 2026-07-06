package com.coffeesaerosmp.core.version;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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

    public enum State { UNKNOWN, UP_TO_DATE, OUTDATED, ERROR }

    private static volatile State  state         = State.UNKNOWN;
    private static volatile String latestVersion = "";
    private static volatile String downloadUrl   = "";
    private static volatile boolean started       = false;

    private VersionCheck() {}

    /** Kicks off the check once per game session. Safe to call repeatedly (e.g. every title-screen init). */
    public static void startAsync() {
        if (started) return;
        started = true;
        String url = AeroConfig.VERSION_CHECK_URL.get();
        if (url == null || url.isBlank()) { state = State.UNKNOWN; return; }
        Thread t = new Thread(() -> fetch(url), "AeroCore-VersionCheck");
        t.setDaemon(true);
        t.start();
    }

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
            downloadUrl   = o.has("url")     ? o.get("url").getAsString()     : "";

            String local = AeroConfig.PACK_VERSION.get();
            state = compare(local, latestVersion) < 0 ? State.OUTDATED : State.UP_TO_DATE;
        } catch (Exception e) {
            state = State.ERROR;
        }
    }

    public static boolean isOutdated()    { return state == State.OUTDATED; }
    public static String  latestVersion() { return latestVersion; }

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
