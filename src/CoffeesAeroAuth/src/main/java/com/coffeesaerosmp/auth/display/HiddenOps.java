package com.coffeesaerosmp.auth.display;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-op "hide me" toggle. Display state only, so it lives in a JSON file next to
 * {@code clan_tags.json} rather than the DB — the no-DB-for-non-essentials rule.
 * Survives restart: an op who logs out hidden comes back hidden.
 */
public final class HiddenOps {

    private static final Set<UUID> HIDDEN = ConcurrentHashMap.newKeySet();
    private static volatile Path file;

    private HiddenOps() {}

    public static void initialize(Path dataDir) {
        file = dataDir.resolve("hidden_ops.json");
        HIDDEN.clear();
        if (!Files.exists(file)) return;
        try {
            JsonArray a = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
            a.forEach(e -> {
                try { HIDDEN.add(UUID.fromString(e.getAsString())); }
                catch (IllegalArgumentException ignored) {}
            });
            CoffeesAeroAuth.LOGGER.info("[Display] Loaded {} hidden ops.", HIDDEN.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Display] hidden_ops.json load failed: {}", e.getMessage());
        }
    }

    public static boolean isHidden(UUID uuid) { return HIDDEN.contains(uuid); }

    /** Toggles and persists. Returns the NEW state (true = now hidden). */
    public static boolean toggle(UUID uuid) {
        boolean nowHidden;
        if (HIDDEN.contains(uuid)) { HIDDEN.remove(uuid); nowHidden = false; }
        else { HIDDEN.add(uuid); nowHidden = true; }
        persist();
        return nowHidden;
    }

    /** Number of currently-online players who are hidden — used to keep the tab footer count honest. */
    public static int hiddenCount(java.util.List<net.minecraft.server.level.ServerPlayer> players) {
        int n = 0;
        for (var p : players) if (HIDDEN.contains(p.getUUID())) n++;
        return n;
    }

    private static void persist() {
        Path f = file;
        if (f == null) return;
        JsonArray a = new JsonArray();
        HIDDEN.forEach(u -> a.add(u.toString()));
        String json = a.toString();
        AsyncIo.submit(() -> {
            try { Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[Display] hidden_ops save failed: {}", e.getMessage()); }
        });
    }
}
