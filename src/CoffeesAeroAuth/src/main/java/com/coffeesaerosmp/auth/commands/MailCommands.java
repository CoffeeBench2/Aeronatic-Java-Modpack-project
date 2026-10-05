package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.mail.MailCompose;
import com.coffeesaerosmp.auth.mail.MailGui;
import com.coffeesaerosmp.auth.mail.MailRules;
import com.coffeesaerosmp.auth.mail.MailService;
import com.coffeesaerosmp.auth.mail.MailStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /mail} — the mailbox, and writing letters.
 *
 * <pre>
 *   /mail                          open your mailbox
 *   /mail help                     how mail works
 *   /mail send &lt;player&gt;            write a letter (+ optional parcel) to one player
 *   /mail admin send &lt;player&gt;      staff: same, no daily limit, items COPIED
 *   /mail admin sendall            staff: every profile on record (confirm click)
 *   /mail admin sendonline         staff: everyone online now (confirm click)
 * </pre>
 * The letter itself is typed in chat after the command (see {@link MailCompose}), so there is no
 * {@code subject | body} syntax to get wrong. That syntax and {@code item}/{@code itemall} are gone
 * as of 1.13.9: an attachment is now whatever you drop in the parcel window.
 */
public final class MailCommands {

    private MailCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("mail")
            .executes(MailCommands::open)
            .then(Commands.literal("help").executes(MailCommands::help))
            .then(Commands.literal("send")
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                    .executes(ctx -> write(ctx, StringArgumentType.getString(ctx, "player"), false))))
            .then(Commands.literal("admin")
                .requires(src -> src.hasPermission(3))
                .then(Commands.literal("send")
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b))
                        .executes(ctx -> write(ctx, StringArgumentType.getString(ctx, "player"), true))))
                .then(Commands.literal("sendall").executes(ctx -> write(ctx, ALL, true)))
                .then(Commands.literal("sendonline").executes(ctx -> write(ctx, ONLINE, true)))));
    }

    /** Sentinels. Not valid Minecraft names (those are [A-Za-z0-9_]), so they cannot shadow a player. */
    private static final String ALL = "<all>", ONLINE = "<online>";

    private static int open(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Players only."));
            return 0;
        }
        if (!MailService.canOpen()) {
            ctx.getSource().sendFailure(Component.literal(MailService.enabled()
                ? "Open your mailbox on the survival server." : "Mail is not enabled on this server."));
            return 0;
        }
        MailGui.open(player);
        return 1;
    }

    private static int write(CommandContext<CommandSourceStack> ctx, String target, boolean staff) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Players only — the letter is typed in chat."));
            return 0;
        }
        if (!MailService.canOpen()) {
            src.sendFailure(Component.literal(MailService.enabled()
                ? "Send mail from the survival server." : "Mail is not enabled on this server."));
            return 0;
        }
        MinecraftServer server = src.getServer();
        List<UUID> to = new ArrayList<>();
        String label;
        switch (target) {
            case ONLINE -> {
                server.getPlayerList().getPlayers().forEach(p -> to.add(p.getUUID()));
                label = "everyone online";
            }
            case ALL -> {
                if (CoffeesAeroAuth.PROFILE_STORE != null) {
                    for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.getAll()) {
                        if (p.getUUID() != null) to.add(p.getUUID());
                    }
                }
                label = "every player";
            }
            default -> {
                UUID u = MailService.resolve(server, target);
                if (u == null) {
                    src.sendFailure(Component.literal("✉ Nobody called '" + target + "' has played here yet."));
                    return 0;
                }
                if (u.equals(player.getUUID())) {
                    src.sendFailure(Component.literal("✉ You can't mail yourself — try a friend!"));
                    return 0;
                }
                to.add(u);
                label = target;
            }
        }
        if (to.isEmpty()) {
            src.sendFailure(Component.literal("✉ Nobody to send to."));
            return 0;
        }
        if (staff) {
            MailCompose.begin(player, to, label, true);
            return 1;
        }
        // Players: refuse up front when the day's letters are used, rather than after they've written one.
        MailStore.countSentSince(server, player.getUUID(), System.currentTimeMillis() - 86_400_000L, n -> {
            if (player.hasDisconnected()) return;
            if (n < 0) {
                player.sendSystemMessage(Component.literal("§c✉ The Mail Office is closed right now. Try again soon."));
            } else if (MailRules.limitReached(n, MailRules.DAILY_SENDS)) {
                player.sendSystemMessage(Component.literal("§c✉ You've sent " + MailRules.DAILY_SENDS
                    + " letters in the last 24 hours. Try again later!"));
            } else {
                MailCompose.begin(player, to, label, false);
            }
        });
        return 1;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("§6✉ ──────────── §f§lMail §6────────────"));
        lines.add(Component.literal("§7Letters and parcels between players, plus rewards from the server."));
        lines.add(Component.empty());
        lines.add(cmd("/mail", "§fOpen your mailbox", "/mail", true));
        lines.add(Component.literal("   §7Click a letter to read it · §a✔ Claim§7 takes the items."));
        lines.add(cmd("/mail send <player>", "§fWrite a letter", "/mail send ", false));
        lines.add(Component.literal("   §71. Type your letter in chat (up to " + MailRules.LETTER_MAX + " characters)."));
        lines.add(Component.literal("   §72. Drop up to " + MailRules.PARCEL_SLOTS + " stacks in the parcel if you like."));
        lines.add(Component.literal("   §73. Press §a✉ Send§7. Type §fcancel§7 any time to stop."));
        lines.add(Component.empty());
        lines.add(Component.literal("§eGood to know"));
        lines.add(Component.literal(" §8• §cCoins can't be mailed§7 — spurs and all Numismatics items change"));
        lines.add(Component.literal("   §7hands in the world only: meet up, or use a shop or vendor."));
        lines.add(Component.literal(" §8• §7" + MailRules.DAILY_SENDS + " letters a day. The chat filter applies to letters too."));
        lines.add(Component.literal(" §8• §7Parcels nobody collects come back to the sender."));
        lines.add(Component.literal(" §8• §7Unclaimed parcels can't be deleted, so nothing is lost by accident."));
        if (src.hasPermission(3)) {
            lines.add(Component.empty());
            lines.add(Component.literal("§cStaff: §f/mail admin send <player> §7· §f/mail admin sendall §7· §f/mail admin sendonline"));
            lines.add(Component.literal("   §7No daily limit; parcel items are copied to every recipient."));
        }
        for (Component l : lines) src.sendSystemMessage(l);
        return 1;
    }

    private static MutableComponent cmd(String shown, String what, String insert, boolean run) {
        return Component.literal(" §a" + shown + " §8— ").append(Component.literal(what)).withStyle(Style.EMPTY
            .withClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, insert))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click"))));
    }
}
