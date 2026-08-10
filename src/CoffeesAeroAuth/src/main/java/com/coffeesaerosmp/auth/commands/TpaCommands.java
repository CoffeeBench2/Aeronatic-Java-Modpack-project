package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Our own /tpa, /tpaccept, /tpdeny — replaces FTB Essentials' built-in versions (disabled via the
 * shipped ftbessentials.snbt override), which fixes two live bugs: (1) FTBE's target argument accepts
 * raw entity selectors, so tab-complete offered "@a"/"@p" alongside player names; (2) its Accept/Deny
 * chat buttons render as raw translation keys in our setup instead of readable text. Ours suggests
 * ONLY currently-online player names (plain string argument, no selector grammar exists) and builds
 * every message with {@link Component#literal}, so there is no translation-key dependency at all.
 *
 * One pending incoming request per target (a newer request overwrites an older unanswered one);
 * expires after {@link AuthConfig#TPA_TIMEOUT_SECONDS}. Expiry is checked lazily at accept/deny/next-
 * request time — a stale entry for a player who disconnects just sits harmlessly until overwritten.
 */
public final class TpaCommands {

    private record TpaRequest(UUID senderUuid, String senderName, long sentAtMs) {}

    private static final Map<UUID, TpaRequest> pending = new ConcurrentHashMap<>();

    private TpaCommands() {}

    /** Suggests online player names only — never selectors, since this isn't an EntityArgument. */
    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS = (ctx, builder) -> {
        ServerPlayer self = ctx.getSource().getPlayer();
        var names = ctx.getSource().getServer().getPlayerList().getPlayers().stream()
            .map(p -> p.getGameProfile().getName())
            .filter(n -> self == null || !n.equalsIgnoreCase(self.getGameProfile().getName()));
        return SharedSuggestionProvider.suggest(names, builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tpa")
            .then(Commands.argument("player", StringArgumentType.word())
                .suggests(ONLINE_PLAYERS)
                .executes(ctx -> {
                    ServerPlayer sender = ctx.getSource().getPlayerOrException();
                    handleRequest(sender, StringArgumentType.getString(ctx, "player"));
                    return 1;
                })
            )
        );
        dispatcher.register(Commands.literal("tpaccept").executes(ctx -> {
            handleResponse(ctx.getSource().getPlayerOrException(), true);
            return 1;
        }));
        dispatcher.register(Commands.literal("tpdeny").executes(ctx -> {
            handleResponse(ctx.getSource().getPlayerOrException(), false);
            return 1;
        }));
    }

    private static void handleRequest(ServerPlayer sender, String targetName) {
        if (!requireAuthed(sender)) return;

        ServerPlayer target = sender.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            msg(sender, "§cPlayer §f" + targetName + " §cisn't online.");
            return;
        }
        if (target == sender) {
            msg(sender, "§cYou can't send a teleport request to yourself.");
            return;
        }
        if (!requireAuthed(target)) {
            msg(sender, "§c" + target.getGameProfile().getName() + " isn't in the world yet.");
            return;
        }

        pending.put(target.getUUID(), new TpaRequest(sender.getUUID(), sender.getGameProfile().getName(),
            System.currentTimeMillis()));

        msg(sender, "§7Teleport request sent to §f" + target.getGameProfile().getName() + "§7.");

        int timeout = AuthConfig.TPA_TIMEOUT_SECONDS.get();
        MutableComponent line = Component.literal(TextUtil.PREFIX + "§f" + sender.getGameProfile().getName()
            + " §7wants to teleport to you. §8(expires in " + timeout + "s)");
        target.sendSystemMessage(line);
        target.sendSystemMessage(button("§a[Accept]", "/tpaccept")
            .append(Component.literal("  "))
            .append(button("§c[Deny]", "/tpdeny")));
    }

    private static void handleResponse(ServerPlayer target, boolean accept) {
        TpaRequest req = pending.get(target.getUUID());
        if (req == null) {
            msg(target, "§7You have no pending teleport request.");
            return;
        }
        long ageMs = System.currentTimeMillis() - req.sentAtMs();
        if (ageMs > AuthConfig.TPA_TIMEOUT_SECONDS.get() * 1000L) {
            pending.remove(target.getUUID());
            msg(target, "§7That teleport request expired.");
            return;
        }
        pending.remove(target.getUUID());

        ServerPlayer sender = target.getServer().getPlayerList().getPlayer(req.senderUuid());
        if (sender == null) {
            msg(target, "§7" + req.senderName() + " is no longer online.");
            return;
        }
        if (!accept) {
            msg(target, "§7Teleport request denied.");
            msg(sender, "§c" + target.getGameProfile().getName() + " denied your teleport request.");
            return;
        }
        sender.teleportTo((net.minecraft.server.level.ServerLevel) target.level(),
            target.getX(), target.getY(), target.getZ(),
            java.util.Set.of(), target.getYRot(), target.getXRot());
        msg(sender, "§aTeleported to §f" + target.getGameProfile().getName() + "§a.");
        msg(target, "§a" + req.senderName() + " teleported to you.");
    }

    private static boolean requireAuthed(ServerPlayer player) {
        if (CoffeesAeroAuth.AUTH_MANAGER != null && CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) {
            return true;
        }
        msg(player, "§cLog in first.");
        return false;
    }

    private static MutableComponent button(String text, String command) {
        return Component.literal(text).setStyle(Style.EMPTY.withClickEvent(
            new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    private static void msg(ServerPlayer player, String text) {
        TextUtil.msg(player, text);
    }
}
