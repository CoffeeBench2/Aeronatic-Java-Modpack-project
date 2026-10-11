package com.coffeesaerosmp.auth.store;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online players who may PREVIEW every cosmetic: ops at permission 2+ (owner 2026-10-12: "1st ops can test the
 * cosmetics, then the shop"). Refreshed once a second by the cosmetic engine from the live op list, and cleared on
 * logout — never persisted, so a de-op takes effect within a second and nothing needs cleaning up.
 * Kept free of Minecraft types so the store layer stays pure.
 */
public final class Previewers {

    private static final Set<UUID> PREVIEW = ConcurrentHashMap.newKeySet();

    private Previewers() {}

    public static boolean isPreview(UUID localUuid) {
        return localUuid != null && PREVIEW.contains(localUuid);
    }

    /** Replace the whole set (the engine's 1 Hz refresh). */
    public static void replaceWith(Set<UUID> ops) {
        PREVIEW.retainAll(ops);
        PREVIEW.addAll(ops);
    }

    public static void forget(UUID localUuid) {
        if (localUuid != null) PREVIEW.remove(localUuid);
    }
}
