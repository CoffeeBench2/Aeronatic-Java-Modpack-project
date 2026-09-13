package com.coffeesaerosmp.core.announce;

import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists which announcement the player has already seen, so the main-menu button only shows its
 * "NEW" badge until they open it once — then stays quiet until the NEXT update (2026-07-12 request:
 * "after viewing no need to highlight it again"). Stored as the latest-seen version string in a tiny
 * file next to the config; survives restarts and is per-instance.
 */
public final class AnnouncementState {

    private static final Logger LOGGER = LoggerFactory.getLogger("CoffeesAeroCore-Announce");
    private static final String SEEN_FILE = "coffees_aero_announcements_seen.txt";

    private static volatile String seenVersion;   // cached
    private static volatile boolean loaded;

    private AnnouncementState() {}

    /**
     * True when the newest RELEASE hasn't been opened yet → badge the button.
     *
     * <h2>🔴 Must key on {@link AnnouncementData#latestRelease()}, never {@code latest()}</h2>
     * {@code latest()} is {@code entries[0]}, which is routinely a <b>teaser</b> ("On the horizon…",
     * "Coming Soon…") whose version string is a fixed label rather than a number. Keying on it wrote
     * that label into the seen-file, and since it never changes, this method returned false on every
     * launch thereafter — permanently killing the What's New popup and the NEW badge, and silently
     * skipping every real release. Verified on a live client 2026-09-09: both the seen-file and
     * {@code entries[0]} read "On the horizon…".
     */
    public static boolean hasUnseen() {
        AnnouncementData.Entry latest = AnnouncementData.latestRelease();
        if (latest == null || latest.version() == null || latest.version().isBlank()) return false;
        return !latest.version().equals(currentSeen());
    }

    /** Call when the player opens the Announcements screen — clears the badge for this version. */
    public static void markLatestSeen() {
        AnnouncementData.Entry latest = AnnouncementData.latestRelease();
        if (latest == null) return;      // teasers only: nothing to mark, so the badge stays honest
        write(latest.version());
    }

    private static String currentSeen() {
        if (!loaded) {
            loaded = true;
            try {
                Path f = FMLPaths.CONFIGDIR.get().resolve(SEEN_FILE);
                if (Files.isRegularFile(f)) seenVersion = Files.readString(f, StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                LOGGER.warn("[Announce] seen-state read failed: {}", e.getMessage());
            }
        }
        return seenVersion == null ? "" : seenVersion;
    }

    private static void write(String version) {
        seenVersion = version;
        loaded = true;
        try {
            Files.writeString(FMLPaths.CONFIGDIR.get().resolve(SEEN_FILE), version, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[Announce] seen-state write failed: {}", e.getMessage());
        }
    }
}
