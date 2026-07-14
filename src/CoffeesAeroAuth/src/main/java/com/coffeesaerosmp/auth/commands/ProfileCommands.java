package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.lobby.NameApprovalQueue;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ProfileCommands {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));

    private static final DateTimeFormatter DATETIME_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneId.of("UTC"));

    /** Online player names (real usernames) for admin tab-complete — plain names, never selectors. */
    private static final SuggestionProvider<CommandSourceStack> ONLINE_NAMES = (ctx, builder) ->
        SharedSuggestionProvider.suggest(
            ctx.getSource().getServer().getPlayerList().getPlayers().stream()
                .map(p -> p.getGameProfile().getName()),
            builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // /profile [player]
        dispatcher.register(Commands.literal("profile")
            .executes(ctx -> showProfile(ctx.getSource(), null))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(ctx -> showProfile(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
            )
        );

        // /setbio <bio>
        dispatcher.register(Commands.literal("setbio")
            .then(Commands.argument("bio", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    return setBio(player, StringArgumentType.getString(ctx, "bio"));
                })
            )
        );

        // (/skin lives in the CoffeesAeroSkins mod since auth 1.6.0 — policy comes back through
        // SkinsHook's backend: authenticated + offline-only, lifetime change cap.)

        // /discord link|unlink|status — Discord↔MC account linking (1.6.10)
        dispatcher.register(Commands.literal("discord")
            .then(Commands.literal("link")
                .executes(ctx -> discordLink(ctx.getSource().getPlayerOrException())))
            .then(Commands.literal("unlink")
                .executes(ctx -> discordUnlink(ctx.getSource().getPlayerOrException())))
            .then(Commands.literal("status")
                .executes(ctx -> discordStatus(ctx.getSource().getPlayerOrException())))
        );

        // /clan tag <tag> | /clan untag — clan tag for the player's FTB party (officer+ only)
        dispatcher.register(Commands.literal("clan")
            .then(Commands.literal("tag")
                .then(Commands.argument("tag", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        if (!requireAuth(player)) return 0;
                        String err = com.coffeesaerosmp.auth.clan.ClanTags.setTag(
                            player, StringArgumentType.getString(ctx, "tag"));
                        player.sendSystemMessage(err != null ? TextUtil.info("§c" + err)
                            : TextUtil.info("Clan tag set — your party now shows as §7[§b"
                                + StringArgumentType.getString(ctx, "tag") + "§7]§r in chat, Tab and nametags."));
                        return err == null ? 1 : 0;
                    })))
            .then(Commands.literal("untag")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (!requireAuth(player)) return 0;
                    String err = com.coffeesaerosmp.auth.clan.ClanTags.clearTag(player);
                    player.sendSystemMessage(err != null ? TextUtil.info("§c" + err)
                        : TextUtil.info("Clan tag removed."));
                    return err == null ? 1 : 0;
                }))
            // /clan color <color> — party OWNER only; everyone without a chosen color stays blue.
            .then(Commands.literal("color")
                .then(Commands.argument("color", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        com.coffeesaerosmp.auth.clan.ClanTags.COLORS.keySet().stream().sorted(), builder))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        if (!requireAuth(player)) return 0;
                        String color = StringArgumentType.getString(ctx, "color");
                        String err = com.coffeesaerosmp.auth.clan.ClanTags.setColor(player, color);
                        player.sendSystemMessage(err != null ? TextUtil.info("§c" + err)
                            : TextUtil.info("Clan tag color set to "
                                + com.coffeesaerosmp.auth.clan.ClanTags.COLORS.get(color.toLowerCase(java.util.Locale.ROOT))
                                + color + "§r."));
                        return err == null ? 1 : 0;
                    })))
        );

        // /mytrustedips — shows own trusted IPs (partially masked)
        dispatcher.register(Commands.literal("mytrustedips")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                return showTrustedIps(player);
            })
        );

        // /authmod — admin commands (op level 2+)
        dispatcher.register(Commands.literal("authmod")
            .requires(src -> src.hasPermission(2))
            .then(Commands.literal("info")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> adminInfo(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
                )
            )
            // Name-based (NOT EntityArgument) so it works for OFFLINE players too — the usual case
            // is "player forgot password and can't get in". Accepts username or display name.
            .then(Commands.literal("resetpassword")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(ONLINE_NAMES)
                    .executes(ctx -> adminResetPassword(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                )
            )
            // Full admin player card (works for offline players; also usable from the Discord
            // watchdog channel via the console bridge: type `authmod player <name>` there).
            .then(Commands.literal("player")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests(ONLINE_NAMES)
                    .executes(ctx -> adminPlayerCard(ctx.getSource(), StringArgumentType.getString(ctx, "name")))
                )
            )
            .then(Commands.literal("players")
                .executes(ctx -> adminPlayersSummary(ctx.getSource()))
            )
            .then(Commands.literal("clearips")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> adminClearIps(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
                )
            )
            .then(Commands.literal("clearban")
                .then(Commands.argument("ip", StringArgumentType.word())
                    .executes(ctx -> adminClearBan(ctx.getSource(), StringArgumentType.getString(ctx, "ip")))
                )
            )
            // ── Aggressive ban: name OR IP. A name bans the account + EVERY IP it ever used
            // (first-join, trusted, live) + each IP's whole subnet (/24 v4, /64 v6). An IP bans
            // that IP + every account that ever used it + all of THOSE accounts' other IPs.
            // Live immediately (in-memory + DB), enforced pre-auth at the join gate.
            .then(Commands.literal("ban")
                .then(Commands.argument("target", StringArgumentType.word())
                    .suggests(ONLINE_NAMES)
                    .executes(ctx -> adminBan(ctx.getSource(),
                        StringArgumentType.getString(ctx, "target"), "Banned by admin"))
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> adminBan(ctx.getSource(),
                            StringArgumentType.getString(ctx, "target"),
                            StringArgumentType.getString(ctx, "reason")))
                    )
                )
            )
            .then(Commands.literal("unban")
                .then(Commands.argument("target", StringArgumentType.word())
                    .executes(ctx -> adminUnban(ctx.getSource(), StringArgumentType.getString(ctx, "target")))
                )
            )
            .then(Commands.literal("obsidianreport")
                .executes(ctx -> obsidianReport(ctx.getSource()))
            )
            // ── Name approval queue ──────────────────────────────────────────
            .then(Commands.literal("queue")
                .executes(ctx -> adminQueue(ctx.getSource()))
            )
            .then(Commands.literal("approve")
                .then(Commands.argument("playerName", StringArgumentType.word())
                    .executes(ctx -> adminApprove(ctx.getSource(), StringArgumentType.getString(ctx, "playerName")))
                )
            )
            .then(Commands.literal("reject")
                .then(Commands.argument("playerName", StringArgumentType.word())
                    .then(Commands.argument("reason", StringArgumentType.greedyString())
                        .executes(ctx -> adminReject(
                            ctx.getSource(),
                            StringArgumentType.getString(ctx, "playerName"),
                            StringArgumentType.getString(ctx, "reason")))
                    )
                )
            )
        );
    }

    // ── Command handlers ──────────────────────────────────────────────────────

    private static int showProfile(CommandSourceStack source, ServerPlayer target) {
        try {
            ServerPlayer viewer = source.getPlayerOrException();
            if (!requireAuth(viewer)) return 0;

            ServerPlayer subject  = target != null ? target : viewer;
            PlayerProfile profile = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(subject.getUUID());
            if (profile == null) {
                source.sendFailure(Component.literal("§cProfile not found."));
                return 0;
            }

            String badge    = profile.getAccountType() == PlayerProfile.AccountType.PREMIUM
                              ? "§6✦ §eVerified" : "§7◈ §8Offline";
            String joinDate = DATE_FMT.format(Instant.ofEpochMilli(profile.joinDate));
            long   mins     = profile.totalPlaytimeSeconds / 60;

            viewer.sendSystemMessage(Component.literal(
                "\n§6§l══════ §eProfile §6§l══════\n" +
                "§7Display: §f" + profile.displayName + "\n" +
                "§7Account: " + badge + "\n" +
                "§7Bio:     §f" + (profile.bio == null || profile.bio.isBlank() ? "§8No bio set" : profile.bio) + "\n" +
                "§7Joined:  §f" + joinDate + "\n" +
                "§7Played:  §f" + mins + " minutes\n" +
                "§6§l══════════════════"
            ));
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cAn error occurred."));
            return 0;
        }
    }

    // ── Discord linking ───────────────────────────────────────────────────────

    private static int discordLink(ServerPlayer player) {
        if (!requireAuth(player)) return 0;
        String code = com.coffeesaerosmp.auth.discord.LinkManager.createCode(
            player.getUUID(), player.getGameProfile().getName());
        player.sendSystemMessage(Component.literal(
            "\n§6§l══════ §eDiscord Link §6§l══════\n" +
            "§7Your one-time code: §a§l" + code + "\n" +
            "§7In our Discord server, type §f/link " + code + "\n" +
            "§8Expires in 5 minutes. Linked pilots get tagged on their achievements!\n" +
            "§6§l══════════════════════"
        ));
        return 1;
    }

    private static int discordUnlink(ServerPlayer player) {
        if (!requireAuth(player)) return 0;
        boolean removed = com.coffeesaerosmp.auth.discord.LinkManager.unlink(player.getUUID());
        player.sendSystemMessage(TextUtil.info(removed
            ? "Discord account unlinked."
            : "No Discord account was linked."));
        return 1;
    }

    private static int discordStatus(ServerPlayer player) {
        if (!requireAuth(player)) return 0;
        PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE != null
            ? CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID()) : null;
        boolean linked = p != null && p.discordId != null && !p.discordId.isBlank();
        player.sendSystemMessage(TextUtil.info(linked
            ? "Linked to Discord ✔ — you get tagged on achievements. Use /discord unlink to remove."
            : "Not linked. Use /discord link to connect your Discord account."));
        return 1;
    }

    private static int setBio(ServerPlayer player, String bio) {
        if (!requireAuth(player)) return 0;
        if (bio.length() > 100) {
            player.sendSystemMessage(TextUtil.error("Bio must be 100 characters or less (got " + bio.length() + ")."));
            return 0;
        }
        PlayerProfile profile = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
        if (profile == null) return 0;
        profile.bio = bio;
        CoffeesAeroAuth.AUTH_MANAGER.getStore().save(profile);
        player.sendSystemMessage(TextUtil.success("Bio updated."));
        return 1;
    }

    private static int adminInfo(CommandSourceStack source, ServerPlayer target) {
        PlayerProfile p = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(target.getUUID());
        if (p == null) {
            source.sendFailure(Component.literal("§cNo profile found for " + target.getGameProfile().getName() + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
            "§6[AuthMod Admin] §7" + target.getGameProfile().getName() + "\n" +
            "§7UUID:      §f" + p.uuidStr + "\n" +
            "§7Real name: §f" + p.username + "\n" +
            "§7Display:   §f" + p.displayName + "\n" +
            "§7Type:      §f" + p.accountType + "\n" +
            "§7HasPass:   §f" + (p.passwordHash != null) + "\n" +
            "§7FirstJoin: §f" + p.firstJoinComplete
        ), false);
        return 1;
    }

    private static int adminResetPassword(CommandSourceStack source, String name) {
        PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE != null
            ? CoffeesAeroAuth.PROFILE_STORE.findByAnyName(name) : null;
        if (p == null) {
            source.sendFailure(Component.literal("§cNo profile found for '" + name + "' (username or display name)."));
            return 0;
        }
        if (p.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
            source.sendFailure(Component.literal("§cPremium accounts have no password (Mojang-verified)."));
            return 0;
        }
        p.passwordHash = null;
        p.passwordSalt = null;
        CoffeesAeroAuth.PROFILE_STORE.save(p);
        CoffeesAeroAuth.AUTH_MANAGER.invalidateSessionToken(p.getUUID());
        source.sendSuccess(() -> Component.literal(
            "§aPassword reset for §f" + p.displayName + "§a. They will need to /register on next login."
        ), true);
        // If they're connected right now, kick with instructions; offline players just re-register next join.
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(p.getUUID());
        if (online != null) {
            online.connection.disconnect(Component.literal(
                "§eYour password was reset by an admin.\n§7Reconnect and use §a/register§7 to set a new one."
            ));
        }
        return 1;
    }

    /** Full admin player card — everything the DB knows about one player, in one readable block.
     *  Plain text (no color codes) so the Discord console-bridge output stays clean. The password is a
     *  one-way salted hash — the plaintext is unrecoverable by design; only "set: yes/no" is shown. */
    private static int adminPlayerCard(CommandSourceStack source, String name) {
        PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE != null
            ? CoffeesAeroAuth.PROFILE_STORE.findByAnyName(name) : null;
        if (p == null) {
            source.sendFailure(Component.literal("No profile found for '" + name + "' (username or display name)."));
            return 0;
        }
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(p.getUUID());

        long secs = p.totalPlaytimeSeconds;
        // include the live session so the card is current while they're playing
        if (online != null && p.sessionStartEpoch > 0)
            secs += (System.currentTimeMillis() - p.sessionStartEpoch) / 1000;
        String playtime = (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";

        String ips = "(none)";
        if (CoffeesAeroAuth.WATCHDOG != null) {
            java.util.List<String> list = CoffeesAeroAuth.WATCHDOG.getTrustedIpStore().getTrustedIps(p.getUUID());
            if (!list.isEmpty()) ips = String.join(", ", list);
        }
        String nameState = p.nameApproved ? "approved"
            : p.nameApprovalPending ? ("PENDING (wants '" + p.pendingDisplayName + "')") : "not approved";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Player card: ").append(p.displayName).append(" ===\n");
        sb.append("Account    : ").append(p.accountType)
          .append("  |  password set: ").append(p.passwordHash != null ? "yes" : "no").append('\n');
        sb.append("Username   : ").append(p.username)
          .append("  |  display: ").append(p.displayName).append('\n');
        sb.append("UUID       : ").append(p.uuidStr).append('\n');
        sb.append("First join : ").append(p.joinDate > 0 ? DATETIME_FMT.format(Instant.ofEpochMilli(p.joinDate)) : "?")
          .append("  |  first IP: ").append(p.firstIp != null ? p.firstIp : "?").append('\n');
        sb.append("Playtime   : ").append(playtime)
          .append("  |  online now: ").append(online != null
              ? ("YES (" + com.coffeesaerosmp.auth.util.NetUtil.getPlayerIP(online) + ")") : "no").append('\n');
        sb.append("Name       : ").append(nameState)
          .append("  |  rejections: ").append(p.nameRejectionCount)
          .append("  |  changes used: ").append(p.nameChangesUsed).append('\n');
        sb.append("Room slot  : ").append(p.roomSlot)
          .append("  |  skin changes: ").append(p.skinChangesUsed)
          .append("  |  cape: ").append(p.capeEnabled).append('\n');
        if (p.returnDim != null)
            sb.append("Return pos : ").append(p.returnDim).append(" ")
              .append(Math.round(p.returnX)).append(", ").append(Math.round(p.returnY))
              .append(", ").append(Math.round(p.returnZ)).append('\n');
        sb.append("Trusted IPs: ").append(ips);
        if (p.bio != null && !p.bio.isBlank()) sb.append('\n').append("Bio        : ").append(p.bio);

        String card = sb.toString();
        source.sendSuccess(() -> Component.literal(card), false);
        return 1;
    }

    /** {@code authmod players} — player-base totals. Plain text so it reads clean via the
     *  Discord console bridge. */
    private static int adminPlayersSummary(CommandSourceStack source) {
        if (CoffeesAeroAuth.PROFILE_STORE == null) {
            source.sendFailure(Component.literal("Profile store unavailable."));
            return 0;
        }
        int premium = 0, offline = 0, linked = 0, named = 0;
        for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.getAll()) {
            if (p.getAccountType() == PlayerProfile.AccountType.PREMIUM) premium++;
            else offline++;
            if (p.discordId != null && !p.discordId.isBlank()) linked++;
            if (p.nameApproved) named++;
        }
        int onlinePrem = 0, onlineOff = 0;
        for (ServerPlayer sp : source.getServer().getPlayerList().getPlayers()) {
            PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE.get(sp.getUUID());
            if (p != null && p.getAccountType() == PlayerProfile.AccountType.PREMIUM) onlinePrem++;
            else onlineOff++;
        }
        int total = premium + offline;
        String out = "=== Player base: " + total + " accounts ===\n"
            + "Premium : " + premium + "  |  Offline: " + offline + "\n"
            + "Online  : " + (onlinePrem + onlineOff)
            + " (" + onlinePrem + " premium, " + onlineOff + " offline)\n"
            + "Name-approved: " + named + "  |  Discord-linked: " + linked;
        source.sendSuccess(() -> Component.literal(out), false);
        return 1;
    }

    /**
     * The {@code authmod player} card as a Discord embed (same data as the text card above) — used
     * by the admin console bridge so the watchdog channel gets a proper card instead of a text dump.
     * Returns {@code null} when no profile matches. Server thread only.
     */
    public static String playerCardEmbedJson(net.minecraft.server.MinecraftServer server, String name) {
        PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE != null
            ? CoffeesAeroAuth.PROFILE_STORE.findByAnyName(name) : null;
        if (p == null) return null;
        ServerPlayer online = server.getPlayerList().getPlayer(p.getUUID());

        long secs = p.totalPlaytimeSeconds;
        if (online != null && p.sessionStartEpoch > 0)
            secs += (System.currentTimeMillis() - p.sessionStartEpoch) / 1000;
        String playtime = (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";

        String ips = "(none)";
        if (CoffeesAeroAuth.WATCHDOG != null) {
            java.util.List<String> list = CoffeesAeroAuth.WATCHDOG.getTrustedIpStore().getTrustedIps(p.getUUID());
            if (!list.isEmpty()) ips = String.join(", ", list);
        }
        String nameState = p.nameApproved ? "✅ approved"
            : p.nameApprovalPending ? ("🕐 PENDING — wants '" + p.pendingDisplayName + "'") : "not approved";
        boolean premium = p.getAccountType() == PlayerProfile.AccountType.PREMIUM;

        com.google.gson.JsonObject embed = new com.google.gson.JsonObject();
        embed.addProperty("title", (premium ? "✦ " : "◈ ") + p.displayName);
        embed.addProperty("color", online != null ? 0x57F287 : (premium ? 0xF1C40F : 0x99AAB5));
        com.google.gson.JsonArray fields = new com.google.gson.JsonArray();
        java.util.function.BiConsumer<String, String> add = (n, v) -> {
            com.google.gson.JsonObject f = new com.google.gson.JsonObject();
            f.addProperty("name", n);
            f.addProperty("value", v == null || v.isBlank() ? "—" : v);
            f.addProperty("inline", true);
            fields.add(f);
        };
        add.accept("Account",     p.accountType + (p.passwordHash != null ? " 🔑" : ""));
        add.accept("Username",    p.username);
        add.accept("Online",      online != null
            ? "YES (" + com.coffeesaerosmp.auth.util.NetUtil.getPlayerIP(online) + ")" : "no");
        add.accept("Playtime",    playtime);
        add.accept("First join",  p.joinDate > 0 ? DATETIME_FMT.format(Instant.ofEpochMilli(p.joinDate)) : "?");
        add.accept("First IP",    p.firstIp);
        add.accept("Name",        nameState + " • " + p.nameRejectionCount + " rejections • "
                                  + p.nameChangesUsed + " changes used");
        add.accept("Room / Skin", "slot " + p.roomSlot + " • " + p.skinChangesUsed
                                  + " skin changes • cape " + (p.capeEnabled ? "on" : "off"));
        add.accept("Discord",     p.discordId != null && !p.discordId.isBlank()
                                  ? "linked (<@" + p.discordId + ">)" : "not linked");
        if (p.returnDim != null)
            add.accept("Return pos", p.returnDim + " " + Math.round(p.returnX) + ", "
                + Math.round(p.returnY) + ", " + Math.round(p.returnZ));
        com.google.gson.JsonObject ipField = new com.google.gson.JsonObject();
        ipField.addProperty("name", "Trusted IPs");
        ipField.addProperty("value", ips);
        ipField.addProperty("inline", false);
        fields.add(ipField);
        if (p.bio != null && !p.bio.isBlank()) {
            com.google.gson.JsonObject bio = new com.google.gson.JsonObject();
            bio.addProperty("name", "Bio");
            bio.addProperty("value", p.bio);
            bio.addProperty("inline", false);
            fields.add(bio);
        }
        embed.add("fields", fields);
        com.google.gson.JsonObject footer = new com.google.gson.JsonObject();
        footer.addProperty("text", "UUID " + p.uuidStr);
        embed.add("footer", footer);
        embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT
            .withZone(ZoneId.of("UTC")).format(Instant.now()));

        com.google.gson.JsonObject body = new com.google.gson.JsonObject();
        com.google.gson.JsonArray embeds = new com.google.gson.JsonArray();
        embeds.add(embed);
        body.add("embeds", embeds);
        return body.toString();
    }

    private static int showTrustedIps(ServerPlayer player) {
        if (!requireAuth(player)) return 0;
        if (CoffeesAeroAuth.WATCHDOG == null) {
            player.sendSystemMessage(TextUtil.info("Watchdog not active."));
            return 0;
        }
        java.util.List<String> ips = CoffeesAeroAuth.WATCHDOG.getTrustedIpStore().getTrustedIps(player.getUUID());
        if (ips.isEmpty()) {
            player.sendSystemMessage(TextUtil.info("No trusted IPs on record yet."));
        } else {
            StringBuilder sb = new StringBuilder("§6[AeroAuth]§r Trusted IPs:\n");
            for (String ip : ips) sb.append("  §7• §f").append(com.coffeesaerosmp.auth.util.NetUtil.maskIp(ip)).append("\n");
            player.sendSystemMessage(Component.literal(sb.toString().trim()));
        }
        return 1;
    }

    private static int adminClearIps(CommandSourceStack source, ServerPlayer target) {
        if (CoffeesAeroAuth.WATCHDOG == null) {
            source.sendFailure(Component.literal("§cWatchdog not active."));
            return 0;
        }
        CoffeesAeroAuth.WATCHDOG.getTrustedIpStore().clearIps(target.getUUID());
        source.sendSuccess(() -> Component.literal(
            "§aTrusted IPs cleared for §f" + target.getGameProfile().getName() + "§a. They will be prompted on next login from a new IP."), true);
        return 1;
    }

    private static int obsidianReport(CommandSourceStack source) {
        if (CoffeesAeroAuth.OBSIDIAN_EXPORTER == null) {
            source.sendFailure(Component.literal("§cObsidian integration is not enabled. Set obsidian.enabled=true and provide an API key in .env."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§7[Obsidian] Full vault sync started..."), false);
        CoffeesAeroAuth.OBSIDIAN_EXPORTER.syncAll(
            source.getServer(),
            CoffeesAeroAuth.AUTH_MANAGER,
            CoffeesAeroAuth.WATCHDOG
        );
        source.sendSuccess(() -> Component.literal("§a[Obsidian] Sync triggered — check your vault in a moment."), true);
        return 1;
    }

    // ── /authmod ban & unban ──────────────────────────────────────────────────

    /** Effectively permanent (100 years) — the SMP has no timed admin bans. */
    private static final long PERMANENT_BAN_MS = 100L * 365 * 24 * 3600 * 1000;

    private static boolean looksLikeIp(String s) {
        return s.indexOf(':') >= 0 || s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** All IPs this account is known by: first-join IP, trusted IPs, live session IP. */
    private static java.util.Set<String> knownIpsOf(CommandSourceStack source, PlayerProfile p) {
        java.util.Set<String> ips = new java.util.LinkedHashSet<>();
        if (p.firstIp != null && !p.firstIp.isBlank()) ips.add(p.firstIp);
        if (CoffeesAeroAuth.WATCHDOG != null)
            ips.addAll(CoffeesAeroAuth.WATCHDOG.getTrustedIpStore().getTrustedIps(p.getUUID()));
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(p.getUUID());
        if (online != null) ips.add(com.coffeesaerosmp.auth.util.NetUtil.getPlayerIP(online));
        return ips;
    }

    private static int adminBan(CommandSourceStack source, String target, String reason) {
        if (CoffeesAeroAuth.WATCHDOG == null || CoffeesAeroAuth.PROFILE_STORE == null) {
            source.sendFailure(Component.literal("§cWatchdog/profile store not active."));
            return 0;
        }
        var bans = CoffeesAeroAuth.WATCHDOG.getIpBanManager();

        java.util.Set<PlayerProfile> accounts = new java.util.LinkedHashSet<>();
        java.util.Set<String> ips = new java.util.LinkedHashSet<>();

        if (looksLikeIp(target)) {
            ips.add(target);
            // Banned from an IP ⇒ every account that ever used it is banned with it.
            for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.getAll()) {
                boolean match = target.equals(p.firstIp)
                    || CoffeesAeroAuth.WATCHDOG.getTrustedIpStore().getTrustedIps(p.getUUID()).contains(target);
                if (match) accounts.add(p);
            }
        } else {
            PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE.findByAnyName(target);
            if (p == null) {
                source.sendFailure(Component.literal(
                    "§cNo profile found for '" + target + "' (username, display name, or IP)."));
                return 0;
            }
            accounts.add(p);
        }

        // Each banned account drags in ALL of its known IPs.
        for (PlayerProfile p : accounts) ips.addAll(knownIpsOf(source, p));

        // IP layer: exact IP + whole subnet, live in memory + persisted to MySQL.
        java.util.Set<String> subnets = new java.util.LinkedHashSet<>();
        for (String ip : ips) {
            bans.ban(ip, PERMANENT_BAN_MS, reason);
            String subnet = com.coffeesaerosmp.auth.util.NetUtil.subnetOf(ip);
            if (!subnet.equals(ip)) subnets.add(subnet);
        }
        for (String subnet : subnets) bans.banSubnet(subnet, PERMANENT_BAN_MS, reason);

        // Account layer: vanilla ban list, keyed by UUID — survives username changes.
        var banList = source.getServer().getPlayerList().getBans();
        for (PlayerProfile p : accounts) {
            var gp = new com.mojang.authlib.GameProfile(
                p.getUUID(), p.username != null ? p.username : p.displayName);
            if (!banList.isBanned(gp)) {
                banList.add(new net.minecraft.server.players.UserBanListEntry(
                    gp, null, source.getTextName(), null, reason));
            }
        }

        // Kick anyone caught by the net — banned account or now-banned IP. Never kick ops
        // (a shared /24 could otherwise lock the admin out of their own server).
        int kicked = 0;
        for (ServerPlayer online : java.util.List.copyOf(source.getServer().getPlayerList().getPlayers())) {
            if (source.getServer().getPlayerList().isOp(online.getGameProfile())) continue;
            boolean uuidHit = accounts.stream().anyMatch(p -> p.getUUID().equals(online.getUUID()));
            boolean ipHit = bans.isBanned(com.coffeesaerosmp.auth.util.NetUtil.getPlayerIP(online));
            if (uuidHit || ipHit) {
                online.connection.disconnect(Component.literal("§cYou are banned from this server."));
                kicked++;
            }
        }

        String who = accounts.isEmpty() ? "(no known accounts)"
            : accounts.stream().map(p -> p.displayName).reduce((a, b) -> a + ", " + b).orElse("?");
        String summary = "Banned " + who + " — " + ips.size() + " IP(s), " + subnets.size()
            + " subnet(s), " + accounts.size() + " account(s)";
        final int kickedF = kicked;
        source.sendSuccess(() -> Component.literal(
            "§c" + summary + (kickedF > 0 ? " — kicked " + kickedF + " online" : "")
            + "§7\nIPs: §f" + String.join(", ", ips)
            + (subnets.isEmpty() ? "" : "§7\nSubnets: §f" + String.join(", ", subnets))
            + "§7\nReason: §f" + reason + "§7 — undo with §f/authmod unban"), true);

        CoffeesAeroAuth.LOGGER.warn("[AdminBan] {} | reason: {} | by {}", summary, reason, source.getTextName());
        try {
            CoffeesAeroAuth.WATCHDOG.recordAdminCommand(source.getPlayerOrException(), "authmod ban " + target);
        } catch (Exception ignored) {}
        CoffeesAeroAuth.WATCHDOG.alert(com.coffeesaerosmp.auth.watchdog.WatchdogEvent.of(
            com.coffeesaerosmp.auth.watchdog.Severity.HIGH, "Admin Ban", "Account + IP + subnet ban applied",
            "By", source.getTextName(), "Target", target, "Accounts", String.valueOf(accounts.size()),
            "IPs", String.join(", ", ips), "Reason", reason));
        return 1;
    }

    private static int adminUnban(CommandSourceStack source, String target) {
        if (CoffeesAeroAuth.WATCHDOG == null || CoffeesAeroAuth.PROFILE_STORE == null) {
            source.sendFailure(Component.literal("§cWatchdog/profile store not active."));
            return 0;
        }
        var bans = CoffeesAeroAuth.WATCHDOG.getIpBanManager();

        if (looksLikeIp(target)) {
            bans.clearBan(target); // clears the exact IP and its subnet key
            source.sendSuccess(() -> Component.literal("§aUnbanned IP §f" + target
                + "§a (and its subnet). Accounts banned by name stay banned — unban them by name."), true);
            return 1;
        }

        PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE.findByAnyName(target);
        if (p == null) {
            source.sendFailure(Component.literal("§cNo profile found for '" + target + "'."));
            return 0;
        }
        var gp = new com.mojang.authlib.GameProfile(
            p.getUUID(), p.username != null ? p.username : p.displayName);
        var banList = source.getServer().getPlayerList().getBans();
        if (banList.isBanned(gp)) banList.remove(gp);

        java.util.Set<String> ips = knownIpsOf(source, p);
        for (String ip : ips) bans.clearBan(ip); // exact + subnet each

        source.sendSuccess(() -> Component.literal("§aUnbanned §f" + p.displayName
            + "§a — account + " + ips.size() + " IP(s) + subnets cleared."), true);
        CoffeesAeroAuth.LOGGER.warn("[AdminBan] UNBAN {} ({} IPs) by {}", p.displayName, ips.size(), source.getTextName());
        return 1;
    }

    private static int adminClearBan(CommandSourceStack source, String ip) {
        if (CoffeesAeroAuth.WATCHDOG == null) {
            source.sendFailure(Component.literal("§cWatchdog not active."));
            return 0;
        }
        CoffeesAeroAuth.WATCHDOG.getIpBanManager().clearBan(ip);
        source.sendSuccess(() -> Component.literal("§aBan cleared for IP: §f" + ip), true);
        return 1;
    }

    private static int adminQueue(CommandSourceStack source) {
        if (CoffeesAeroAuth.APPROVAL_QUEUE == null) {
            source.sendFailure(Component.literal("§cApproval queue not active."));
            return 0;
        }
        java.util.Map<java.util.UUID, NameApprovalQueue.PendingEntry> pending =
            CoffeesAeroAuth.APPROVAL_QUEUE.getPending();
        if (pending.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§a[Queue] No pending name approvals."), false);
            return 1;
        }
        StringBuilder sb = new StringBuilder("§6[Queue] §ePending name approvals:\n");
        for (NameApprovalQueue.PendingEntry entry : pending.values()) {
            sb.append("  §f").append(entry.mcName())
              .append(" §7→ §a").append(entry.proposedName())
              .append("\n    §8/authmod approve ").append(entry.mcName())
              .append("  |  /authmod reject ").append(entry.mcName()).append(" <reason>\n");
        }
        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int adminApprove(CommandSourceStack source, String playerName) {
        if (CoffeesAeroAuth.APPROVAL_QUEUE == null) {
            source.sendFailure(Component.literal("§cApproval queue not active."));
            return 0;
        }
        if (CoffeesAeroAuth.WATCHDOG != null) {
            try { CoffeesAeroAuth.WATCHDOG.recordAdminCommand(source.getPlayerOrException(), "authmod approve " + playerName); }
            catch (Exception ignored) {}
        }
        boolean done = CoffeesAeroAuth.APPROVAL_QUEUE.adminApprove(playerName);
        if (!done) {
            source.sendFailure(Component.literal("§cNo pending entry found for §f" + playerName + "§c. Use /authmod queue to list."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§aApproved display name for §f" + playerName + "§a."), true);
        return 1;
    }

    private static int adminReject(CommandSourceStack source, String playerName, String reason) {
        if (CoffeesAeroAuth.APPROVAL_QUEUE == null) {
            source.sendFailure(Component.literal("§cApproval queue not active."));
            return 0;
        }
        if (CoffeesAeroAuth.WATCHDOG != null) {
            try { CoffeesAeroAuth.WATCHDOG.recordAdminCommand(source.getPlayerOrException(), "authmod reject " + playerName); }
            catch (Exception ignored) {}
        }
        boolean done = CoffeesAeroAuth.APPROVAL_QUEUE.adminReject(playerName, reason);
        if (!done) {
            source.sendFailure(Component.literal("§cNo pending entry found for §f" + playerName + "§c. Use /authmod queue to list."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§aRejected name for §f" + playerName + "§a. Reason: §e" + reason), true);
        return 1;
    }

    private static boolean requireAuth(ServerPlayer player) {
        if (CoffeesAeroAuth.AUTH_MANAGER == null || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) {
            player.sendSystemMessage(TextUtil.error("Authenticate first."));
            return false;
        }
        return true;
    }
}
