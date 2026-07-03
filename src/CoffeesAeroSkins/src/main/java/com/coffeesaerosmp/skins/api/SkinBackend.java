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
}
