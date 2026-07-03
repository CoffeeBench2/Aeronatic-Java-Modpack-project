package com.coffeesaerosmp.skins.server;

import com.coffeesaerosmp.skins.api.AeroSkinsApi;
import com.coffeesaerosmp.skins.api.SkinBackend;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /skin <java_username>} | {@code /skin reset}. Policy (who may use it, lifetime cap counter)
 * comes from the installed {@link SkinBackend} — on Coffees Aero SMP that's CoffeesAeroAuth, which
 * limits /skin to authenticated OFFLINE players (premium players auto-wear their real skin).
 */
public final class SkinCommands {

    private SkinCommands() {}

    /** Lifetime cap on /skin <name> uses per player ("reset" is free and doesn't refund). */
    public static final int MAX_SKIN_CHANGES = 2;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("skin")
            .then(Commands.literal("reset")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (denied(player)) return 0;
                    SkinService.reset(player);
                    player.sendSystemMessage(success("Skin reset. Premium players get their real skin back on next join."));
                    return 1;
                })
            )
            .then(Commands.argument("username", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    if (denied(player)) return 0;
                    SkinBackend backend = AeroSkinsApi.backend();
                    // Lifetime cap: checked here + consumed only on a SUCCESSFUL apply.
                    if (backend.skinChangesUsed(player.getUUID()) >= MAX_SKIN_CHANGES) {
                        player.sendSystemMessage(error(
                            "You've used all " + MAX_SKIN_CHANGES + " of your skin changes."));
                        return 0;
                    }
                    String name = StringArgumentType.getString(ctx, "username");
                    player.sendSystemMessage(info("Fetching skin for '" + name + "'…"));
                    SkinService.applyByName(player, name, result -> {
                        if (result == null) {
                            player.sendSystemMessage(error("No Java account named '" + name + "' found."));
                            return;
                        }
                        backend.addSkinChangeUsed(player.getUUID());
                        int left = MAX_SKIN_CHANGES - backend.skinChangesUsed(player.getUUID());
                        player.sendSystemMessage(success(
                            "Skin applied from '" + result + "'. §7(" + left + " of " + MAX_SKIN_CHANGES
                            + " skin change" + (left == 1 ? "" : "s") + " left.)"));
                    });
                    return 1;
                })
            )
        );
    }

    private static boolean denied(ServerPlayer player) {
        String reason = AeroSkinsApi.backend().skinCommandDenyReason(player);
        if (reason == null) return false;
        player.sendSystemMessage(error(reason));
        return true;
    }

    private static Component success(String msg) { return Component.literal("§6[AeroSkins]§r §a" + msg); }
    private static Component error(String msg)   { return Component.literal("§6[AeroSkins]§r §c" + msg); }
    private static Component info(String msg)    { return Component.literal("§6[AeroSkins]§r §7" + msg); }
}
