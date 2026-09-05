package com.coffeesaerosmp.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Finds the Core jar on disk, for the four features that must copy themselves aside and re-run
 * after the game exits: the stale-mod cleaner, Potato mode, the pack updater's applier and the
 * NeoForge loader applier. FML holds every mod jar open on Windows, so each of them launches a
 * second JVM with {@code -cp <copy of our jar>} and a main class inside it.
 *
 * <p>🔴 <b>Why this class exists.</b> All four used to resolve the jar themselves with the same
 * snippet: list {@code mods/}, take the first file whose name starts with {@code coffeesaerocore},
 * {@code findFirst()}. When a player's {@code mods/} contains TWO Core jars — which happens
 * routinely, because an interrupted update leaves the old one behind — directory order returns
 * {@code CoffeesAeroCore-1.3.19.jar} before {@code CoffeesAeroCore-1.3.44.jar}. The helper process
 * then starts against a jar that predates the feature it was asked to run and dies with
 * {@code ClassNotFoundException}, into a log file nobody reads.
 *
 * <p>Observed on a real instance 2026-09-05: {@code .aero-cleanup/cleanup.log} contained
 * {@code Could not find or load main class …StaleModsCleaner} and {@code cleaner.jar} was
 * byte-for-byte the 1.3.19 jar. <b>The failure is self-perpetuating</b> — the sweep that would have
 * deleted the duplicate Core is the very thing the duplicate Core prevents from running — so the
 * player stays stuck until someone deletes the old jar by hand.
 *
 * <p>The fix is to stop guessing. A candidate is only acceptable if it actually CONTAINS the class
 * we are about to invoke; among those, the newest version wins. Verifying by content also means a
 * future rename of the jar cannot silently reintroduce this.
 */
public final class SelfJar {

    private SelfJar() {}

    /**
     * @param gameDir       the instance directory
     * @param requiredClass the class the helper JVM will run, e.g.
     *                      {@code com.coffeesaerosmp.core.cleanup.StaleModsCleaner}
     * @return a jar that definitely contains {@code requiredClass}
     * @throws IOException if no such jar exists, rather than returning one that cannot work
     */
    public static Path locate(Path gameDir, String requiredClass) throws IOException {
        String entry = requiredClass.replace('.', '/') + ".class";

        // Preferred: ask the classloader where we came from. Correct by construction when it works,
        // but NeoForge's union filesystem often hands back a URI that is not a plain file, hence the
        // scan below.
        try {
            var loc = SelfJar.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc != null) {
                Path p = Paths.get(loc.toURI());
                if (isUsable(p, entry)) return p;
            }
        } catch (Exception ignored) {
            // fall through to the scan
        }

        List<Path> candidates = new ArrayList<>();
        Path mods = gameDir.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (var s = Files.list(mods)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.startsWith("coffeesaerocore") && n.endsWith(".jar") && isUsable(p, entry)) {
                        candidates.add(p);
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            throw new IOException("no CoffeesAeroCore jar in mods/ contains " + requiredClass);
        }
        // More than one can legitimately qualify while an update is half-applied. Newest wins:
        // that is the jar whose behaviour the player is actually running.
        candidates.sort((a, b) -> compareVersions(version(b), version(a)));
        return candidates.get(0);
    }

    /** True when {@code p} is a readable jar containing {@code entry}. */
    private static boolean isUsable(Path p, String entry) {
        if (!Files.isRegularFile(p)) return false;
        if (!p.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) return false;
        try (ZipFile zf = new ZipFile(p.toFile())) {
            return zf.getEntry(entry) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Digits out of {@code CoffeesAeroCore-1.3.44-cf.jar} → {@code [1, 3, 44]}. */
    static int[] version(Path p) {
        String n = p.getFileName().toString();
        List<Integer> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        // Stop at the first non-version token so a build suffix cannot inflate the comparison.
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isDigit(c)) {
                cur.append(c);
            } else {
                if (cur.length() > 0) { parts.add(Integer.parseInt(cur.toString())); cur.setLength(0); }
                if (c != '.' && c != '-' && !parts.isEmpty()) break;
            }
        }
        if (cur.length() > 0) parts.add(Integer.parseInt(cur.toString()));
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = parts.get(i);
        return out;
    }

    static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
