package com.coffeesaerosmp.skins.api;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Storage + policy provider for the skin engine. On Coffees Aero SMP this is implemented by
 * CoffeesAeroAuth (MySQL profile store + auth/premium policy) and installed via
 * {@link AeroSkinsApi#setBackend}. Without a backend the engine still works, but persistence is
 * in-memory only and every player may use /skin.
 */
public interface SkinBackend {

    /** The saved base64 textures value for this player, or {@code null} if none. */
    String savedTextures(UUID player);

    /** Persist the player's textures value ({@code null} clears it). */
    void saveTextures(UUID player, String texturesOrNull);

    /** How many lifetime /skin changes this player has consumed. */
    int skinChangesUsed(UUID player);

    /** Consume one /skin change (called only after a SUCCESSFUL apply). */
    void addSkinChangeUsed(UUID player);

    /** Whether this player may wear capes (premium accounts). */
    boolean capeAllowed(UUID player);

    /** {@code null} if the player may use /skin right now; otherwise a user-facing deny message
     *  (e.g. not authenticated yet, or premium accounts that already wear their real skin). */
    String skinCommandDenyReason(ServerPlayer player);

    /**
     * The uuid of the player called {@code name} (account OR display name), online or not, or {@code null}.
     * Used by the staff command {@code /skin admin}. Default: online players, then the server's user cache —
     * CoffeesAeroAuth overrides it with its profile store, which knows offline players and display names.
     */
    default java.util.UUID resolvePlayer(net.minecraft.server.MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        var cache = server.getProfileCache();
        if (cache == null) return null;
        java.util.UUID id = cache.get(name).map(com.mojang.authlib.GameProfile::getId).orElse(null);
        if (id == null) return null;
        // 🔴 On an offline-mode server the cache INVENTS md5("OfflinePlayer:"+name) for any unknown name, so a
        // typo would "succeed" against a player who does not exist. Only accept someone who has really played
        // here: their playerdata file exists. (Boot test 2026-10-04: "Nobody_Zz9" resolved without this.)
        java.nio.file.Path dat = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
            .resolve(id + ".dat");
        return java.nio.file.Files.exists(dat) ? id : null;
    }
}
