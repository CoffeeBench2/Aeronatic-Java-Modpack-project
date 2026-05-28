package com.coffeesaerosmp.auth.events;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.UUIDUtil;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.NetUtil;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

public class PlayerAuthEvents {

    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (CoffeesAeroAuth.AUTH_MANAGER == null) return;

        boolean isOffline = !UUIDUtil.isPremiumUUID(player.getUUID());

        // Detect first-ever join BEFORE auth manager processes the player
        // (auth manager sets firstJoinComplete=true for premium players during onPlayerJoin)
        boolean isFirstJoin = CoffeesAeroAuth.PROFILE_STORE != null
            && CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID()) == null;

        // Watchdog pre-join checks (IP ban, UUID switch, lookalike) — may kick and return early
        if (CoffeesAeroAuth.WATCHDOG != null && CoffeesAeroAuth.WATCHDOG.checkJoin(player, isOffline)) return;

        CoffeesAeroAuth.AUTH_MANAGER.onPlayerJoin(player);

        boolean authed = CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID());

        // Discord + Obsidian join events (only fires if auth completed immediately, i.e. premium)
        if (authed) {
            PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
            String badge = profile != null && profile.getAccountType() == PlayerProfile.AccountType.PREMIUM
                ? "[✦ Verified]" : "[◈ Offline]";

            if (CoffeesAeroAuth.DISCORD_BRIDGE != null) {
                postDiscordJoin(player, profile, isFirstJoin, badge);
            }
            if (CoffeesAeroAuth.OBSIDIAN_EXPORTER != null && profile != null) {
                CoffeesAeroAuth.OBSIDIAN_EXPORTER.onPlayerJoin(player, profile, isFirstJoin, badge);
            }
        }

        // Register premium display name in watchdog lookalike index
        if (!isOffline && CoffeesAeroAuth.WATCHDOG != null) {
            PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
            if (profile != null) CoffeesAeroAuth.WATCHDOG.addPremiumName(profile.displayName);
        }
    }

    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (CoffeesAeroAuth.AUTH_MANAGER == null) return;

        // Update playtime FIRST, then let Obsidian read the updated profile
        CoffeesAeroAuth.AUTH_MANAGER.onPlayerLeave(player);

        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE != null
            ? CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID()) : null;

        if (CoffeesAeroAuth.OBSIDIAN_EXPORTER != null) {
            CoffeesAeroAuth.OBSIDIAN_EXPORTER.onPlayerLeave(player, profile);
        }
        if (CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            CoffeesAeroAuth.DISCORD_BRIDGE.onPlayerLeave(player);
        }
    }

    // ── Also called from AuthManager.onAuthenticated for offline players ─────
    // (Offline players complete auth on /login, not on join — this is their deferred hook)
    public static void onOfflinePlayerAuthenticated(ServerPlayer player) {
        if (CoffeesAeroAuth.PROFILE_STORE == null) return;
        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
        if (profile == null) return;
        boolean isFirstJoin = !profile.firstJoinComplete; // set to true just before this call
        String badge = "[◈ Offline]";

        if (CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            postDiscordJoin(player, profile, isFirstJoin, badge);
        }
        if (CoffeesAeroAuth.OBSIDIAN_EXPORTER != null) {
            CoffeesAeroAuth.OBSIDIAN_EXPORTER.onPlayerJoin(player, profile, isFirstJoin, badge);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void postDiscordJoin(ServerPlayer player, PlayerProfile profile,
                                         boolean isFirst, String badge) {
        if (profile == null) return;
        CoffeesAeroAuth.DISCORD_BRIDGE.onPlayerJoin(player, isFirst, badge);
        long hours = profile.totalPlaytimeSeconds / 3600;
        CoffeesAeroAuth.DISCORD_BRIDGE.checkMilestones(player, hours);
    }
}
