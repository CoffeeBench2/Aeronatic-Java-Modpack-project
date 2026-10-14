package com.coffeesaerosmp.core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Windowless post-exit applier. Launched by {@link InClientUpdater} via {@code javaw} from a COPY of
 * the core jar (so it can replace the real jar in mods/). It waits for the game process to exit — which
 * releases the locked mod jars — then moves the staged files into place and removes orphaned files.
 *
 * <p>Standalone main (runs in its own JVM): args = pid, gameDir, stagingDir, removalsFile.
 * No Minecraft classes referenced, so it loads fine from the bare jar on the classpath.</p>
 */
public final class Applier {

    public static void main(String[] args) {
        if (args.length < 4) return;
        long pid;
        try { pid = Long.parseLong(args[0]); } catch (NumberFormatException e) { return; }
        Path gameDir = Paths.get(args[1]);
        Path staging = Paths.get(args[2]);
        Path removalsFile = Paths.get(args[3]);

        waitForExit(pid);
        // tiny extra grace so the OS fully releases file handles
        sleep(800);

        List<String> failed;
        try {
            failed = applyStaging(gameDir, staging);
            applyRemovals(gameDir, removalsFile, failed);
        } catch (IOException e) {
            System.err.println("[Applier] failed: " + e);
            return;
        }
        // 🔴 Delete ONLY this run's throwaway files. This used to wipe the whole .aero-update/
        // directory, which destroyed the updater manifest (orphan removal never worked), the
        // NeoForge loader request (racing LoaderApplier, which starts at the same exit) and every
        // marker other features kept there. Persistent state now lives in .aero-state/.
        deleteTree(staging);
        try { Files.deleteIfExists(removalsFile); } catch (IOException ignored) {}
        System.out.println("[Applier] update applied. Relaunch Coffees Aero SMP.");
    }

    private static void waitForExit(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isEmpty()) return;
        try { h.get().onExit().get(); } catch (Exception e) {
            // fallback: poll
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) sleep(500);
        }
    }

    /** @return staged paths (relative, '/'-separated) that could NOT be applied */
    private static List<String> applyStaging(Path gameDir, Path staging) throws IOException {
        List<String> failedRels = new java.util.ArrayList<>();
        if (!Files.isDirectory(staging)) return failedRels;
        // Per-file fault isolation: one stubborn file (AV scan / launcher briefly holding a jar) must
        // not abort the rest of the apply. A failed file keeps its PREVIOUS copy — an old jar still
        // loads; a missing jar cascades into "balm is not installed" dependency errors for players.
        List<String> failed = new java.util.ArrayList<>();
        try (Stream<Path> walk = Files.walk(staging)) {
            for (Path src : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                Path rel = staging.relativize(src);
                Path dst = gameDir.resolve(rel);
                try {
                    Files.createDirectories(dst.getParent());
                    moveReplacing(src, dst);
                } catch (IOException e) {
                    failed.add(rel + " -> " + e);
                    failedRels.add(rel.toString().replace(java.io.File.separatorChar, '/'));
                }
            }
        }
        if (!failed.isEmpty()) {
            System.err.println("[Applier] " + failed.size() + " file(s) kept their previous copy (apply failed):");
            for (String f : failed) System.err.println("  " + f);
        }
        return failedRels;
    }

    private static void moveReplacing(Path src, Path dst) throws IOException {
        // NEVER remove the existing file before its replacement is safely next to it. The old
        // move(REPLACE_EXISTING) could delete the target and then fail the rename (transient lock),
        // leaving a HOLE in mods/ — the "balm/kotlinforforge is not installed" incident. Now: copy the
        // staged file to a sibling temp, then atomically swap; any failure leaves the old file intact.
        Path tmp = dst.resolveSibling(dst.getFileName() + ".aero-new");
        IOException last = null;
        for (int i = 0; i < 10; i++) {
            try {
                Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.deleteIfExists(src);
                return;
            } catch (IOException e) {
                last = e;
                sleep(700);
            }
        }
        try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        throw last;
    }

    private static void applyRemovals(Path gameDir, Path removalsFile, List<String> failedStaged) throws IOException {
        if (!Files.exists(removalsFile)) return;
        // A jar whose replacement failed to apply must survive: removing the old Core (or any old
        // mod) after its new copy failed would leave the instance with NEITHER.
        java.util.Set<String> failedKeys = new java.util.HashSet<>();
        for (String f : failedStaged) if (f.startsWith("mods/")) failedKeys.add(modKey(f.substring(5)));
        Path root = gameDir.toAbsolutePath().normalize();
        List<String> lines = Files.readAllLines(removalsFile, StandardCharsets.UTF_8);
        int removed = 0, kept = 0;
        for (String rel : lines) {
            rel = rel.trim().replace(java.io.File.separatorChar, '/');
            if (rel.isEmpty()) continue;
            Path target = root.resolve(rel).normalize();
            if (!target.startsWith(root)) continue;              // never outside the instance
            if (rel.startsWith("mods/") && failedKeys.contains(modKey(rel.substring(5)))) {
                System.err.println("[Applier] kept " + rel + " — its replacement failed to apply");
                kept++;
                continue;
            }
            try {
                if (Files.deleteIfExists(target)) { removed++; System.out.println("[Applier] removed " + rel); }
            } catch (IOException e) {
                System.err.println("[Applier] could not remove " + rel + ": " + e + " (retried next update)");
            }
        }
        System.out.println("[Applier] removals: " + removed + " removed, " + kept + " kept");
    }

    /** Same reduction as InClientUpdater.modKey: the name up to the first version-looking segment. */
    static String modKey(String fileName) {
        String n = fileName.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".jar")) n = n.substring(0, n.length() - 4);
        StringBuilder key = new StringBuilder();
        for (String part : n.split("-")) {
            if (!part.isEmpty() && Character.isDigit(part.charAt(0))) break;
            if (key.length() > 0) key.append('-');
            key.append(part);
        }
        return key.length() == 0 ? n : key.toString();
    }

    private static void deleteTree(Path workDir) {
        if (workDir == null || !Files.exists(workDir)) return;
        try (Stream<Path> walk = Files.walk(workDir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}   // applier.jar stays locked: best-effort
            });
        } catch (IOException ignored) {}
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private Applier() {}
}
