package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.store.Entitlements.Selection;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-only cosmetic choices for ops with NO Mojang link (an offline account, e.g. on a test server). The store
 * keys everything on the Mojang uuid, so such an op could otherwise never try a cosmetic. Nothing here is written
 * to the database; it is dropped on logout.
 */
public final class PreviewSelections {

    private static final Map<UUID, Selection> BY_LOCAL = new ConcurrentHashMap<>();

    private PreviewSelections() {}

    public static Optional<Selection> get(UUID localUuid) {
        return localUuid == null ? Optional.empty() : Optional.ofNullable(BY_LOCAL.get(localUuid));
    }

    public static void put(UUID localUuid, Selection s) {
        if (localUuid != null && s != null) BY_LOCAL.put(localUuid, s);
    }

    public static void forget(UUID localUuid) {
        if (localUuid != null) BY_LOCAL.remove(localUuid);
    }
}
