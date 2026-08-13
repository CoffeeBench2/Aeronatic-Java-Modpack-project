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

    /** Concurrent index scans. Modest on purpose — enough to hide per-request latency without
     *  looking like a burst to the CDN that already 429'd us once. */
    private static final int SCAN_THREADS  = 8;
    private static final int HTTP_ATTEMPTS = 4;

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
            // From here on, key raw fetches on the index hash (see bust()): one cached copy of index.toml
            // + every metafile serves the whole player base per release, instead of each client punching
            // origin ~150× — the 429 that broke 1.7.3. pack.toml above still used a unique key (fresh).
            bustToken = pack.get("index.hash");
            String indexFile   = pack.getOrDefault("index.file", "index.toml");
            String indexUrl    = base + indexFile;
            String indexRaw    = get(indexUrl);
            String idxHashFmt  = firstValue(indexRaw, "hash-format", "sha256");

            // Integrity gate: the fetched index MUST match the hash pack.toml declares for it. With
            // cache-busting both come fresh from origin, so a failure here means the pushed pack is
            // genuinely inconsistent (index not refreshed after an edit) — fail loud rather than march
            // on with stale per-file hashes and surface a misleading "hash mismatch after download".
            String declaredIdxHash = pack.get("index.hash");
            if (declaredIdxHash != null && !hashStringMatches(indexRaw, declaredIdxHash,
                    pack.getOrDefault("index.hash-format", "sha256")))
                throw new IOException("pack index is inconsistent (index.toml does not match pack.toml)"
                    + " — the pack was published without a packwiz refresh; try again shortly");

            phase = "Checking files…";
            List<String[]> files = parseFilesArray(indexRaw);      // [file, hash, metafile]

            // PARALLEL SCAN. Every metafile is its own HTTPS round-trip and every local jar has to be
            // hashed, so doing this one-at-a-time meant ~150 sequential round-trips plus ~1-2 GB of
            // SHA-512 before a single byte was downloaded — the bulk of the "checking" wait. Results
            // land in a positional array so the manifest order stays deterministic despite the pool.
            Scan[] scans = new Scan[files.size()];
            total = files.size(); done = 0;
            java.util.concurrent.atomic.AtomicInteger progress = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(SCAN_THREADS, r -> {
                Thread t = new Thread(r, "AeroCore-Scan");
                t.setDaemon(true);
                return t;
            });
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < files.size(); i++) {
                    final int idx = i;
                    futures.add(pool.submit(() -> {
                        scans[idx] = scanOne(files.get(idx), gameDir, base, idxHashFmt);
                        current = files.get(idx)[0];
                        done = progress.incrementAndGet();
                        return null;
                    }));
                }
                for (var fut : futures) {
                    try { fut.get(); }
                    catch (java.util.concurrent.ExecutionException ee) {
                        Throwable c = ee.getCause();
                        throw (c instanceof Exception ex) ? ex : new IOException(String.valueOf(c));
                    }
                }
            } finally {
                pool.shutdownNow();
            }

            List<Target> plan = new ArrayList<>();
            Set<String>  managed = new LinkedHashSet<>();          // target paths this update controls
            for (Scan s : scans) {
                if (s == null) continue;                           // metafile with no filename
                managed.add(s.managedRel());
                if (s.target() != null) plan.add(s.target());
            }

            // Orphans: files a previous in-client update installed that the pack no longer lists.
            List<String> removals = orphans(gameDir, managed);

            // Duplicate jars of a mod the pack DOES manage — e.g. CoffeesAeroCore-1.0.0.jar sitting
            // next to 1.3.3 after a filename-changing release. Both declare the same mod id and FML
            // silently loads the OLD one. The original mrpack import writes no manifest, so orphan
            // tracking alone can never catch these.
            removals.addAll(duplicateModJars(gameDir, managed));

            // Mods the pack has DROPPED outright. Neither mechanism above can catch these: orphan
            // tracking only sees files a previous in-client update installed (an mrpack or CF import
            // writes no manifest at all), and duplicate detection only fires when the pack still
            // manages some version of the same mod. A dropped mod has neither, so on a player's first
            // update it would silently survive into the new pack — leaving them running content the
            // server no longer has.
            removals.addAll(retiredMods(gameDir));
            removals.addAll(retiredFiles(gameDir));

            if (plan.isEmpty() && removals.isEmpty()) {
                phase = "Already up to date."; success = true; finished = true; return;
            }

            // Download changed files into staging (mirroring their target-relative path).
            // WIPE FIRST. Staging used to persist across runs, and the Applier copies whatever it
            // finds there WITHOUT re-verifying — so a file that failed its hash check in a previous
            // run sat around and got applied by the next successful one. That is a silent path to a
            // corrupt jar in mods/, and it is why this directory starts empty every time.
            deleteRecursively(staging);
            Files.createDirectories(staging);
            total = plan.size(); done = 0; current = "";
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
            // getMessage() is null for plenty of exceptions (NPE being the classic), and the screen
            // rendered that as a literal "null" with no clue what went wrong.
            String m = e.getMessage();
            error = (m == null || m.isBlank()) ? e.getClass().getSimpleName() : m;
            success = false; finished = true;
        }
    }

    /** One index entry resolved: the path it manages, and a download Target when it is out of date. */
    private record Scan(String managedRel, Target target) {}

    /** Runs on the scan pool — fetches the metafile if needed and hashes the local file. */
    private static Scan scanOne(String[] f, Path gameDir, String base, String idxHashFmt) throws Exception {
        String file = f[0], hash = f[1];
        boolean meta = "true".equals(f[2]);
        if (meta) {
            Map<String, String> mf = parseToml(get(base + file));
            String fname = mf.get("filename");
            if (fname == null) return null;
            String dlUrl  = mf.get("download.url");
            String dlHash = mf.get("download.hash");
            String dlFmt  = mf.getOrDefault("download.hash-format", "sha512");
            Path target = gameDir.resolve(parentDir(file)).resolve(fname);
            Target t = (dlUrl != null && !matches(target, dlHash, dlFmt))
                ? new Target(dlUrl, target, dlHash, dlFmt) : null;
            return new Scan(rel(gameDir, target), t);
        }
        // override file: strip the leading "overrides/" to get the in-instance target
        String rel = file.startsWith("overrides/") ? file.substring("overrides/".length()) : file;
        Path target = gameDir.resolve(rel);
        Target t = matches(target, hash, idxHashFmt) ? null : new Target(base + file, target, hash, idxHashFmt);
        return new Scan(rel(gameDir, target), t);
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

    /**
     * GitHub's raw CDN (Fastly) caches each path for ~300s and does NOT reliably honour a request
     * {@code Cache-Control: no-cache}. That means for ~5 min after a pack push, some edges hand clients
     * a STALE index.toml/config while others are fresh — the classic "hash mismatch after download".
     * We defeat it by making every raw request a unique URL (unique cache key ⇒ guaranteed origin miss).
     * Only raw.githubusercontent is busted; mod jars live on immutable, content-addressed CDNs
     * (Modrinth) or hash-stamped release URLs, where a query string is pointless and best avoided.
     */
    /** Set to the pack's index hash once pack.toml is read. Every raw request in a run then shares one
     *  STABLE per-release cache key, so Fastly serves index.toml + all ~150 metafiles to every client
     *  from ONE cached copy instead of each client forcing an origin miss — the thundering-herd HTTP 429
     *  that broke the 1.7.3 rollout. A new release (new index hash) still forces a fresh fetch, and the
     *  index-integrity gate still catches a stale/mismatched index. Null before pack.toml is read, so
     *  pack.toml itself uses a unique key (its own freshness is what everything else keys off). */
    private static volatile String bustToken = null;

    private static String bust(String url) {
        if (!url.contains("raw.githubusercontent.com")) return url;
        String token = (bustToken != null && !bustToken.isBlank()) ? bustToken : Long.toString(System.nanoTime());
        return url + (url.indexOf('?') < 0 ? "?" : "&") + "aerocb=" + token;
    }

    /**
     * Bounded retry around a single request.
     *
     * <p>There was none, so ONE transient blip anywhere in ~150 metafile fetches aborted the whole
     * update and showed the player a raw exception. On a release day that is exactly when the CDN is
     * least happy: the 1.7.3 rollout died to a thundering-herd 429, and a 429 was treated as fatal
     * rather than as the "wait and retry" it literally means. 5xx and 429 are retried with backoff
     * and {@code Retry-After} is honoured; 4xx other than 429 is a real error and fails immediately.
     */
    private static <T> HttpResponse<T> sendWithRetry(HttpRequest.Builder builder, String url,
                                                     HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= HTTP_ATTEMPTS; attempt++) {
            try {
                HttpResponse<T> r = HTTP.send(builder.copy().uri(URI.create(bust(url))).build(), handler);
                int sc = r.statusCode();
                if (sc == 200) return r;
                boolean retryable = sc == 429 || sc == 408 || sc >= 500;
                last = new IOException("HTTP " + sc + " for " + url);
                if (!retryable || attempt == HTTP_ATTEMPTS) throw last;
                Thread.sleep(retryAfterMs(r, attempt));
            } catch (IOException e) {
                last = e;
                if (attempt == HTTP_ATTEMPTS) throw e;
                Thread.sleep(backoffMs(attempt));
            }
        }
        throw last != null ? last : new IOException("request failed: " + url);
    }

    /** Server-directed wait when offered, otherwise plain exponential backoff. */
    private static long retryAfterMs(HttpResponse<?> r, int attempt) {
        try {
            var h = r.headers().firstValue("Retry-After");
            if (h.isPresent()) {
                long secs = Long.parseLong(h.get().trim());
                return Math.min(Math.max(secs * 1000L, 1000L), 30_000L);
            }
        } catch (Exception ignored) {}
        return backoffMs(attempt);
    }

    private static long backoffMs(int attempt) {
        // Jittered so a whole player base retrying a release-day 429 doesn't march back in lockstep.
        long baseMs = 1000L << Math.min(attempt - 1, 4);
        return baseMs + (long) (Math.random() * 500);
    }

    private static String get(String url) throws IOException, InterruptedException {
        return sendWithRetry(
            HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                .header("Cache-Control", "no-cache").header("Pragma", "no-cache").GET(),
            url, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    private static void download(String url, Path target) throws IOException, InterruptedException {
        sendWithRetry(
            HttpRequest.newBuilder().timeout(Duration.ofMinutes(5))
                .header("Cache-Control", "no-cache").GET(),
            url, HttpResponse.BodyHandlers.ofFile(target));
    }

    // ── Hashing ─────────────────────────────────────────────────────────────────

    private static boolean matches(Path file, String expected, String fmt) {
        if (expected == null) return true;
        return Files.exists(file) && hashMatches(file, expected, fmt);
    }

    /** Map a packwiz hash-format to a MessageDigest. Handles sha512/sha256/sha1 — CurseForge metadata
     *  metafiles carry sha1, which the old two-way (512-or-256) check hashed as SHA-256 and always
     *  failed (the Corail "hash mismatch after download"). Defaults to SHA-256. */
    private static MessageDigest digestFor(String fmt) throws java.security.NoSuchAlgorithmException {
        String f = fmt == null ? "" : fmt.toLowerCase(Locale.ROOT);
        String algo = f.contains("512") ? "SHA-512" : f.contains("256") ? "SHA-256"
                    : f.contains("1")   ? "SHA-1"   : "SHA-256";
        return MessageDigest.getInstance(algo);
    }

    private static boolean hashStringMatches(String content, String expected, String fmt) {
        try {
            MessageDigest md = digestFor(fmt);
            byte[] d = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString().equalsIgnoreCase(expected.trim());
        } catch (Exception e) { return false; }
    }

    private static boolean hashMatches(Path file, String expected, String fmt) {
        try {
            MessageDigest md = digestFor(fmt);
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

    /**
     * Older copies of jars the pack DOES manage — nothing else.
     *
     * <p>This used to delete every jar under {@code mods/} the index didn't list, on the theory that
     * the pack is a closed set. That silently deleted any mod a player added themselves, with no
     * prompt, and it actively breaks the CurseForge install route: the Core jar is hand-dropped from
     * Discord there, so the moment its filename didn't match the index it deleted the very updater
     * doing the deleting. Now a jar is only removed when a DIFFERENT version of the same mod is
     * managed — which still solves the case this was written for (an old CoffeesAeroCore next to the
     * new one, same mod id, FML silently loading the old file) while leaving personal mods alone.
     */
    /**
     * Mod-id prefixes the pack has retired, matched against the jars actually on disk.
     *
     * <p>Matched on the FILENAME PREFIX, lowercased, not on an exact filename — the same mod reaches
     * players under several names depending on channel and how they downloaded it
     * ({@code waystones-neoforge-1.21.1-21.1.34.jar}, a CurseForge copy with spaces, a browser's
     * {@code " (1)"} duplicate). An exact-name list would miss most of them.
     *
     * <p>Entries must be specific enough not to shadow a mod that is KEPT. Verified against the 1.8.4
     * index at the time of writing: nothing the pack still ships starts with any of these.
     *
     * <p>1.8.4 (Season 2) retirements:
     * <ul>
     *   <li>{@code waystones} + {@code waystonessable} + {@code balm} — Waystones removed; balm was
     *       its only consumer (dependency sweep of all 237 jars found no other required edge).</li>
     *   <li>{@code createdeliveryrequired} — removed.</li>
     *   <li>{@code create aeronautics gyroscope} — removed.</li>
     * </ul>
     */
    private static final List<String> RETIRED_MOD_PREFIXES = List.of(
        "waystones", "waystonessable", "balm-", "balm_",
        "createdeliveryrequired", "create aeronautics gyroscope",
        // dropped in 1.8.5
        "railwaysuntold");

    /**
     * Loose files, outside {@code mods/}, that the pack once installed and no longer wants.
     *
     * <p><b>Why these need their own list.</b> These are not, and never were, in the packwiz index —
     * {@code overrides/kubejs/} does not exist in the repo. They were written into players' instances
     * by an OLDER pack release and then orphaned when the owning mod was dropped. packwiz only
     * manages what it indexes, so nothing in the normal update path can ever reach them: they are
     * not orphans (no manifest entry), not duplicates (no surviving version), and not indexed files.
     * They simply persist forever until something deletes them by name.
     *
     * <p>The KubeJS entries below are the visible case: with {@code createdeliveryrequired} removed in
     * 1.8.4, these ponder scripts reference item IDs that no longer resolve, so KubeJS throws a
     * red "client script errors" screen on every launch. Harmless, but it looks like a broken pack.
     * A CurseForge "repair" also restores them, which is how it surfaced.
     */
    private static final List<String> RETIRED_FILES = List.of(
        "kubejs/client_scripts/cdr_contractor_ponder.js",
        "kubejs/client_scripts/cdr_market_ponder.js",
        "kubejs/client_scripts/cdr_p2p_ponder.js",
        // Dropped in 1.8.4. This one IS a packwiz-indexed file, so orphan tracking would
        // eventually catch it -- but only on a player's SECOND update, because the first run is
        // what writes the manifest in the first place. Listing it here makes it deterministic.
        "resourcepacks/Visual Effects+.zip");

    /** Retired loose files that actually exist on disk. */
    private static List<String> retiredFiles(Path gameDir) {
        List<String> out = new ArrayList<>();
        for (String rel : RETIRED_FILES) {
            if (Files.exists(gameDir.resolve(rel))) {
                out.add(rel);
                LOGGER.info("[Updater] retiring orphaned file: {}", rel);
            }
        }
        return out;
    }

    /** Jars in {@code mods/} whose name matches a retired prefix. Returns index-relative paths. */
    private static List<String> retiredMods(Path gameDir) {
        List<String> out = new ArrayList<>();
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return out;
        try (Stream<Path> s = Files.list(mods)) {
            for (Path jar : (Iterable<Path>) s::iterator) {
                String name = jar.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                String low = name.toLowerCase(Locale.ROOT);
                for (String prefix : RETIRED_MOD_PREFIXES) {
                    if (low.startsWith(prefix)) {
                        out.add("mods/" + name);
                        LOGGER.info("[Updater] retiring dropped mod: {}", name);
                        break;
                    }
                }
            }
        } catch (IOException e) {
            // Non-fatal: a failed prune leaves a stale mod, which the join will surface anyway.
            LOGGER.warn("[Updater] could not scan mods/ for retired mods: {}", e.toString());
        }
        return out;
    }

    private static List<String> duplicateModJars(Path gameDir, Set<String> managed) {
        List<String> out = new ArrayList<>();
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return out;

        Set<String> managedKeys = new HashSet<>();
        Set<String> managedNames = new HashSet<>();
        for (String rel : managed) {
            if (!rel.startsWith("mods/")) continue;
            String name = rel.substring("mods/".length());
            if (name.contains("/")) continue;                 // nested dirs are not the duplicate case
            managedNames.add(name.toLowerCase(Locale.ROOT));
            managedKeys.add(modKey(name));
        }

        String self = runningCoreJarName(gameDir);
        try (Stream<Path> s = Files.list(mods)) {
            for (Path jar : (Iterable<Path>) s::iterator) {
                String name = jar.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".jar")) continue;
                if (managedNames.contains(lower)) continue;    // it IS the managed copy
                if (name.equals(self)) continue;               // never delete the jar we are running from
                if (managedKeys.contains(modKey(name))) out.add("mods/" + name);
            }
        } catch (IOException ignored) {}
        return out;
    }

    /**
     * Filename reduced to a mod identity: everything before the first version-looking segment.
     * {@code CoffeesAeroCore-1.3.3-2fa5e52e.jar -> coffeesaerocore},
     * {@code sodium-neoforge-0.6.13+mc1.21.1.jar -> sodium-neoforge}.
     */
    private static String modKey(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jar")) n = n.substring(0, n.length() - 4);
        String[] parts = n.split("-");
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty() && Character.isDigit(part.charAt(0))) break;   // version segment
            if (key.length() > 0) key.append('-');
            key.append(part);
        }
        return key.length() == 0 ? n : key.toString();
    }

    /** The Core jar this process is running from, so the sweep can never delete it. */
    private static String runningCoreJarName(Path gameDir) {
        try {
            return resolveSelfJar(gameDir).getFileName().toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Staging must start empty — see the call site for why a leftover file is dangerous. */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
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
