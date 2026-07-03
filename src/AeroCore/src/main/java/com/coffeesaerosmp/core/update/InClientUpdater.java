package com.coffeesaerosmp.core.update;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * In-client packwiz updater. Reads the live {@code pack.toml} + {@code index.toml} + metafiles, hashes
 * the local files, and downloads ONLY changed/missing files into a staging folder — all inside the game
 * with a progress bar (no external console, no packwiz GUI). The final swap (which needs the locked mod
 * jars released) is done by {@link Applier}, a windowless helper that runs after the game closes.
 *
 * <p>Progress is exposed via volatile fields polled by {@code UpdatingScreen}.</p>
 */
public final class InClientUpdater {

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroCore-Updater");
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

    // ── Progress (polled by the screen) ────────────────────────────────────────
    public static volatile String  phase    = "Starting…";
    public static volatile String  current  = "";
    public static volatile int     done     = 0;
    public static volatile int     total    = 0;
    public static volatile boolean finished = false;
    public static volatile boolean success  = false;
    public static volatile boolean willClose = false;   // true once the applier is launched (game must close)
    public static volatile String  error    = null;

    private InClientUpdater() {}

    /** A file we need to fetch: an absolute download URL, the local target path, and its expected hash. */
    private record Target(String url, Path target, String hash, String hashFormat) {}

    public static void start() {
        phase = "Starting…"; current = ""; done = 0; total = 0;
        finished = false; success = false; willClose = false; error = null;
        Thread t = new Thread(InClientUpdater::run, "AeroCore-InClientUpdater");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            String packUrl = AeroConfig.PACK_TOML_URL.get();
            if (packUrl == null || packUrl.isBlank()) throw new IllegalStateException("no pack.toml URL configured");
            String base = packUrl.substring(0, packUrl.lastIndexOf('/') + 1);
            Path gameDir = Minecraft.getInstance().gameDirectory.getCanonicalFile().toPath();
            Path staging = gameDir.resolve(".aero-update").resolve("staging");

            phase = "Reading pack…";
            Map<String, String> pack = parseToml(get(packUrl));
            String indexFile   = pack.getOrDefault("index.file", "index.toml");
            String indexUrl    = base + indexFile;
            String indexRaw    = get(indexUrl);
            String idxHashFmt  = firstValue(indexRaw, "hash-format", "sha256");

            phase = "Checking files…";
            List<Target> plan = new ArrayList<>();
            Set<String>  managed = new LinkedHashSet<>();         // target paths this update controls
            List<String[]> files = parseFilesArray(indexRaw);     // [file, hash, metafile]
            int scanned = 0;
            for (String[] f : files) {
                String file = f[0], hash = f[1]; boolean meta = "true".equals(f[2]);
                current = file; done = ++scanned; total = files.size();
                if (meta) {
                    Map<String, String> mf = parseToml(get(base + file));
                    String fname = mf.get("filename");
                    if (fname == null) continue;
                    String dlUrl = mf.get("download.url");
                    String dlHash = mf.get("download.hash");
                    String dlFmt  = mf.getOrDefault("download.hash-format", "sha512");
                    Path target = gameDir.resolve(parentDir(file)).resolve(fname);
                    managed.add(rel(gameDir, target));
                    if (dlUrl != null && !matches(target, dlHash, dlFmt))
                        plan.add(new Target(dlUrl, target, dlHash, dlFmt));
                } else {
                    // override file: strip the leading "overrides/" to get the in-instance target
                    String t = file.startsWith("overrides/") ? file.substring("overrides/".length()) : file;
                    Path target = gameDir.resolve(t);
                    managed.add(rel(gameDir, target));
                    if (!matches(target, hash, idxHashFmt))
                        plan.add(new Target(base + file, target, hash, idxHashFmt));
                }
            }

            // Orphans: files a previous in-client update installed that the pack no longer lists.
            List<String> removals = orphans(gameDir, managed);

            // mods/ is FULLY managed: any jar not in the pack index is stale — an old-version
            // leftover from a filename-changing release or from the original mrpack import (which
            // writes no manifest, so plain orphan tracking can never catch it). This is what left
            // CoffeesAeroCore-1.0.0.jar sitting next to 1.2.0 on Prism installs: both declared the
            // same mod version, and FML silently picked the OLD file.
            removals.addAll(staleModJars(gameDir, managed));

            if (plan.isEmpty() && removals.isEmpty()) {
                phase = "Already up to date."; success = true; finished = true; return;
            }

            // Download changed files into staging (mirroring their target-relative path).
            Files.createDirectories(staging);
            total = plan.size(); done = 0;
            for (Target tg : plan) {
                current = tg.target().getFileName().toString();
                phase = "Downloading " + (done + 1) + " / " + total;
                Path stagePath = staging.resolve(rel(gameDir, tg.target()));
                Files.createDirectories(stagePath.getParent());
                download(tg.url(), stagePath);
                if (tg.hash() != null && !hashMatches(stagePath, tg.hash(), tg.hashFormat()))
                    throw new IOException("hash mismatch after download: " + tg.target().getFileName());
                done++;
            }

            writeManifest(gameDir, managed);
            phase = "Applying… the game will close.";
            willClose = true;
            launchApplier(gameDir, staging, removals);
            success = true; finished = true;
        } catch (Exception e) {
            LOGGER.error("[Updater] in-client update failed", e);
            error = e.getMessage(); success = false; finished = true;
        }
    }

    // ── Applier hand-off ────────────────────────────────────────────────────────

    private static void launchApplier(Path gameDir, Path staging, List<String> removals) throws Exception {
        Path work = gameDir.resolve(".aero-update");
        Files.createDirectories(work);
        // Run the applier from a COPY of our own jar so it can freely replace the real one in mods/.
        Path selfJar = resolveSelfJar(gameDir);
        Path applierJar = work.resolve("applier.jar");
        Files.copy(selfJar, applierJar, StandardCopyOption.REPLACE_EXISTING);

        Path removalsFile = work.resolve("removals.txt");
        Files.write(removalsFile, removals, StandardCharsets.UTF_8);

        String javaw = javaw();
        long pid = ProcessHandle.current().pid();
        ProcessBuilder pb = new ProcessBuilder(javaw, "-cp", applierJar.toString(),
            "com.coffeesaerosmp.core.update.Applier",
            String.valueOf(pid), gameDir.toString(), staging.toString(), removalsFile.toString());
        pb.directory(gameDir.toFile());
        pb.redirectOutput(work.resolve("apply.log").toFile());
        pb.redirectError(work.resolve("apply.log").toFile());
        pb.start();
        LOGGER.info("[Updater] Windowless applier launched (waits on pid {}).", pid);
    }

    /** Locates our own jar to copy as the applier classpath. Falls back to scanning mods/ because
     *  NeoForge's module classloader doesn't always expose a plain file: code-source location. */
    private static Path resolveSelfJar(Path gameDir) throws IOException {
        try {
            var loc = InClientUpdater.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                Path p = Paths.get(loc.toURI());
                if (Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return p;
            }
        } catch (Exception ignored) {}
        Path mods = gameDir.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var s = Files.list(mods)) {
                Optional<Path> hit = s.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.startsWith("coffeesaerocore") && n.endsWith(".jar");
                }).findFirst();
                if (hit.isPresent()) return hit.get();
            }
        }
        throw new IOException("could not locate CoffeesAeroCore jar for the applier");
    }

    private static String javaw() {
        String home = System.getProperty("java.home", "");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path p = Paths.get(home, "bin", win ? "javaw.exe" : "java");
        return Files.exists(p) ? p.toString() : (win ? "javaw.exe" : "java");
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────

    private static String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Cache-Control", "no-cache").header("Pragma", "no-cache").GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    private static void download(String url, Path target) throws IOException, InterruptedException {
        HttpResponse<Path> r = HTTP.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("Cache-Control", "no-cache").GET().build(),
            HttpResponse.BodyHandlers.ofFile(target));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " downloading " + url);
    }

    // ── Hashing ─────────────────────────────────────────────────────────────────

    private static boolean matches(Path file, String expected, String fmt) {
        if (expected == null) return true;
        return Files.exists(file) && hashMatches(file, expected, fmt);
    }

    private static boolean hashMatches(Path file, String expected, String fmt) {
        try {
            MessageDigest md = MessageDigest.getInstance(
                fmt != null && fmt.toLowerCase(Locale.ROOT).contains("512") ? "SHA-512" : "SHA-256");
            byte[] buf = new byte[1 << 16];
            try (var in = Files.newInputStream(file)) {
                int n; while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expected.trim());
        } catch (Exception e) { return false; }
    }

    // ── Manifest / orphans ──────────────────────────────────────────────────────

    private static Path manifestPath(Path gameDir) { return gameDir.resolve(".aero-update").resolve("manifest.txt"); }

    private static void writeManifest(Path gameDir, Set<String> managed) throws IOException {
        Files.createDirectories(manifestPath(gameDir).getParent());
        Files.write(manifestPath(gameDir), managed, StandardCharsets.UTF_8);
    }

    /** Every *.jar under mods/ that the pack index doesn't manage. The pack is a closed set —
     *  a jar the index doesn't know is either an outdated leftover or breaks server negotiation. */
    private static List<String> staleModJars(Path gameDir, Set<String> managed) {
        List<String> out = new ArrayList<>();
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return out;
        try (Stream<Path> s = Files.list(mods)) {
            for (Path jar : (Iterable<Path>) s::iterator) {
                String name = jar.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                String rel = "mods/" + name;
                if (!managed.contains(rel)) out.add(rel);
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static List<String> orphans(Path gameDir, Set<String> nowManaged) {
        List<String> out = new ArrayList<>();
        Path mf = manifestPath(gameDir);
        if (!Files.exists(mf)) return out;
        try {
            for (String prev : Files.readAllLines(mf, StandardCharsets.UTF_8)) {
                prev = prev.trim();
                if (!prev.isEmpty() && !nowManaged.contains(prev) && Files.exists(gameDir.resolve(prev)))
                    out.add(prev);
            }
        } catch (IOException ignored) {}
        return out;
    }

    private static String rel(Path gameDir, Path target) {
        return gameDir.relativize(target).toString().replace('\\', '/');
    }

    private static String parentDir(String file) {
        int i = file.lastIndexOf('/');
        return i < 0 ? "" : file.substring(0, i);
    }

    // ── Minimal packwiz-TOML parsing (no external deps) ─────────────────────────

    /** Flattens a small TOML doc to "section.key" -> value (last [section] wins). */
    private static Map<String, String> parseToml(String toml) {
        Map<String, String> m = new HashMap<>();
        String section = "";
        for (String line : toml.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            if (s.startsWith("[") && s.endsWith("]")) { section = s.substring(1, s.length() - 1).trim(); continue; }
            int eq = s.indexOf('=');
            if (eq < 0) continue;
            String key = s.substring(0, eq).trim();
            String val = unquote(s.substring(eq + 1).trim());
            m.put(section.isEmpty() ? key : section + "." + key, val);
        }
        return m;
    }

    /** Parses repeated [[files]] blocks into [file, hash, metafile] triples. */
    private static List<String[]> parseFilesArray(String index) {
        List<String[]> out = new ArrayList<>();
        String file = null, hash = null, meta = "false"; boolean in = false;
        for (String line : index.split("\n")) {
            String s = line.trim();
            if (s.equals("[[files]]")) {
                if (in && file != null) out.add(new String[]{file, hash, meta});
                in = true; file = null; hash = null; meta = "false"; continue;
            }
            if (!in) continue;
            if (s.startsWith("[") && !s.startsWith("[[")) { // a non-files section ends the array
                if (file != null) out.add(new String[]{file, hash, meta});
                in = false; file = null; continue;
            }
            int eq = s.indexOf('=');
            if (eq < 0) continue;
            String k = s.substring(0, eq).trim(), v = unquote(s.substring(eq + 1).trim());
            switch (k) { case "file" -> file = v; case "hash" -> hash = v; case "metafile" -> meta = v; default -> {} }
        }
        if (in && file != null) out.add(new String[]{file, hash, meta});
        return out;
    }

    private static String firstValue(String toml, String key, String def) {
        Map<String, String> m = parseToml(toml);
        return m.getOrDefault(key, def);
    }

    private static String unquote(String v) {
        v = v.trim();
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) v = v.substring(1, v.length() - 1);
        return v;
    }
}
