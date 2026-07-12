package com.coffeesaerosmp.core;

import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Method;

/**
 * Reflective seam between the always-present menu/UI code and the OPTIONAL in-client updater
 * subsystem ({@code update/**}, {@code version/**}, {@code screen/UpdateScreen}, {@code UpdatingScreen}).
 *
 * <p>Why reflection: the CurseForge distribution ships a Core jar built WITHOUT those classes (they
 * download files from outside the pack, which CurseForge policy disallows — the CF app is itself the
 * updater there, so the mechanism is redundant on that channel). The Modrinth/GitHub jar ships them.
 * All retained code (title screen, disconnect handler) talks to this bridge, never to the updater
 * classes directly, so the exact same source compiles both ways and simply degrades to "connect
 * directly" when the classes are absent. This is a real capability split, not obfuscation.</p>
 */
public final class UpdaterBridge {

    private static final boolean PRESENT;
    private static final Method M_START, M_OUTDATED, M_LATEST, M_URL;

    static {
        boolean present = false;
        Method start = null, outdated = null, latest = null, url = null;
        try {
            Class<?> vc = Class.forName("com.coffeesaerosmp.core.version.VersionCheck");
            start    = vc.getMethod("startAsync");
            outdated = vc.getMethod("isOutdated");
            latest   = vc.getMethod("latestVersion");
            url      = vc.getMethod("downloadUrl");
            present  = true;
        } catch (Throwable ignored) {
            // No updater in this build (CurseForge variant) — bridge no-ops.
        }
        PRESENT = present;
        M_START = start; M_OUTDATED = outdated; M_LATEST = latest; M_URL = url;
    }

    private UpdaterBridge() {}

    /** True only when the updater subsystem is bundled in this jar. */
    public static boolean available() { return PRESENT; }

    /** Begin the once-per-session pack version poll (no-op without the updater). */
    public static void startCheck() {
        if (PRESENT) invokeVoid(M_START);
    }

    /** Whether the version check has determined the pack is stale (false without the updater). */
    public static boolean isPackOutdated() {
        return PRESENT && Boolean.TRUE.equals(invoke(M_OUTDATED, false));
    }

    /** The update screen instance, or {@code null} when the updater isn't bundled. */
    public static Screen openUpdateScreen(Screen parent) {
        if (!PRESENT) return null;
        try {
            Class<?> us = Class.forName("com.coffeesaerosmp.core.screen.UpdateScreen");
            return (Screen) us.getConstructor(Screen.class).newInstance(parent);
        } catch (Throwable t) {
            return null;
        }
    }

    public static String latestVersion() { return String.valueOf(invoke(M_LATEST, "")); }
    public static String downloadUrl()   { return String.valueOf(invoke(M_URL, "")); }

    private static Object invoke(Method m, Object fallback) {
        if (m == null) return fallback;
        try { return m.invoke(null); } catch (Throwable t) { return fallback; }
    }

    private static void invokeVoid(Method m) {
        if (m == null) return;
        try { m.invoke(null); } catch (Throwable ignored) {}
    }
}
