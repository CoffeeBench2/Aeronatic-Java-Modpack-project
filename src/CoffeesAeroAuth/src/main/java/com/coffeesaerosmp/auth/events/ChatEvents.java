package com.coffeesaerosmp.auth.events;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.chat.ChatFilter;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.Sounds;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.coffeesaerosmp.auth.watchdog.Severity;
import com.coffeesaerosmp.auth.watchdog.WatchdogEvent;
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

        // Word filter. Runs BEFORE anything is rebroadcast, bridged to Discord or written to the
        // console — a blocked message must not reach any of the three.
        String filtered = screen(player, profile, event.getRawText());
        if (filtered == null) return;

        boolean premium    = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM;
        String badge       = premium ? TextUtil.VERIFIED_BADGE : TextUtil.OFFLINE_BADGE;
        String nameColor   = premium ? "§f" : "§7";   // offline names greyish, premium white
        String displayName = profile.displayName != null ? profile.displayName : profile.username;
        String rawText     = filtered;

        // Clan tag (FTB party) in front of the name — display layer only.
        String clanTag = com.coffeesaerosmp.auth.clan.ClanTags.tagFor(player);
        String tagPart = clanTag != null
            ? "§7[" + com.coffeesaerosmp.auth.clan.ClanTags.colorFor(player) + clanTag + "§7] " : "";

        // Styled name (/namecolor: colors, hex, formats, §k scramble, rainbow) — falls back to the
        // plain premium/offline coloring when the player has no style. Legacy rgbNames still work
        // (NameStyles handles the fallback internally).
        Component styled = com.coffeesaerosmp.auth.util.NameStyles.nameComponent(
            player.getUUID(), profile.username, displayName);
        Component nameComp = styled != null ? styled : Component.literal(nameColor + displayName);

        Component formatted = Component.literal(badge + tagPart).append(nameComp)
            .append(Component.literal(" §8» §r" + rawText));
        // Admins get the real account name inline; everyone else sees only the display name.
        String realName = profile.username;
        Component adminVariant = realName != null && !realName.equals(displayName)
            ? Component.literal(badge + tagPart).append(nameComp)
                .append(Component.literal(" §8(" + realName + ")§r §8» §r" + rawText))
            : formatted;
        boolean senderInLobby =
            player.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION;
        for (ServerPlayer viewer : player.getServer().getPlayerList().getPlayers()) {
            boolean viewerInLobby =
                viewer.level().dimension() == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION;
            if (viewerInLobby != senderInLobby) continue;   // lobby and world are separate chat channels
            viewer.sendSystemMessage(viewer.hasPermissions(2) ? adminVariant : formatted);
        }
        player.getServer().sendSystemMessage(adminVariant);   // console log keeps both names

        // Bridge to Discord public channel — WORLD chat only (lobby chatter stays in the lobby).
        if (!senderInLobby && CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            // Strip formatting codes from badge for Discord
            String cleanBadge = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM
                ? "[✦ Verified]" : "[◈ Offline]";
            // skinUrl (the stored base64 textures) is what makes OFFLINE players show a real head:
            // their name resolves to nothing at Mojang, so the account name alone gave Steve.
            CoffeesAeroAuth.DISCORD_BRIDGE.onPlayerChat(cleanBadge,
                (clanTag != null ? "[" + clanTag + "] " : "") + displayName, rawText,
                player.getGameProfile().getName(), profile.skinUrl);
        }
    }

    /** Violations per player this session — resets on restart; enough to spot a spree in an alert. */
    private static final java.util.Map<java.util.UUID, Integer> strikes =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Screens a message through {@link ChatFilter}.
     *
     * @return the text to publish (censored where needed), or null if the message must be dropped
     */
    private static String screen(ServerPlayer player, PlayerProfile profile, String raw) {
        if (!AuthConfig.CHAT_FILTER_ENABLED.get()) return raw;
        ChatFilter.Result r;
        try {
            r = ChatFilter.check(raw);
        } catch (Throwable t) {
            // A filter fault must never swallow chat. Fail OPEN: a missed word is recoverable,
            // a server where nobody can talk is not.
            CoffeesAeroAuth.LOGGER.warn("[ChatFilter] check failed, passing message through", t);
            return raw;
        }
        if (r == null) return raw;

        int strike = strikes.merge(player.getUUID(), 1, Integer::sum);
        String who = profile.displayName != null ? profile.displayName : profile.username;

        if (r.action() == ChatFilter.Action.BLOCK) {
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX
                + "§c✖ Message blocked. §7That language is not allowed here, and staff have been "
                + "notified. Repeat offences are actioned."));
            Sounds.error(player);
            alert(Severity.HIGH, "Chat: Blocked Term", "Message blocked, staff notified",
                who, profile.username, r.word(), raw, strike);
            // ⚠ The original text goes to the ALERT (staff need to see what was actually said) and
            // nowhere else — not to chat, not to the Discord bridge, not to the console broadcast.
            CoffeesAeroAuth.LOGGER.info("[ChatFilter] BLOCKED {} ({}) term='{}' strike={}",
                who, profile.username, r.word(), strike);
            return null;
        }

        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§eMind your language §7— that word was filtered."));
        alert(Severity.LOW, "Chat: Censored Word", "Word starred out, message delivered",
            who, profile.username, r.word(), raw, strike);
        return r.text();
    }

    private static void alert(Severity sev, String title, String action,
                              String display, String account, String word, String raw, int strike) {
        if (CoffeesAeroAuth.WATCHDOG == null) return;
        try {
            CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(sev, title, action,
                "Player", display,
                "Account", account == null ? "?" : account,
                "Term", word,
                "Message", raw.length() > 300 ? raw.substring(0, 300) + "…" : raw,
                "Violations this session", String.valueOf(strike)));
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[ChatFilter] alert failed", t);
        }
    }

    /** Forget a player's strike count when they disconnect. */
    public static void onPlayerLogout(ServerPlayer player) {
        strikes.remove(player.getUUID());
    }
}
