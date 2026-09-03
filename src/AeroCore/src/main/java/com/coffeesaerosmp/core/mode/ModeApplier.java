package com.coffeesaerosmp.core.mode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Windowless post-exit mode switcher. Launched by {@link ClientMode} from a COPY of the core jar,
 * it waits for the game process to exit — which releases the locked mod jars — then renames the
 * potato-excluded mods and swaps options.txt.
 *
 * <p>Standalone main (runs in its own JVM): args = pid, gameDir, targetMode.
 * References no Minecraft classes, so it loads from the bare jar on the classpath.
 *
 * <p><b>All-or-nothing.</b> Every rename is recorded as it succeeds, and any failure rolls the whole
 * batch back before exiting. A half-applied mode is the one outcome worth real effort to avoid: it
 * would leave a client with, say, Distant Horizons off but its config and options still expecting it,
 * and no state file saying which mode it is actually in. Same reasoning as the partial-wipe guard in
 * DhLodReset — record success only when the whole operation succeeded.
 */
public final class ModeApplier {

    public static void main(String[] args) {
        if (args.length < 3) return;
        long pid;
        try { pid = Long.parseLong(args[0]); } catch (NumberFormatException e) { return; }
        Path gameDir = Paths.get(args[1]);
        ClientMode.Mode target = "potato".equalsIgnoreCase(args[2])
            ? ClientMode.Mode.POTATO : ClientMode.Mode.NORMAL;

        waitForExit(pid);
        sleep(800);   // extra grace so the OS fully releases file handles

        List<Runnable> undo = new ArrayList<>();
        try {
            if (target == ClientMode.Mode.POTATO) {
                applyPotato(gameDir, undo);
            } else {
                applyNormal(gameDir, undo);
            }
            ClientMode.write(ClientMode.statePath(gameDir), target);
            Files.deleteIfExists(ClientMode.pendingPath(gameDir));
            System.out.println("[ModeApplier] switched to " + target.label() + ". Relaunch Coffees Aero SMP.");
        } catch (Exception e) {
            System.err.println("[ModeApplier] FAILED: " + e + " — rolling back " + undo.size() + " change(s).");
            for (int i = undo.size() - 1; i >= 0; i--) {
                try { undo.get(i).run(); } catch (Exception ignored) {}
            }
            // pending.txt is deliberately left in place: the screen shows the switch as still
            // outstanding rather than silently pretending nothing was asked for.
        }
    }

    // ── Potato ──────────────────────────────────────────────────────────────────

    private static void applyPotato(Path gameDir, List<Runnable> undo) throws IOException {
        Path mods = gameDir.resolve("mods");
        if (Files.isDirectory(mods)) {
            List<Path> hits = new ArrayList<>();
            try (var s = Files.list(mods)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString();
                    if (!n.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                    for (String prefix : ClientMode.POTATO_EXCLUDE) {
                        if (n.regionMatches(true, 0, prefix, 0, prefix.length())) { hits.add(p); break; }
                    }
                }
            }
            for (Path jar : hits) {
                Path off = jar.resolveSibling(jar.getFileName() + ClientMode.DISABLED_SUFFIX);
                Files.move(jar, off, StandardCopyOption.REPLACE_EXISTING);
                undo.add(() -> { try { Files.move(off, jar, StandardCopyOption.REPLACE_EXISTING); }
                                 catch (IOException ignored) {} });
            }
            System.out.println("[ModeApplier] disabled " + hits.size() + " heavy client mod(s).");
        }
        applyPotatoOptions(gameDir, undo);
    }

    /**
     * Backs up options.txt once, then rewrites only the potato keys in place.
     *
     * <p>Rewriting keys rather than shipping a whole potato options.txt matters: the file also holds
     * keybinds, sound volumes, language and every mod's own entries. Replacing it wholesale would
     * reset all of that, which is exactly the behaviour 1.10.0 promises to have stopped.
     *
     * <p>The backup is written ONLY if one does not already exist, so a second switch to Potato
     * cannot overwrite the player's real settings with an already-potato'd copy.
     */
    private static void applyPotatoOptions(Path gameDir, List<Runnable> undo) throws IOException {
        Path opts = gameDir.resolve("options.txt");
        if (!Files.exists(opts)) return;
        Path backup = gameDir.resolve(ClientMode.OPTIONS_BACKUP);
        if (!Files.exists(backup)) {
            Files.copy(opts, backup);
            undo.add(() -> { try { Files.deleteIfExists(backup); } catch (IOException ignored) {} });
        }
        byte[] before = Files.readAllBytes(opts);
        undo.add(() -> { try { Files.write(opts, before); } catch (IOException ignored) {} });

        List<String> lines = Files.readAllLines(opts, StandardCharsets.UTF_8);
        Map<String, String> want = new LinkedHashMap<>(ClientMode.POTATO_OPTIONS);
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            int eq = line.indexOf(':');
            if (eq > 0) {
                String key = line.substring(0, eq);
                String val = want.remove(key);
                if (val != null) { out.add(key + ":" + val); continue; }
            }
            out.add(line);
        }
        want.forEach((k, v) -> out.add(k + ":" + v));   // keys the file did not already have
        Files.write(opts, out, StandardCharsets.UTF_8);
        System.out.println("[ModeApplier] applied potato video settings (original kept as "
            + ClientMode.OPTIONS_BACKUP + ").");
    }

    // ── Normal ──────────────────────────────────────────────────────────────────

    private static void applyNormal(Path gameDir, List<Runnable> undo) throws IOException {
        Path mods = gameDir.resolve("mods");
        if (Files.isDirectory(mods)) {
            List<Path> hits = new ArrayList<>();
            try (var s = Files.list(mods)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (p.getFileName().toString().endsWith(ClientMode.DISABLED_SUFFIX)) hits.add(p);
                }
            }
            for (Path off : hits) {
                String n = off.getFileName().toString();
                Path jar = off.resolveSibling(n.substring(0, n.length() - ClientMode.DISABLED_SUFFIX.length()));
                Files.move(off, jar, StandardCopyOption.REPLACE_EXISTING);
                undo.add(() -> { try { Files.move(jar, off, StandardCopyOption.REPLACE_EXISTING); }
                                 catch (IOException ignored) {} });
            }
            System.out.println("[ModeApplier] re-enabled " + hits.size() + " mod(s).");
        }
        Path backup = gameDir.resolve(ClientMode.OPTIONS_BACKUP);
        Path opts = gameDir.resolve("options.txt");
        if (Files.exists(backup)) {
            byte[] before = Files.exists(opts) ? Files.readAllBytes(opts) : null;
            Files.copy(backup, opts, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(backup);
            undo.add(() -> {
                try {
                    if (before != null) Files.write(opts, before);
                    Files.copy(opts, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {}
            });
            System.out.println("[ModeApplier] restored the original options.txt.");
        }
    }

    // ── plumbing ────────────────────────────────────────────────────────────────

    private static void waitForExit(long pid) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isEmpty()) return;
        try { h.get().onExit().get(); } catch (Exception e) {
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) sleep(500);
        }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    private ModeApplier() {}
}
