package com.coffeesaerosmp.core.cleanup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Windowless post-exit deleter for {@link StaleMods}. Waits for the game process to exit, which
 * releases the locked mod jars, then removes each queued file.
 *
 * <p>Standalone main (own JVM): args = pid, gameDir, queueFile. References no Minecraft classes.
 *
 * <p>Deletion is per-file best effort. One stubborn file (an antivirus scan holding a handle) must
 * not stop the rest — a leftover jar is retried on the next launch, because the sweep re-runs and
 * re-queues anything still present.
 */
public final class StaleModsCleaner {

    public static void main(String[] args) {
        if (args.length < 3) return;
        long pid;
        try { pid = Long.parseLong(args[0]); } catch (NumberFormatException e) { return; }
        Path gameDir = Paths.get(args[1]);
        Path queue = Paths.get(args[2]);

        waitForExit(pid);
        sleep(800);   // grace so the OS fully releases handles

        int gone = 0, failed = 0;
        try {
            List<String> names = Files.readAllLines(queue, StandardCharsets.UTF_8);
            for (String n : names) {
                n = n.trim();
                if (n.isEmpty()) continue;
                // Resolve by NAME ONLY against mods/ — never trust a path from the queue file.
                Path p = gameDir.resolve("mods").resolve(Paths.get(n).getFileName().toString());
                try {
                    if (Files.deleteIfExists(p)) { gone++; System.out.println("[Cleanup] removed " + n); }
                } catch (Exception e) {
                    failed++;
                    System.err.println("[Cleanup] could not remove " + n + ": " + e);
                }
            }
            Files.deleteIfExists(queue);
        } catch (Exception e) {
            System.err.println("[Cleanup] failed: " + e);
        }
        System.out.println("[Cleanup] done — removed " + gone + ", failed " + failed
            + (failed > 0 ? " (retried next launch)" : ""));
    }

    private static void waitForExit(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isEmpty()) return;
        try { h.get().onExit().get(); } catch (Exception e) {
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) sleep(500);
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private StaleModsCleaner() {}
}
