package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class ProfileCommands {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("UTC"));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // /profile [player]
        dispatcher.register(Commands.literal("profile")
            .executes(ctx -> showProfile(ctx.getSource(), null))
            .then(Commands.argument("player", EntityArgument.player())
                .executes(ctx -> showProfile(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
            )
        );

        // /setdisplayname <name>
        dispatcher.register(Commands.literal("setdisplayname")
            .then(Commands.argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    return setDisplayName(player, StringArgumentType.getString(ctx, "name"));
                })
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
            .then(Commands.literal("resetpassword")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> adminResetPassword(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
                )
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
            .then(Commands.literal("obsidianreport")
                .executes(ctx -> obsidianReport(ctx.getSource()))
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

    private static int setDisplayName(ServerPlayer player, String name) {
        if (!requireAuth(player)) return 0;
        PlayerProfile profile = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
        if (profile == null) return 0;

        String error = CoffeesAeroAuth.AUTH_MANAGER.getDisplayNames().trySetDisplayName(profile, name);
        if (error != null) {
            player.sendSystemMessage(TextUtil.error(error));
            return 0;
        }
        player.sendSystemMessage(TextUtil.success("Display name set to §f" + name + "§a."));
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
            "§7Display:   §f" + p.displayName + "\n" +
            "§7Type:      §f" + p.accountType + "\n" +
            "§7HasPass:   §f" + (p.passwordHash != null) + "\n" +
            "§7FirstJoin: §f" + p.firstJoinComplete
        ), false);
        return 1;
    }

    private static int adminResetPassword(CommandSourceStack source, ServerPlayer target) {
        PlayerProfile p = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(target.getUUID());
        if (p == null) {
            source.sendFailure(Component.literal("§cNo profile for that player."));
            return 0;
        }
        if (p.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
            source.sendFailure(Component.literal("§cPremium accounts have no password."));
            return 0;
        }
        p.passwordHash = null;
        p.passwordSalt = null;
        CoffeesAeroAuth.AUTH_MANAGER.getStore().save(p);
        CoffeesAeroAuth.AUTH_MANAGER.invalidateSessionToken(target.getUUID());
        source.sendSuccess(() -> Component.literal(
            "§aPassword reset for §f" + p.displayName + "§a. They will need to /register on next login."
        ), true);
        target.connection.disconnect(Component.literal(
            "§eYour password was reset by an admin.\n§7Reconnect and use §a/register§7 to set a new one."
        ));
        return 1;
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

    private static int adminClearBan(CommandSourceStack source, String ip) {
        if (CoffeesAeroAuth.WATCHDOG == null) {
            source.sendFailure(Component.literal("§cWatchdog not active."));
            return 0;
        }
        CoffeesAeroAuth.WATCHDOG.getIpBanManager().clearBan(ip);
        source.sendSuccess(() -> Component.literal("§aBan cleared for IP: §f" + ip), true);
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
