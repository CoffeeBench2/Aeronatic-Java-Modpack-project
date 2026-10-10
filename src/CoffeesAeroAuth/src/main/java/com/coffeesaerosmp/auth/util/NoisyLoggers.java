package com.coffeesaerosmp.auth.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Turns down other mods' loggers that flood debug.log FROM THE TICK THREAD.
 *
 * <p>2026-10-10, S3: Adaptive Performance Tweaks' spawn module logged one DEBUG line per denied natural
 * spawn — 1,132,628 lines in ~2 minutes (~9,400/s), all formatted and written on the server thread.
 * debug.log rolled every ~2 minutes, so it never held more than ~10 minutes of history (it could not
 * explain a 333-item loss the same day). APT has no config switch for that line, so it is done here.
 * Only DEBUG/TRACE are dropped; that logger's INFO and above still reach the logs.
 */
public final class NoisyLoggers {

    /** Logger names exactly as they appear in the log's [name/] bracket. */
    static final String[] QUIET = {
        "Adaptive Performance Tweaks.spawn",
    };

    private NoisyLoggers() {}

    public static void quiet() {
        for (String name : QUIET) {
            try {
                Configurator.setLevel(name, Level.INFO);
            } catch (Throwable ignored) {
                // log4j-core missing or reconfigured: leave logging exactly as it was.
            }
        }
    }
}
