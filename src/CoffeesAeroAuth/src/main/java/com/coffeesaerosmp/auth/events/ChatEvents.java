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

        boolean premium    = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM;
        String badge       = premium ? TextUtil.VERIFIED_BADGE : TextUtil.OFFLINE_BADGE;
        String nameColor   = premium ? "§f" : "§7";   // offline names greyish, premium white
        String displayName = profile.displayName != null ? profile.displayName : profile.username;
        String rawText     = event.getRawText();

        // Clan tag (FTB party) in front of the name — display layer only.
        String clanTag = com.coffeesaerosmp.auth.clan.ClanTags.tagFor(player);
        String tagPart = clanTag != null
            ? "§7[" + com.coffeesaerosmp.auth.clan.ClanTags.colorFor(player) + clanTag + "§7] " : "";

        // RGB name for enabled players (owner etc.) — render the name as a rainbow Component.
        Component nameComp = com.coffeesaerosmp.auth.util.RainbowText.isEnabled(profile.username)
            ? com.coffeesaerosmp.auth.util.RainbowText.gradient(displayName)
            : Component.literal(nameColor + displayName);

        Component formatted = Component.literal(badge + tagPart).append(nameComp)
            .append(Component.literal(" §8» §r" + rawText));
        // Admins get the real account name inline; everyone else sees only the display name.
        String realName = profile.username;
        Component adminVariant = realName != null && !realName.equals(displayName)
            ? Component.literal(badge + tagPart).append(nameComp)
                .append(Component.literal(" §8(" + realName + ")§r §8» §r" + rawText))
            : formatted;
        for (ServerPlayer viewer : player.getServer().getPlayerList().getPlayers()) {
            viewer.sendSystemMessage(viewer.hasPermissions(2) ? adminVariant : formatted);
        }
        player.getServer().sendSystemMessage(adminVariant);   // console log keeps both names

        // Bridge to Discord public channel (no IPs ever go here)
        if (CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            // Strip formatting codes from badge for Discord
            String cleanBadge = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM
                ? "[✦ Verified]" : "[◈ Offline]";
            CoffeesAeroAuth.DISCORD_BRIDGE.onPlayerChat(cleanBadge,
                (clanTag != null ? "[" + clanTag + "] " : "") + displayName, rawText);
        }
    }
}
