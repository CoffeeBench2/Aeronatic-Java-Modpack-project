package com.coffeesaerosmp.auth.events;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.UUIDUtil;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.NetUtil;
import com.coffeesaerosmp.auth.watchdog.IpBanManager;
import net.minecraft.network.chat.Component;
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

        // Auth IP ban check — 30s cooldown after too many failed login attempts.
        // Must run before watchdog so it shows the right message (not the generic IP ban message).
        if (CoffeesAeroAuth.WATCHDOG != null) {
            IpBanManager ipBans = CoffeesAeroAuth.WATCHDOG.getIpBanManager();
            String ip = NetUtil.getPlayerIP(player);
            if (ipBans.isAuthBanned(ip)) {
                long secs = ipBans.getAuthRemainingSeconds(ip);
                player.connection.disconnect(Component.literal(
                    "§cToo many failed attempts. Please wait §e" + secs + "§c second" + (secs == 1 ? "" : "s") + "."));
                return;
            }
        }

        // Watchdog pre-join checks (IP ban, UUID switch, lookalike) — may kick and return early
        if (CoffeesAeroAuth.WATCHDOG != null && CoffeesAeroAuth.WATCHDOG.checkJoin(player, isOffline)) return;

        CoffeesAeroAuth.AUTH_MANAGER.onPlayerJoin(player);

        // Discord + Obsidian join events only fire once auth completes. Premium players are now
        // held until the proxy's player_type signal arrives (see onPremiumResolved); offline
        // players post from AuthManager.onAuthenticated. Only operators (bypassAuthForOps) are
        // authenticated synchronously here.
        if (CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) {
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
    }

    // ── Called from AuthManager.enterPremiumFlow once the proxy confirms a player is premium ──
    public static void onPremiumResolved(ServerPlayer player, boolean isFirstJoin) {
        if (CoffeesAeroAuth.PROFILE_STORE == null) return;
        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
        String badge = "[✦ Verified]";

        if (CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            postDiscordJoin(player, profile, isFirstJoin, badge);
        }
        if (CoffeesAeroAuth.OBSIDIAN_EXPORTER != null && profile != null) {
            CoffeesAeroAuth.OBSIDIAN_EXPORTER.onPlayerJoin(player, profile, isFirstJoin, badge);
        }
        if (CoffeesAeroAuth.WATCHDOG != null && profile != null) {
            CoffeesAeroAuth.WATCHDOG.addPremiumName(profile.displayName);
            // Premium logins were never counted — the daily digest only saw the three offline
            // recordSuccessfulLogin call sites, so "Successful Logins" sat near 0.
            CoffeesAeroAuth.WATCHDOG.recordSuccessfulLogin(player.getUUID(),
                NetUtil.getPlayerIP(player), profile.displayName, true);
        }
        if (CoffeesAeroAuth.DISCORD_BRIDGE != null)
            CoffeesAeroAuth.DISCORD_BRIDGE.updatePlayerCount(player.getServer().getPlayerList().getPlayerCount());
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
            CoffeesAeroAuth.DISCORD_BRIDGE.updatePlayerCount(
                Math.max(0, player.getServer().getPlayerList().getPlayerCount() - 1));
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
