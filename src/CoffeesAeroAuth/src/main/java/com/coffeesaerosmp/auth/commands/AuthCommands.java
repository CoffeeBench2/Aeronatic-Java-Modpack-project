package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class AuthCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        // /login <password>
        dispatcher.register(Commands.literal("login")
            .then(Commands.argument("password", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    CoffeesAeroAuth.AUTH_MANAGER.handleLogin(
                        player, StringArgumentType.getString(ctx, "password"));
                    return 1;
                })
            )
        );

        // /register <password> <confirmPassword>
        dispatcher.register(Commands.literal("register")
            .then(Commands.argument("password", StringArgumentType.word())
                .then(Commands.argument("confirmPassword", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        CoffeesAeroAuth.AUTH_MANAGER.handleRegister(
                            player,
                            StringArgumentType.getString(ctx, "password"),
                            StringArgumentType.getString(ctx, "confirmPassword")
                        );
                        return 1;
                    })
                )
            )
        );

        // /changepassword <oldPassword> <newPassword>
        dispatcher.register(Commands.literal("changepassword")
            .then(Commands.argument("oldPassword", StringArgumentType.word())
                .then(Commands.argument("newPassword", StringArgumentType.word())
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        CoffeesAeroAuth.AUTH_MANAGER.handleChangePassword(
                            player,
                            StringArgumentType.getString(ctx, "oldPassword"),
                            StringArgumentType.getString(ctx, "newPassword")
                        );
                        return 1;
                    })
                )
            )
        );

        // /logout  — invalidates session token and disconnects; must /login on next join
        dispatcher.register(Commands.literal("logout")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                CoffeesAeroAuth.AUTH_MANAGER.invalidateSessionToken(player.getUUID());
                player.connection.disconnect(Component.literal(
                    "§7Logged out. Reconnect and use §a/login§7 to play again."
                ));
                return 1;
            })
        );

        // /spawn — lobby: exit room into main world (no cooldown). Main world: teleport to the
        // overworld world spawn, once per hour (ops exempt; CombatGuard blocks it while tagged).
        dispatcher.register(Commands.literal("spawn")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                CoffeesAeroAuth.AUTH_MANAGER.handleSpawn(player);
                return 1;
            })
        );

        // /lobby — admin only: drop into the lobby preview room to inspect/iterate the design
        dispatcher.register(Commands.literal("lobby")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                if (CoffeesAeroAuth.ROOM_MANAGER == null) {
                    player.sendSystemMessage(Component.literal("§cLobby system not ready."));
                    return 0;
                }
                CoffeesAeroAuth.ROOM_MANAGER.teleportToPreview(player);
                player.sendSystemMessage(Component.literal(
                    "§6[Lobby] §7Previewing the lobby room — decorate it, then §a/lobby save§7. §a/spawn§7 to leave."));
                return 1;
            })
            .then(Commands.literal("save")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    boolean ok = CoffeesAeroAuth.ROOM_MANAGER != null && CoffeesAeroAuth.ROOM_MANAGER.saveTemplate();
                    player.sendSystemMessage(Component.literal(ok
                        ? "§a[Lobby] Saved this room as the template for ALL lobbies — existing rooms rebuild on next visit."
                        : "§c[Lobby] Save failed — use §e/lobby§c first so the preview room is loaded, then retry."));
                    return ok ? 1 : 0;
                })
            )
        );

        // /changename <newName> — one-time post-approval display name change (authenticated only)
        dispatcher.register(Commands.literal("changename")
            .then(Commands.argument("newName", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    CoffeesAeroAuth.AUTH_MANAGER.handleChangeName(
                        player, StringArgumentType.getString(ctx, "newName"));
                    return 1;
                })
            )
        );

        // /setname <displayName> — lobby only: pick a display name for approval
        dispatcher.register(Commands.literal("setname")
            .then(Commands.argument("displayName", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    CoffeesAeroAuth.AUTH_MANAGER.handleSetName(
                        player, StringArgumentType.getString(ctx, "displayName"));
                    return 1;
                })
            )
        );
    }
}
