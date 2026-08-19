package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Append-only audit log with SHA-256 tamper detection.
 * Name parameter drives filenames: "audit" → audit.log, audit.log.bak, audit.checksum
 *
 * <p>1.6.12: appends run on the {@link AsyncIo} writer thread, and the checksum is maintained
 * INCREMENTALLY (a running digest updated per appended line, snapshotted via clone()). The old
 * implementation re-read and re-hashed the ENTIRE file on every line, on the caller's thread —
 * a per-login server-thread stall that grew with the log. The produced checksum is identical
 * (SHA-256 of the whole file), so existing checksum files and the verify cycle stay valid.</p>
 *
 * <h2>1.7.35 — the alert loop, and why this class no longer restores anything</h2>
 *
 * On 2026-08-19 this fired {@code CRITICAL — Audit Log Tampered} every 60 seconds indefinitely.
 * Nothing had been tampered with. Three separate defects combined:
 *
 * <ol>
 *   <li><b>Startup desync read as tampering.</b> {@code initialize()} took {@code lastChecksum} from
 *       the {@code .checksum} file but primed the running digest from the {@code .log} file, and
 *       never checked that the two agreed. A write is two steps — append to {@code .log}, then write
 *       {@code .checksum} — so <b>any death between them leaves the checksum one line behind</b>.
 *       This server is killed by non-unwinding Rapier {@code SIGABRT} aborts with no shutdown hook,
 *       which is exactly that. Every check from the next boot onward then compared against a stale
 *       value.</li>
 *   <li><b>The mismatch was never cleared, so it looped forever.</b> The old recovery was
 *       {@code restoreFromBackup()}, which <i>silently returned</i> when no {@code .bak} existed —
 *       and a {@code .bak} is only produced by a CLEAN pass, so a boot that starts dirty never has
 *       one. {@code lastChecksum} stayed stale and every 60s tick re-alerted.</li>
 *   <li><b>The recovery destroyed data when it did fire.</b> Restoring copied an OLDER backup over
 *       the live log, deleting real audit entries to "fix" a checksum mismatch.</li>
 * </ol>
 *
 * <p><b>The invariant that makes a loop structurally impossible:</b> every path that reports a
 * mismatch also RE-SEEDS {@code lastChecksum} from what is actually on disk right now. The next
 * check therefore compares against reality, so the same mismatch can never be reported twice.
 * Nothing is ever overwritten — a suspect file is copied ASIDE for forensics, never restored over.
 */
public class AuditLogger {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter FILE_STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("UTC"));

    /** How many {@code *.log.suspect-*} copies to keep before the oldest are pruned. */
    private static final int MAX_SUSPECT_COPIES = 10;

    /** Outcome of an integrity check. */
    public enum IntegrityResult {
        /** File matches what we last wrote. */
        CLEAN,
        /** The .checksum on disk disagreed with the .log at STARTUP — an unclean shutdown, or an
         *  edit made while the server was down. Reported once, at WARNING, never repeated. */
        STARTUP_DESYNC,
        /** The file changed while this process was running and tracking it. The real signal. */
        MODIFIED,
        /** The check itself failed (unreadable file, etc). Not evidence of tampering. */
        IO_ERROR
    }

    private final String name;
    private final Path logFile;
    private final Path backupFile;
    private final Path checksumFile;
    private volatile String lastChecksum;
    private final Object lock = new Object();
    private MessageDigest runningDigest;   // guarded by lock; SHA-256 of the file's full content

    /** Set by {@link #initialize()} when the stored checksum disagreed with the log; read once. */
    private volatile boolean startupDesync;
    /** Path of the copy taken aside at startup, for the one-time alert. */
    private volatile String startupSuspectCopy;

    public AuditLogger(String name, Path dataDir) {
        this.name    = name;
        logFile      = dataDir.resolve(name + ".log");
        backupFile   = dataDir.resolve(name + ".log.bak");
        checksumFile = dataDir.resolve(name + ".checksum");
    }

    /**
     * Loads state and RECONCILES the stored checksum against the real file.
     *
     * <p>The stored checksum is treated as a hint, never as truth: whatever is on disk right now
     * becomes the baseline. If the two disagreed we keep a copy for forensics and raise the
     * {@link #consumeStartupDesync()} flag exactly once — because a two-step write means a stale
     * checksum is the EXPECTED result of an unclean shutdown, not evidence of an intruder.
     */
    public void initialize() {
        synchronized (lock) {
            String stored = null;
            if (Files.exists(checksumFile)) {
                try { stored = Files.readString(checksumFile).trim(); }
                catch (IOException ignored) {}
            }
            String actual = null;
            try {
                if (Files.exists(logFile)) actual = sha256(logFile);
            } catch (IOException | NoSuchAlgorithmException e) {
                CoffeesAeroAuth.LOGGER.warn("[Watchdog] Could not hash {} at startup", logFile, e);
            }

            if (stored != null && actual != null && !stored.equals(actual)) {
                startupDesync = true;
                startupSuspectCopy = quarantine("startup");
                CoffeesAeroAuth.LOGGER.warn(
                    "[Watchdog] {}.checksum did not match {}.log at startup. This is the expected "
                    + "result of an unclean shutdown (the log is appended BEFORE the checksum is "
                    + "written, so a hard kill leaves the checksum behind). Adopting the on-disk "
                    + "file as the new baseline; a copy was kept at {}. NOT treated as tampering.",
                    name, name, startupSuspectCopy);
            }

            // Adopt reality, always. This is what stops the old every-60s alert loop.
            lastChecksum = actual;
            if (actual != null) writeChecksum(actual);
            reprimeDigest();
        }
    }

    /** True once if the stored checksum disagreed with the log at boot. Clears on read. */
    public boolean consumeStartupDesync() {
        if (!startupDesync) return false;
        startupDesync = false;
        return true;
    }

    /** Path of the startup forensic copy, or {@code "(none)"}. */
    public String startupSuspectCopy() {
        return startupSuspectCopy == null ? "(none)" : startupSuspectCopy;
    }

    public String fileName() { return name + ".log"; }

    /** Appends one line in format: [timestamp] [CATEGORY] message. Non-blocking for the caller. */
    public void log(String category, String message) {
        String line = "[" + FMT.format(Instant.now()) + "] [" + category + "] " + message + System.lineSeparator();
        AsyncIo.submit(() -> {
            synchronized (lock) {
                try {
                    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                    Files.write(logFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    lastChecksum = updateChecksum(bytes);
                    writeChecksum(lastChecksum);
                } catch (IOException | NoSuchAlgorithmException e) {
                    CoffeesAeroAuth.LOGGER.error("Audit log write failed ({})", logFile, e);
                }
            }
        });
    }

    /** Lock held. Incremental when possible; identical result to sha256(whole file). */
    private String updateChecksum(byte[] appended) throws IOException, NoSuchAlgorithmException {
        if (runningDigest != null) {
            runningDigest.update(appended);
            try {
                MessageDigest snapshot = (MessageDigest) runningDigest.clone();
                return HexFormat.of().formatHex(snapshot.digest());
            } catch (CloneNotSupportedException e) {
                runningDigest = null;   // provider can't clone — degrade to slow path below
            }
        }
        return sha256(logFile);
    }

    /**
     * Verifies the log against what we last wrote, and rotates the backup on a clean pass.
     *
     * <p><b>Never restores and never overwrites.</b> On a mismatch the current file is copied aside
     * as {@code <name>.log.suspect-<utc>} and the baseline is re-seeded from disk, so the same
     * mismatch cannot be reported a second time. The {@code .bak} is kept purely as a reference
     * copy for a human to diff against — {@link #restoreFromBackup()} is now manual-only.
     *
     * <p>(Runs on the watchdog scheduler thread — a full re-read here is fine.)
     */
    public IntegrityResult verifyAndBackup() {
        synchronized (lock) {
            if (!Files.exists(logFile)) return IntegrityResult.CLEAN;
            String actual;
            try {
                actual = sha256(logFile);
            } catch (IOException | NoSuchAlgorithmException e) {
                CoffeesAeroAuth.LOGGER.error("[Watchdog] {} checksum failed", name, e);
                return IntegrityResult.IO_ERROR;   // an unreadable file is not evidence of tampering
            }

            if (lastChecksum != null && !actual.equals(lastChecksum)) {
                quarantine("runtime");
                // RE-SEED — the invariant. Without this the mismatch persists and re-alerts forever,
                // which is exactly what happened on 2026-08-19.
                lastChecksum = actual;
                writeChecksum(actual);
                reprimeDigest();
                return IntegrityResult.MODIFIED;
            }

            try {
                Files.copy(logFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                CoffeesAeroAuth.LOGGER.warn("[Watchdog] Could not refresh {}", backupFile, e);
            }
            return IntegrityResult.CLEAN;
        }
    }

    /**
     * Copies the log aside for forensics. Returns the file name, or {@code null}.
     *
     * <p>Lock held. Never throws — a failed forensic copy must not break the integrity check.
     */
    private String quarantine(String reason) {
        if (!Files.exists(logFile)) return null;
        Path dest = logFile.resolveSibling(
            name + ".log.suspect-" + FILE_STAMP.format(Instant.now()) + "-" + reason);
        try {
            Files.copy(logFile, dest, StandardCopyOption.REPLACE_EXISTING);
            pruneSuspectCopies();
            return dest.getFileName().toString();
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.warn("[Watchdog] Could not quarantine {}", logFile, e);
            return null;
        }
    }

    /** Keeps only the newest {@link #MAX_SUSPECT_COPIES} forensic copies so they can't fill the disk. */
    private void pruneSuspectCopies() {
        try (DirectoryStream<Path> ds =
                 Files.newDirectoryStream(logFile.getParent(), name + ".log.suspect-*")) {
            List<Path> found = new ArrayList<>();
            ds.forEach(found::add);
            if (found.size() <= MAX_SUSPECT_COPIES) return;
            found.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
            for (int i = 0; i < found.size() - MAX_SUSPECT_COPIES; i++) {
                try { Files.deleteIfExists(found.get(i)); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
            // Pruning is housekeeping; failing to prune must never affect the check.
        }
    }

    /**
     * MANUAL recovery only — an admin choosing to roll the log back to the last clean copy.
     *
     * <p>⚠ No longer called automatically. It used to run on every detection, which copied an OLDER
     * backup over the live log and destroyed real audit entries in response to what was almost
     * always a stale checksum. Returns true if a restore actually happened — the old {@code void}
     * signature is why the alert could claim "Backup restored" when nothing had been.
     */
    public boolean restoreFromBackup() {
        synchronized (lock) {
            if (!Files.exists(backupFile)) return false;
            try {
                quarantine("pre-restore");     // never lose the current content
                Files.copy(backupFile, logFile, StandardCopyOption.REPLACE_EXISTING);
                lastChecksum = sha256(logFile);
                writeChecksum(lastChecksum);
                reprimeDigest();
                CoffeesAeroAuth.LOGGER.warn("[Watchdog] {}.log restored from backup on request.", name);
                return true;
            } catch (IOException | NoSuchAlgorithmException e) {
                CoffeesAeroAuth.LOGGER.error("Failed to restore {} backup", name, e);
                return false;
            }
        }
    }

    /** Lock held. Rebuilds the running digest to match the file's current content. */
    private void reprimeDigest() {
        try {
            runningDigest = MessageDigest.getInstance("SHA-256");
            if (Files.exists(logFile)) runningDigest.update(Files.readAllBytes(logFile));
        } catch (IOException | NoSuchAlgorithmException e) {
            runningDigest = null;
        }
    }

    /** Lock held. Best-effort — a failed checksum write self-corrects on the next append. */
    private void writeChecksum(String value) {
        if (value == null) return;
        try {
            Files.writeString(checksumFile, value);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.warn("[Watchdog] Could not write {}", checksumFile, e);
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(md.digest());
    }
}
