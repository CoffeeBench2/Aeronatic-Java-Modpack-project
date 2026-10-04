package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.mail.MailGui;
import com.coffeesaerosmp.auth.mail.MailItems;
import com.coffeesaerosmp.auth.mail.MailService;
import com.coffeesaerosmp.auth.mail.MailStore;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /mail} — open your mailbox. Staff: {@code /mail admin …} to post rewards.
 *
 * <pre>
 *   /mail admin send       &lt;player&gt; &lt;spurs&gt; &lt;subject | body&gt;   spurs (0 allowed) + a message
 *   /mail admin sendall    &lt;spurs&gt; &lt;subject | body&gt;            every profile on record
 *   /mail admin sendonline &lt;spurs&gt; &lt;subject | body&gt;            everyone online now
 *   /mail admin item       &lt;player&gt; &lt;subject | body&gt;           a COPY of the item in your main hand
 *   /mail admin itemall    &lt;subject | body&gt;                    the same, to every profile
 *   player = online or offline, account or display name
 * </pre>
 * {@code |} splits subject from body; {@code \n} in the body starts a new line.
 *
 * <p>Players cannot mail each other in this version: player-to-player parcels are a trading and duping
 * surface of their own, and the brief was system/staff mail.
 */
public final class MailCommands {

    private MailCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("mail")
            .executes(MailCommands::open)
            .then(Commands.literal("admin")
                .requires(src -> src.hasPermission(3))
                .then(Commands.literal("send")
                    .then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("spurs", IntegerArgumentType.integer(0, 1_000_000))
                            .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> send(ctx, name(ctx), spurs(ctx), false))))))
                .then(Commands.literal("sendall")
                    .then(Commands.argument("spurs", IntegerArgumentType.integer(0, 1_000_000))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> send(ctx, ALL, spurs(ctx), false)))))
                .then(Commands.literal("sendonline")
                    .then(Commands.argument("spurs", IntegerArgumentType.integer(0, 1_000_000))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> send(ctx, ONLINE, spurs(ctx), false)))))
                .then(Commands.literal("item")
                    .then(Commands.argument("target", StringArgumentType.word())
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                            .executes(ctx -> send(ctx, name(ctx), 0, true)))))
                .then(Commands.literal("itemall")
                    .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> send(ctx, ALL, 0, true))))));
    }

    /** Sentinels. Not valid Minecraft names (those are [A-Za-z0-9_]), so they cannot shadow a player. */
    private static final String ALL = "<all>", ONLINE = "<online>";

    /** Broadcast confirmation: sender → fingerprint of the previewed command, and when it was previewed. */
    private static final java.util.Map<String, Long> PENDING_BROADCAST = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> ARMED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    private static String name(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "target");
    }

    private static int spurs(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "spurs");
    }

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

    private static int send(CommandContext<CommandSourceStack> ctx, String target, int spurs, boolean withHeldItem) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        if (!MailService.enabled()) {
            src.sendFailure(Component.literal("Mail is not enabled (mailEnabled=false, or MySQL is down)."));
            return 0;
        }
        String text = StringArgumentType.getString(ctx, "text");
        String subject = text, body = "";
        int bar = text.indexOf('|');
        if (bar >= 0) {
            subject = text.substring(0, bar).trim();
            body = text.substring(bar + 1).trim();
        }
        if (subject.isBlank()) {
            src.sendFailure(Component.literal("Give the mail a subject."));
            return 0;
        }

        String itemsSnbt = null;
        if (withHeldItem) {
            ServerPlayer self = src.getPlayer();
            ItemStack held = self == null ? ItemStack.EMPTY : self.getMainHandItem();
            if (held.isEmpty()) {
                src.sendFailure(Component.literal("Hold the item to attach in your main hand."));
                return 0;
            }
            itemsSnbt = MailItems.encode(List.of(held.copy()), server.registryAccess());
        }
        if (spurs == 0 && itemsSnbt == null && body.isBlank() && subject.length() < 3) {
            src.sendFailure(Component.literal("Nothing to send."));
            return 0;
        }

        List<UUID> to = new ArrayList<>();
        switch (target) {
            case ONLINE -> server.getPlayerList().getPlayers().forEach(p -> to.add(p.getUUID()));
            case ALL -> {
                if (CoffeesAeroAuth.PROFILE_STORE != null) {
                    for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.getAll()) {
                        if (p.getUUID() != null) to.add(p.getUUID());
                    }
                }
            }
            default -> {
                UUID u = MailService.resolve(server, target);
                if (u != null) to.add(u);
            }
        }
        if (to.isEmpty()) {
            src.sendFailure(Component.literal("No recipients for '" + target + "'."));
            return 0;
        }

        String sender = src.getTextName();
        // Broadcasts are confirmed, not deduped: the first run only previews, the IDENTICAL command again
        // within 30s sends. (A same-second dedupe key was tried first and failed in the boot test — two
        // presses one second apart posted 846 mails to 423 players.)
        if (to.size() > 1) {
            String fingerprint = sender + "|" + target + "|" + spurs + "|" + withHeldItem + "|" + text;
            Long armed = PENDING_BROADCAST.get(sender);
            long now = System.currentTimeMillis();
            if (armed == null || armed != (long) fingerprint.hashCode()
                    || now - ARMED_AT.getOrDefault(sender, 0L) > 30_000) {
                PENDING_BROADCAST.put(sender, (long) fingerprint.hashCode());
                ARMED_AT.put(sender, now);
                src.sendSuccess(() -> Component.literal("§e✉ This will mail §f" + to.size() + "§e players"
                    + (spurs > 0 ? " §f" + spurs + "§e spurs each (§f" + (long) spurs * to.size() + "§e total)" : "")
                    + ". §7Run the same command again within 30s to send."), false);
                return 1;
            }
            PENDING_BROADCAST.remove(sender);
            ARMED_AT.remove(sender);
        }
        MailStore.send(server, to, new MailStore.Outgoing(sender, subject, body, itemsSnbt, spurs,
            MailService.expiry(), null), sent -> {
                src.sendSuccess(() -> Component.literal("§a✉ Sent to §f" + sent + "§a of " + to.size() + " recipient(s)."), true);
                CoffeesAeroAuth.LOGGER.info("[Mail] {} sent '{}' (+{} spurs{}) to {} recipient(s) [{}].",
                    sender, MailStoreSubject.of(text), spurs, withHeldItem ? ", item" : "", sent, target);
                for (UUID u : to) MailService.notifyNew(server.getPlayerList().getPlayer(u), 1);
            });
        return 1;
    }

    /** Log-safe subject (the body can be long). */
    private static final class MailStoreSubject {
        static String of(String text) {
            int bar = text.indexOf('|');
            String s = bar >= 0 ? text.substring(0, bar).trim() : text;
            return s.length() > 64 ? s.substring(0, 64) : s;
        }
    }
}
