package com.coffeesaerosmp.auth.auth;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import com.coffeesaerosmp.auth.util.Sounds;

/**
 * The ONE owner of every cosmetic welcome surface (2026-07-18 rework — user report: the full
 * welcome show replayed on EVERY join, including the auto-reconnects after kicks, "very annoying").
 *
 * <p>Rules: the first-ever join sequence and name-approval banner are once-in-a-lifetime events and
 * always show. Everything else cosmetic — the full-screen title, the verified/welcome-back chat
 * line, the /skin tip — shows at most once per {@code welcomeIntervalHours} (default 10; 0 = every
 * join, the old behaviour). The stamp is PERSISTED to {@code welcome_shown.json} (same pattern as
 * rtp_cooldowns.json: loaded on server start, written through AsyncIo), so restarts, relogs, and
 * freeze-kick reconnect storms don't reset it — that was the original bug: nothing remembered that
 * a player had already been greeted. Functional feedback (login prompts, "session resumed",
 * "/spawn to enter", security notices) is NOT routed through this class and never throttled,
 * except a single minimal line kept for offline manual logins so /login always visibly succeeds.</p>
 */
public final class WelcomeMessages {

    private static final Map<UUID, Long> lastShown = new ConcurrentHashMap<>();
    private static volatile Path saveFile;

    private WelcomeMessages() {}

    // ── Persistence (mirror of RtpCommand's cooldown store) ───────────────────

    public static void initialize(Path dataDir) {
        saveFile = dataDir.resolve("welcome_shown.json");
        lastShown.clear();
        if (!Files.exists(saveFile)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(saveFile)).getAsJsonObject();
            o.entrySet().forEach(e -> lastShown.put(UUID.fromString(e.getKey()), e.getValue().getAsLong()));
            CoffeesAeroAuth.LOGGER.info("[Welcome] Loaded {} greeting stamps.", lastShown.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Welcome] welcome_shown.json load failed: {}", e.getMessage());
        }
    }

    private static void save() {
        Path f = saveFile;
        if (f == null) return;
        JsonObject o = new JsonObject();
        lastShown.forEach((id, t) -> o.addProperty(id.toString(), t));
        String json = o.toString();
        AsyncIo.submit(() -> {
            try { Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[Welcome] stamp save failed: {}", e.getMessage()); }
        });
    }

    private static boolean isDue(UUID uuid) {
        long intervalMs = AuthConfig.WELCOME_INTERVAL_HOURS.get() * 3_600_000L;
        if (intervalMs <= 0) return true;                       // 0 = old behaviour, every join
        return System.currentTimeMillis() - lastShown.getOrDefault(uuid, 0L) >= intervalMs;
    }

    private static void stamp(UUID uuid) {
        lastShown.put(uuid, System.currentTimeMillis());
        save();
    }

    // ── The single entry point (called from AuthManager.onAuthenticated) ─────

    /** Auth just completed (premium auto-auth, session resume, or password login). */
    public static void onAuthComplete(ServerPlayer player, PlayerProfile profile, boolean firstJoin) {
        String serverName  = AuthConfig.SERVER_DISPLAY_NAME.get();
        String displayName = profile.displayName != null ? profile.displayName : profile.username;
        boolean premium    = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM;

        if (firstJoin) {                                        // once in a lifetime — never throttled
            firstJoinSequence(player, serverName);
            broadcastNewArrival(player, displayName);
            stamp(player.getUUID());
            return;
        }
        if (isDue(player.getUUID())) {                          // the full (occasional) greeting
            chat(player, premium
                ? TextUtil.PREFIX + "§a✦ Verified — welcome, " + displayName + "§a!"
                : TextUtil.PREFIX + "§aLogged in. Welcome back, §f" + displayName + "§a!");
            welcomeTitle(player, displayName, serverName);
            skinTip(player, profile);
            stamp(player.getUUID());
            return;
        }
        // Throttled: premium auto-auth stays fully silent (the player typed nothing and needs no
        // feedback); a manual offline /login keeps ONE minimal confirmation line.
        if (!premium) {
            chat(player, TextUtil.PREFIX + "§aLogged in.");
        }
    }

    /** First world entry via /spawn right after the startup bonus — part of the once-only flow. */
    public static void firstSpawnBanner(ServerPlayer player) {
        String serverName = AuthConfig.SERVER_DISPLAY_NAME.get();
        chat(player, TextUtil.PREFIX + "§a✈ Welcome to §6§l" + serverName + "§a!");
    }

    // ── The surfaces (moved verbatim from AuthManager) ────────────────────────

    private static void firstJoinSequence(ServerPlayer player, String serverName) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(20, 80, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§6§l" + serverName)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§eYour adventure begins now ✈")));
        chat(player, "");
        chat(player, "§6§l╔═══════════════════════════╗");
        chat(player, "§6§l║  §e✈ Welcome aboard, pilot!  §6§l║");
        chat(player, "§6§l║  §7The #1 Create: Aeronautics  §6§l║");
        chat(player, "§6§l║  §7experience in Asia.         §6§l║");
        chat(player, "§6§l╚═══════════════════════════╝");
        chat(player, "");
        chat(player, "§7Getting started:");
        chat(player, "  §6• §f/spawn §7— back to the world spawn");
        chat(player, "  §6• §f/daily §7— your streak reward, claimable now");
        chat(player, "  §6• §f/profile §7— your hours, name and stats");
        chat(player, "  §6• §f/rtp §7— drop somewhere wild and start building");

        // Clickable invite. A raw URL in chat is not clickable in vanilla, so the link has to carry
        // a ClickEvent — otherwise new players have to retype it by hand and simply don't.
        String invite = AuthConfig.DISCORD_INVITE_URL.get();
        if (invite != null && !invite.isBlank()) {
            chat(player, "");
            player.sendSystemMessage(
                Component.literal("§9§l[Discord] §r§7Join the community — ")
                    .append(Component.literal("§b§n" + invite)
                        .withStyle(s -> s
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, invite))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7Click to open the Discord invite"))))));
        }
        chat(player, "");
        Sounds.reward(player);
    }

    /**
     * Tells everyone online that a brand-new player has arrived. First join only — a returning
     * login must never trigger this, or the "new player" signal stops meaning anything.
     */
    public static void broadcastNewArrival(ServerPlayer player, String displayName) {
        if (!AuthConfig.BROADCAST_NEW_PLAYERS.get()) return;
        var server = player.getServer();
        if (server == null) return;
        server.getPlayerList().broadcastSystemMessage(Component.literal(
            TextUtil.PREFIX + "§6✦ §e" + displayName
            + " §6just joined for the first time — welcome them aboard! o7"), false);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p != player) Sounds.notify(p);
        }
    }

    private static void welcomeTitle(ServerPlayer player, String displayName, String serverName) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§6§l" + serverName)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§eWelcome back, §f" + displayName + "§e!")));
    }

    /** Tells an offline player how many /skin uses they have left (silent once exhausted).
     *  Public: the once-only name-approval flow (completeApproval) also sends it, un-throttled. */
    public static void skinTip(ServerPlayer player, PlayerProfile profile) {
        if (profile.getAccountType() != PlayerProfile.AccountType.OFFLINE) return;
        // Compile-time constant from CoffeesAeroSkins — javac inlines the value, so this is
        // safe even though the skins jar is compileOnly.
        int max  = com.coffeesaerosmp.skins.server.SkinCommands.MAX_SKIN_CHANGES;
        int left = max - profile.skinChangesUsed;
        if (left <= 0) return;
        chat(player, TextUtil.PREFIX + "§b✦ Skins: §7use §a/skin <java_username>§7 to wear any Java account's skin — §e"
            + left + " of " + max + "§7 change" + (left == 1 ? "" : "s") + " available (choose wisely!).");
    }

    private static void chat(ServerPlayer player, String msg) {
        player.sendSystemMessage(Component.literal(msg));
    }
}
