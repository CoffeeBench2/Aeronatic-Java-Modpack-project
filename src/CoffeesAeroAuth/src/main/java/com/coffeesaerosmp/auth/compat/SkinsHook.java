package com.coffeesaerosmp.auth.compat;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.UUID;

/**
 * Bridge to the CoffeesAeroSkins mod (which owns ALL skin logic since auth 1.6.0 — fetching, /skin,
 * client sync). Auth remains the identity/policy source: it installs its MySQL profile store as the
 * skins backend and forwards gate-verified premium UUIDs.
 *
 * <p>CoffeesAeroSkins is a compileOnly dependency; every skins class is referenced only inside
 * {@link Impl}, which is classloaded solely when the mod is actually present.</p>
 */
public final class SkinsHook {

    private SkinsHook() {}

    private static volatile boolean available;

    /** Call once from the mod constructor. */
    public static void install() {
        available = ModList.get().isLoaded("coffeesaeroskins");
        if (available) {
            Impl.install();
            CoffeesAeroAuth.LOGGER.info("[Skins] CoffeesAeroSkins detected — auth profile-store backend installed.");
        } else {
            CoffeesAeroAuth.LOGGER.warn("[Skins] CoffeesAeroSkins NOT present — premium auto-skins and /skin are disabled.");
        }
    }

    /** Premium player verified by the gate cookie → wear their real Mojang skin. */
    public static void applyPremium(ServerPlayer player, UUID realMojangUuid) {
        if (available) Impl.applyPremium(player, realMojangUuid);
    }

    /** Only classloaded when coffeesaeroskins is in the mod list. */
    private static final class Impl {

        static void install() {
            com.coffeesaerosmp.skins.api.AeroSkinsApi.setBackend(new AuthBackend());
        }

        static void applyPremium(ServerPlayer player, UUID uuid) {
            com.coffeesaerosmp.skins.api.AeroSkinsApi.applyPremium(player, uuid);
        }

        /** Skins storage + policy backed by the auth MySQL profile store. */
        private static final class AuthBackend implements com.coffeesaerosmp.skins.api.SkinBackend {

            private PlayerProfile profile(UUID player) {
                return CoffeesAeroAuth.AUTH_MANAGER != null
                    ? CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player) : null;
            }

            @Override public String savedTextures(UUID player) {
                PlayerProfile p = profile(player);
                return p != null && p.skinUrl != null && !p.skinUrl.isBlank() ? p.skinUrl : null;
            }

            @Override public void saveTextures(UUID player, String texturesOrNull) {
                PlayerProfile p = profile(player);
                if (p == null) return;
                p.skinUrl = texturesOrNull;
                CoffeesAeroAuth.AUTH_MANAGER.getStore().save(p);
            }

            @Override public int skinChangesUsed(UUID player) {
                PlayerProfile p = profile(player);
                return p != null ? p.skinChangesUsed : 0;
            }

            @Override public void addSkinChangeUsed(UUID player) {
                PlayerProfile p = profile(player);
                if (p == null) return;
                p.skinChangesUsed++;
                CoffeesAeroAuth.AUTH_MANAGER.getStore().save(p);
            }

            @Override public boolean capeAllowed(UUID player) {
                PlayerProfile p = profile(player);
                return p != null && p.capeEnabled;
            }

            @Override public String skinCommandDenyReason(ServerPlayer player) {
                if (CoffeesAeroAuth.AUTH_MANAGER == null
                    || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID()))
                    return "Authenticate first.";
                PlayerProfile p = profile(player.getUUID());
                if (p != null && p.getAccountType() == PlayerProfile.AccountType.PREMIUM)
                    return "Premium players already use their own Mojang skin & cape — /skin is for offline players.";
                return null;
            }
        }
    }
}
