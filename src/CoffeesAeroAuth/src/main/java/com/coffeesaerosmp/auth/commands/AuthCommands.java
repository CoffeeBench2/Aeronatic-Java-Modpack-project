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

        // /spawn — exit lobby room into main world (lobby dimension only)
        dispatcher.register(Commands.literal("spawn")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                CoffeesAeroAuth.AUTH_MANAGER.handleSpawn(player);
                return 1;
            })
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
