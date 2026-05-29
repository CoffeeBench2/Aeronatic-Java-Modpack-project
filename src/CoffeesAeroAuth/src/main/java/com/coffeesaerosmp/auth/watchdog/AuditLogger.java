package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

import java.io.IOException;
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
 */
public class AuditLogger {

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"));

    private final Path logFile;
    private final Path backupFile;
    private final Path checksumFile;
    private volatile String lastChecksum;
    private final Object lock = new Object();

    public AuditLogger(String name, Path dataDir) {
        logFile      = dataDir.resolve(name + ".log");
        backupFile   = dataDir.resolve(name + ".log.bak");
        checksumFile = dataDir.resolve(name + ".checksum");
    }

    public void initialize() {
        if (Files.exists(checksumFile)) {
            try { lastChecksum = Files.readString(checksumFile).trim(); }
            catch (IOException ignored) {}
        }
    }

    /** Appends one line in format: [timestamp] [CATEGORY] message */
    public void log(String category, String message) {
        synchronized (lock) {
            String line = "[" + FMT.format(Instant.now()) + "] [" + category + "] " + message + System.lineSeparator();
            try {
                Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                lastChecksum = sha256(logFile);
                Files.writeString(checksumFile, lastChecksum);
            } catch (IOException | NoSuchAlgorithmException e) {
                CoffeesAeroAuth.LOGGER.error("Audit log write failed ({})", logFile, e);
            }
        }
    }

    /**
     * Verifies the log file checksum.
     * If clean, rotates backup. Returns false if tampering detected.
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
                CoffeesAeroAuth.LOGGER.warn("Audit log restored from backup.");
            } catch (IOException | NoSuchAlgorithmException e) {
                CoffeesAeroAuth.LOGGER.error("Failed to restore audit log backup", e);
            }
        }
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(md.digest());
    }
}
