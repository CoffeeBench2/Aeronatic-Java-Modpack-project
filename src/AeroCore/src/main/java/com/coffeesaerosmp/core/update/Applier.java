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

        try {
            applyStaging(gameDir, staging);
            applyRemovals(gameDir, removalsFile);
        } catch (IOException e) {
            System.err.println("[Applier] failed: " + e);
            return;
        }
        cleanup(staging.getParent());   // best-effort: wipe the .aero-update work dir
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

    private static void applyStaging(Path gameDir, Path staging) throws IOException {
        if (!Files.isDirectory(staging)) return;
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
                }
            }
        }
        if (!failed.isEmpty()) {
            System.err.println("[Applier] " + failed.size() + " file(s) kept their previous copy (apply failed):");
            for (String f : failed) System.err.println("  " + f);
        }
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

    private static void applyRemovals(Path gameDir, Path removalsFile) throws IOException {
        if (!Files.exists(removalsFile)) return;
        List<String> lines = Files.readAllLines(removalsFile, StandardCharsets.UTF_8);
        for (String rel : lines) {
            rel = rel.trim();
            if (rel.isEmpty()) continue;
            try { Files.deleteIfExists(gameDir.resolve(rel)); } catch (IOException ignored) {}
        }
    }

    private static void cleanup(Path workDir) {
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
