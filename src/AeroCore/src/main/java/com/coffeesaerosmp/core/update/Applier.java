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
        try (Stream<Path> walk = Files.walk(staging)) {
            for (Path src : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                Path rel = staging.relativize(src);
                Path dst = gameDir.resolve(rel);
                Files.createDirectories(dst.getParent());
                moveReplacing(src, dst);
            }
        }
    }

    private static void moveReplacing(Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFail) {
            // cross-device or locked: copy then delete, with a couple of retries for stubborn locks
            for (int i = 0; i < 5; i++) {
                try { Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING); Files.deleteIfExists(src); return; }
                catch (IOException e) { sleep(500); }
            }
            throw atomicFail;
        }
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
