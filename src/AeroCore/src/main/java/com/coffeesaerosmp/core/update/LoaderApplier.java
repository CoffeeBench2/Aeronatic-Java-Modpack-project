package com.coffeesaerosmp.core.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-exit half of {@link LoaderUpdater}: rewrites the launcher's loader version, and where the
 * launcher will not fetch NeoForge itself, installs it first.
 *
 * <p>Standalone main (own JVM): args = pid, gameDir, requestFile. Waits for the game to exit before
 * touching anything — a launcher may hold its metadata open while a child game process runs.
 *
 * <p><b>Every file is backed up before it is edited</b> ({@code <name>.aero-bak}). This code writes
 * OUTSIDE the game directory, into the launcher's own configuration, which is as invasive as this
 * mod gets. A corrupted instance file means a player cannot start the game at all, so the edit is
 * deliberately the narrowest possible: one version string, matched in place, with the rest of the
 * document untouched. No JSON library is used precisely so the document cannot be reformatted or
 * have unknown fields dropped on re-serialisation.
 */
public final class LoaderApplier {

    public static void main(String[] args) {
        if (args.length < 3) return;
        long pid;
        try { pid = Long.parseLong(args[0]); } catch (NumberFormatException e) { return; }
        Path gameDir = Paths.get(args[1]);
        Path req = Paths.get(args[2]);

        waitForExit(pid);
        sleep(1200);   // launchers often rewrite their own metadata as the game closes

        try {
            List<String> l = Files.readAllLines(req, StandardCharsets.UTF_8);
            if (l.size() < 5) { System.err.println("[Loader] malformed request"); return; }
            String launcher = l.get(0);
            Path meta = Paths.get(l.get(1));
            String rootStr = l.get(2);
            String from = l.get(3), to = l.get(4);

            System.out.println("[Loader] " + launcher + ": NeoForge " + from + " -> " + to);

            // Install FIRST. Pointing an instance at a loader that is not on disk leaves it unable
            // to start, so if the install fails we must not touch the metadata at all.
            if (!rootStr.isBlank()) {
                Path root = Paths.get(rootStr);
                if (alreadyInstalled(root, to)) {
                    System.out.println("[Loader] NeoForge " + to + " already present in " + root);
                } else if (!runInstaller(gameDir, root, to)) {
                    System.err.println("[Loader] installer failed — metadata left UNCHANGED so the "
                        + "instance still starts on " + from);
                    return;
                }
            }

            boolean ok = switch (launcher) {
                case "PRISM"      -> patchPrism(meta, to);
                case "VANILLA"    -> patchVanilla(meta, to);
                default           -> false;
            };
            System.out.println(ok
                ? "[Loader] done. Restart the launcher and NeoForge " + to + " will be used."
                : "[Loader] could not patch " + meta);
            Files.deleteIfExists(req);
        } catch (Exception e) {
            System.err.println("[Loader] failed: " + e);
        }
    }

    // ── launcher metadata ───────────────────────────────────────────────────────

    /** Prism: the component with {@code uid: net.neoforged} carries version + cachedVersion. */
    private static boolean patchPrism(Path meta, String to) throws IOException {
        String s = read(meta);
        int uid = s.indexOf("\"net.neoforged\"");
        if (uid < 0) return false;
        // The component object containing that uid — bound the edit to it so a version string
        // belonging to Minecraft or LWJGL can never be hit by accident.
        int start = s.lastIndexOf('{', uid);
        int end = s.indexOf('}', uid);
        if (start < 0 || end < 0) return false;
        String block = s.substring(start, end);
        String patched = block
            .replaceAll("(\"version\"\\s*:\\s*\")[^\"]*(\")", "$1" + Matcher.quoteReplacement(to) + "$2")
            .replaceAll("(\"cachedVersion\"\\s*:\\s*\")[^\"]*(\")", "$1" + Matcher.quoteReplacement(to) + "$2");
        backup(meta);
        write(meta, s.substring(0, start) + patched + s.substring(end));
        return true;
    }

    /**
     * Vanilla launcher: profiles reference a version id like {@code neoforge-21.1.244}. Only ids
     * that already look like NeoForge are rewritten, so a player's other profiles are left alone.
     */
    private static boolean patchVanilla(Path meta, String to) throws IOException {
        String s = read(meta);
        Matcher m = Pattern.compile("(\"lastVersionId\"\\s*:\\s*\")(neoforge-[^\"]*)(\")").matcher(s);
        if (!m.find()) return false;
        backup(meta);
        write(meta, m.replaceAll("$1neoforge-" + Matcher.quoteReplacement(to) + "$3"));
        return true;
    }

    // ── NeoForge installer ──────────────────────────────────────────────────────

    private static boolean alreadyInstalled(Path root, String ver) {
        return Files.isDirectory(root.resolve("libraries/net/neoforged/neoforge").resolve(ver))
            || Files.isDirectory(root.resolve("versions").resolve("neoforge-" + ver));
    }

    /**
     * Downloads NeoForge's official installer and runs it headlessly against the launcher's shared
     * install root. {@code --installClient} is the documented non-interactive mode; it writes
     * {@code versions/} and {@code libraries/} exactly as the launcher expects to find them.
     */
    private static boolean runInstaller(Path gameDir, Path root, String ver) {
        try {
            Path work = gameDir.resolve(".aero-update");
            Files.createDirectories(work);
            Path jar = work.resolve("neoforge-installer-" + ver + ".jar");
            String url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/"
                + ver + "/neoforge-" + ver + "-installer.jar";
            System.out.println("[Loader] downloading " + url);
            var c = (java.net.HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "CoffeesAeroCore");
            c.setConnectTimeout(30000);
            c.setReadTimeout(300000);
            if (c.getResponseCode() != 200) {
                System.err.println("[Loader] installer HTTP " + c.getResponseCode());
                return false;
            }
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(jar) < 100_000) {
                System.err.println("[Loader] installer download looks truncated (" + Files.size(jar) + " bytes)");
                return false;
            }
            System.out.println("[Loader] running installer against " + root);
            ProcessBuilder pb = new ProcessBuilder(java(), "-jar", jar.toString(),
                "--installClient", root.toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(work.resolve("neoforge-install.log").toFile());
            Process p = pb.start();
            if (!p.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly();
                System.err.println("[Loader] installer timed out");
                return false;
            }
            if (p.exitValue() != 0) {
                System.err.println("[Loader] installer exit " + p.exitValue()
                    + " — see .aero-update/neoforge-install.log");
                return false;
            }
            Files.deleteIfExists(jar);
            return alreadyInstalled(root, ver);
        } catch (Exception e) {
            System.err.println("[Loader] installer error: " + e);
            return false;
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────────

    private static String read(Path p) throws IOException { return Files.readString(p, StandardCharsets.UTF_8); }

    private static void write(Path p, String s) throws IOException {
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }

    /** Never overwrite an existing backup: the first one is the last known-good state. */
    private static void backup(Path p) throws IOException {
        Path b = p.resolveSibling(p.getFileName() + ".aero-bak");
        if (!Files.exists(b)) Files.copy(p, b);
    }

    private static String java() {
        String home = System.getProperty("java.home", "");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path p = Paths.get(home, "bin", win ? "java.exe" : "java");
        return Files.exists(p) ? p.toString() : "java";
    }

    private static void waitForExit(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isEmpty()) return;
        try { h.get().onExit().get(); } catch (Exception e) {
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) sleep(500);
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private LoaderApplier() {}
}
