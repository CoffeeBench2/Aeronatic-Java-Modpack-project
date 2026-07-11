package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 */
public class AuditLogger {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));

    private final Path logFile;
    private final Path backupFile;
    private final Path checksumFile;
    private volatile String lastChecksum;
    private final Object lock = new Object();
    private MessageDigest runningDigest;   // guarded by lock; SHA-256 of the file's full content

    public AuditLogger(String name, Path dataDir) {
        logFile      = dataDir.resolve(name + ".log");
        backupFile   = dataDir.resolve(name + ".log.bak");
        checksumFile = dataDir.resolve(name + ".checksum");
    }

    public void initialize() {
        synchronized (lock) {
            if (Files.exists(checksumFile)) {
                try { lastChecksum = Files.readString(checksumFile).trim(); }
                catch (IOException ignored) {}
            }
            try {
                runningDigest = MessageDigest.getInstance("SHA-256");
                if (Files.exists(logFile)) runningDigest.update(Files.readAllBytes(logFile));
            } catch (IOException | NoSuchAlgorithmException e) {
                runningDigest = null;   // falls back to whole-file hashing per append
                CoffeesAeroAuth.LOGGER.warn("Audit digest priming failed ({}) — slow path.", logFile);
            }
        }
    }

    /** Appends one line in format: [timestamp] [CATEGORY] message. Non-blocking for the caller. */
    public void log(String category, String message) {
        String line = "[" + FMT.format(Instant.now()) + "] [" + category + "] " + message + System.lineSeparator();
        AsyncIo.submit(() -> {
            synchronized (lock) {
                try {
                    byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                    Files.write(logFile, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    lastChecksum = updateChecksum(bytes);
                    Files.writeString(checksumFile, lastChecksum);
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
     * Verifies the log file checksum.
     * If clean, rotates backup. Returns false if tampering detected.
     * (Runs on the watchdog scheduler thread — full re-read here is fine.)
     */
    public boolean verifyAndBackup() {
        synchronized (lock) {
            if (!Files.exists(logFile)) return true;
            try {
                String actual = sha256(logFile);
                if (lastChecksum != null && !actual.equals(lastChecksum)) {
                    return false; // external modification detected
                }
                Files.copy(logFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException | NoSuchAlgorithmException e) {
                CoffeesAeroAuth.LOGGER.error("Audit checksum failed", e);
                return false;
            }
        }
    }

    public void restoreFromBackup() {
        synchronized (lock) {
            if (!Files.exists(backupFile)) return;
            try {
                Files.copy(backupFile, logFile, StandardCopyOption.REPLACE_EXISTING);
                lastChecksum = sha256(logFile);
                Files.writeString(checksumFile, lastChecksum);
                reprimeDigest();
                CoffeesAeroAuth.LOGGER.warn("Audit log restored from backup.");
            } catch (IOException | NoSuchAlgorithmException e) {
                CoffeesAeroAuth.LOGGER.error("Failed to restore audit log backup", e);
            }
        }
    }

    /** Lock held. Rebuilds the running digest after the file content was replaced. */
    private void reprimeDigest() {
        try {
            runningDigest = MessageDigest.getInstance("SHA-256");
            if (Files.exists(logFile)) runningDigest.update(Files.readAllBytes(logFile));
        } catch (IOException | NoSuchAlgorithmException e) {
            runningDigest = null;
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(md.digest());
    }
}
