package com.coffeesaerosmp.skins.api;

import com.coffeesaerosmp.skins.server.SkinService;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public entry points for other server mods (CoffeesAeroAuth). Auth installs its profile-store
 * backend at startup and calls {@link #applyPremium} when the gate cookie verifies a premium player.
 */
public final class AeroSkinsApi {

    private AeroSkinsApi() {}

    private static volatile SkinBackend backend = new InMemoryBackend();

    /** Install the storage/policy backend (CoffeesAeroAuth does this in commonSetup). */
    public static void setBackend(SkinBackend b) {
        if (b != null) backend = b;
    }

    public static SkinBackend backend() {
        return backend;
    }

    /** Premium player verified by the gate cookie: fetch + wear their REAL Mojang skin and cape. */
    public static void applyPremium(ServerPlayer player, UUID realMojangUuid) {
        SkinService.applyPremium(player, realMojangUuid);
    }

    /** Fallback backend: in-memory persistence, no cape, /skin open to everyone. */
    private static final class InMemoryBackend implements SkinBackend {
        private final Map<UUID, String>  textures = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> changes  = new ConcurrentHashMap<>();

        @Override public String savedTextures(UUID player) { return textures.get(player); }
        @Override public void saveTextures(UUID player, String v) {
            if (v == null) textures.remove(player); else textures.put(player, v);
        }
        @Override public int skinChangesUsed(UUID player) { return changes.getOrDefault(player, 0); }
        @Override public void addSkinChangeUsed(UUID player) { changes.merge(player, 1, Integer::sum); }
        @Override public boolean capeAllowed(UUID player) { return false; }
        @Override public String skinCommandDenyReason(ServerPlayer player) { return null; }
    }
}
