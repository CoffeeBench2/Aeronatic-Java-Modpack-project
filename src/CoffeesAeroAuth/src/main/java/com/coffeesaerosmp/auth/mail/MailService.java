package com.coffeesaerosmp.auth.mail;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.SeasonMigration;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * The front door to /mail for the rest of the mod: what to send, to whom, and telling players about it.
 * Storage is {@link MailStore}; the GUI is {@link MailGui}.
 */
public final class MailService {

    public static final String SYSTEM = "Coffee's AeroSMP";

    private MailService() {}

    /** Mail is a feature of the SMP. The lobby shares the DB, so it may COUNT mail but never pay it out. */
    public static boolean enabled() {
        try {
            return AuthConfig.MAIL_ENABLED.get() && CoffeesAeroAuth.DB_MANAGER != null
                && CoffeesAeroAuth.DB_MANAGER.isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /** Mail + welcome mail switched on in config (regardless of whether the DB is reachable right now). */
    public static boolean welcomeConfigured() {
        try {
            return AuthConfig.MAIL_ENABLED.get() && AuthConfig.SEASON_WELCOME_MAIL.get()
                && !LobbyHandoff.isLobbyRole();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean canOpen() {
        return enabled() && !LobbyHandoff.isLobbyRole();
    }

    public static long expiry() {
        int days = AuthConfig.MAIL_EXPIRY_DAYS.get();
        return days <= 0 ? 0 : System.currentTimeMillis() + days * 86_400_000L;
    }

    /** Season number used in dedupe keys, so Season 4 can reward the same levels again. */
    public static int season() {
        return SeasonMigration.CURRENT_SEASON;
    }

    // ── system mail ──────────────────────────────────────────────────────────

    /**
     * The starter spurs + welcome items, as mail. Idempotent per player per season by dedupe key, so it
     * is safe to call from both first-entry paths.
     *
     * @return true if mail took over the grant (the caller must NOT also pay it directly)
     */
    public static boolean sendSeasonWelcome(ServerPlayer player, int spurs) {
        if (!enabled() || !AuthConfig.SEASON_WELCOME_MAIL.get()) return false;
        MinecraftServer server = player.getServer();
        List<ItemStack> items = MailItems.parseSpecs(AuthConfig.SEASON_WELCOME_ITEMS.get());
        String key = "welcome:s" + season() + ":" + player.getUUID();
        MailStore.send(server, List.of(player.getUUID()), new MailStore.Outgoing(SYSTEM,
            "Welcome to Season " + season() + "!",
            "Welcome aboard, " + player.getGameProfile().getName() + "! Here is your starter kit and your "
                + "starting spurs.\\nLevel up by earning advancements: every level grows your land claims "
                + "and mails you a reward. Have fun!",
            MailItems.encode(items, server.registryAccess()), spurs, 0, key),
            sent -> { if (sent > 0) notifyNew(player, 1); });
        return true;
    }

    /** Reward for reaching {@code level}. Dedupe key = season + uuid + level: never paid twice. */
    public static void sendLevelReward(ServerPlayer player, int level, int claimsNow) {
        if (!enabled()) return;
        MinecraftServer server = player.getServer();
        // 🔴 NO SPURS. Owner, 2026-10-04: "spurs cannot be gained from any system reward except voting".
        // The economy is the starting spurs + voting + what players trade among themselves.
        int every = AuthConfig.LEVEL_MILESTONE_EVERY.get();
        boolean milestone = every > 0 && level % every == 0;
        List<ItemStack> items = milestone ? MailItems.parseSpecs(AuthConfig.LEVEL_MILESTONE_ITEMS.get()) : List.of();
        String body = "You reached level " + level + "!"
            + (claimsNow > 0 ? "\\nYou can now claim " + claimsNow + " chunks." : "")
            + (milestone ? "\\nMilestone level: bonus items included." : "");
        MailStore.send(server, List.of(player.getUUID()), new MailStore.Outgoing(SYSTEM,
            (milestone ? "★ " : "") + "Level " + level + " reward", body,
            MailItems.encode(items, server.registryAccess()), 0, expiry(),
            "lvl:s" + season() + ":" + player.getUUID() + ":" + level),
            sent -> { if (sent > 0) notifyNew(player, 1); });
    }

    // ── notices ──────────────────────────────────────────────────────────────

    /** Join notice: "You have N unread mail" with a clickable /mail. Works on the lobby too (count only). */
    public static void onJoin(ServerPlayer player) {
        if (!enabled()) return;
        MailStore.counts(player.getServer(), player.getUUID(), c -> {
            if (player.hasDisconnected()) return;
            if (c[0] == 0 && c[1] == 0) {
                // Owner 2026-10-07: always say something on join, so players learn the mailbox exists.
                // SMP only: the lobby cannot open mail, and the player hears it again on arrival anyway.
                if (LobbyHandoff.isLobbyRole()) return;
                player.sendSystemMessage(Component.literal("§6✉ §7No new mail. ").append(openLink()));
                return;
            }
            Component msg = Component.literal("§6✉ §fYou have §e" + c[0] + "§f unread mail"
                + (c[1] > 0 ? " §7(§a" + c[1] + " to claim§7)" : "") + ". ");
            player.sendSystemMessage(msg.copy().append(openLink()));
        });
    }

    public static void notifyNew(ServerPlayer player, int n) {
        if (player == null || player.hasDisconnected()) return;
        player.sendSystemMessage(Component.literal("§6✉ §fNew mail" + (n > 1 ? " ×" + n : "") + "! ")
            .append(openLink()));
    }

    private static Component openLink() {
        if (LobbyHandoff.isLobbyRole()) return Component.literal("§7Open it on the survival server.");
        return Component.literal("§a§n[Open /mail]").withStyle(Style.EMPTY
            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mail"))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Open your mailbox")))
            .withUnderlined(true).withColor(net.minecraft.ChatFormatting.GREEN));
    }

    /** Uuid for a name: online player first, then any profile (exact account or display name). */
    public static UUID resolve(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        var store = CoffeesAeroAuth.PROFILE_STORE;
        if (store == null) return null;
        var p = store.findByAnyName(name);
        return p == null ? null : p.getUUID();
    }
}
