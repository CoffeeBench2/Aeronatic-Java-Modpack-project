package com.coffeesaerosmp.auth.events;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;

public class ChatEvents {

    /**
     * Intercepts all chat messages.
     * - Unauthenticated players: message blocked, told to log in.
     * - Authenticated players: message rebroadcast with badge + display name format.
     *
     * Format: [✦ Verified] DisplayName » message
     *      or [◈ Offline] DisplayName » message
     */
    public static void onServerChat(ServerChatEvent event) {
        if (CoffeesAeroAuth.AUTH_MANAGER == null) return;

        ServerPlayer player = event.getPlayer();

        if (!CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal(
                TextUtil.PREFIX + "§cAuthenticate before chatting: §a/login <password>"
            ));
            return;
        }

        PlayerProfile profile = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
        if (profile == null) return;

        event.setCanceled(true);

        String badge       = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM
                             ? TextUtil.VERIFIED_BADGE
                             : TextUtil.OFFLINE_BADGE;
        String displayName = profile.displayName != null ? profile.displayName : profile.username;
        String rawText     = event.getRawText();

        Component formatted = Component.literal(badge + "§f" + displayName + " §8» §r" + rawText);
        player.getServer().getPlayerList().broadcastSystemMessage(formatted, false);

        // Bridge to Discord public channel (no IPs ever go here)
        if (CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            // Strip formatting codes from badge for Discord
            String cleanBadge = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM
                ? "[✦ Verified]" : "[◈ Offline]";
            CoffeesAeroAuth.DISCORD_BRIDGE.onPlayerChat(cleanBadge, displayName, rawText);
        }
    }
}
