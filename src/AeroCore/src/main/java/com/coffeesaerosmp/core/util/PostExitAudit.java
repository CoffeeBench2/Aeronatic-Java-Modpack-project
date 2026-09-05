package com.coffeesaerosmp.core.util;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports, at startup, whether the previous session's post-exit helper actually did its job.
 *
 * <p>🔴 <b>Why this exists.</b> Four features finish their work in a second JVM after the game
 * closes: the stale-mod cleaner, Potato mode, the pack updater's applier and the NeoForge loader
 * applier. Each writes a request file, launches a helper, and the helper deletes that request file
 * when it succeeds. Each also redirects its output to a private log — {@code cleanup.log},
 * {@code mode.log}, {@code apply.log}, {@code loader.log} — inside a dot-directory.
 *
 * <p>Nothing ever read any of that back. So when the cleaner died on 2026-09-05 with
 * {@code ClassNotFoundException} (it copied a stale duplicate Core jar and ran a class that did not
 * exist in it), the evidence sat in {@code .aero-cleanup/cleanup.log} for days while the player
 * watched removed mods refuse to disappear and could not join the server. The bug was cheap; the
 * silence was expensive.
 *
 * <p>The signal was always there and always free: <b>a surviving request file means the helper did
 * not finish.</b> This class checks all four on every launch and puts the result in the ordinary
 * game log, where players paste from and where we actually look. It fixes nothing by itself — that
 * is deliberate, since a retry from inside the running game would hit the same file locks — but it
 * converts a silent failure into a visible one.
 */
public final class PostExitAudit {

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroCore-PostExit");

    /** requestFile, helperLog, human name — relative to the game directory. */
    private static final String[][] HELPERS = {
        // StaleModsCleaner deletes delete.txt when it finishes.
        {".aero-cleanup/delete.txt",   ".aero-cleanup/cleanup.log", "stale-mod cleanup"},
        // ModeApplier deletes pending.txt on success; ClientMode.pending() only reads it, so
        // nothing else clears it at startup and the signal survives to be audited here.
        {".aero-mode/pending.txt",     ".aero-mode/mode.log",       "Potato/Normal mode switch"},
        // LoaderApplier deletes its request once the launcher metadata is patched.
        {".aero-update/loader.txt",    ".aero-update/loader.log",   "NeoForge loader update"},
        // Applier wipes the whole .aero-update work dir, removals.txt included, on success.
        {".aero-update/removals.txt",  ".aero-update/apply.log",    "pack update apply"},
    };

    private static boolean done;

    private PostExitAudit() {}

    /** Safe to call repeatedly; only the first call reports. Never throws. */
    public static void run() {
        if (done) return;
        done = true;
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            for (String[] h : HELPERS) {
                Path request = gameDir.resolve(h[0]);
                if (!Files.isRegularFile(request)) continue;   // absent = it completed, or never ran

                List<String> pending = read(request);
                LOGGER.error("[PostExit] The {} from the previous session DID NOT COMPLETE. "
                        + "{} still lists {} item(s): {}",
                    h[2], h[0], pending.size(), pending.isEmpty() ? "(empty)" : pending);

                for (String line : tail(gameDir.resolve(h[1]), 6)) {
                    LOGGER.error("[PostExit]   {}: {}", h[1], line);
                }
                LOGGER.error("[PostExit] Nothing was changed on disk. If this repeats, the helper "
                    + "process cannot start — check for a leftover CoffeesAeroCore jar in mods/.");
            }
        } catch (Throwable t) {
            // An audit that crashes the client would be worse than the silence it replaces.
            LOGGER.warn("[PostExit] audit skipped: {}", t.toString());
        }
    }

    private static List<String> read(Path p) {
        try {
            List<String> out = new ArrayList<>();
            for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (!l.isBlank()) out.add(l.trim());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Last {@code n} non-blank lines of a helper log — the failure is always at the end. */
    private static List<String> tail(Path p, int n) {
        try {
            if (!Files.isRegularFile(p)) return List.of();
            List<String> all = new ArrayList<>();
            for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (!l.isBlank()) all.add(l.trim());
            }
            return all.subList(Math.max(0, all.size() - n), all.size());
        } catch (Exception e) {
            return List.of();
        }
    }
}
