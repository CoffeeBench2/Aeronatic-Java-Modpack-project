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
                    if (!requireLobby(player, "login")) return 0;
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
                        if (!requireLobby(player, "register")) return 0;
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
        // overworld world spawn on a configurable cooldown (spawnTeleportCooldownMinutes, default
        // 3h; ops exempt; CombatGuard blocks it while combat-tagged).
        dispatcher.register(Commands.literal("spawn")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                CoffeesAeroAuth.AUTH_MANAGER.handleSpawn(player);
                return 1;
            })
        );

        // /home — teleport to your bed / respawn point (blocked while combat-tagged; cooldown via config)
        dispatcher.register(Commands.literal("home")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                CoffeesAeroAuth.AUTH_MANAGER.handleHome(player);
                return 1;
            })
        );

        // /vote — the link, plus whether they can vote right now. No permission: this is the one
        // command we actively WANT every player to run.
        dispatcher.register(Commands.literal("vote")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                if (CoffeesAeroAuth.VOTE_REWARDS == null) {
                    player.sendSystemMessage(Component.literal("§cVoting is not set up yet."));
                    return 0;
                }
                String url = com.coffeesaerosmp.auth.config.AuthConfig.VOTE_URL.get();
                long wait = CoffeesAeroAuth.VOTE_REWARDS.msUntilVotable(player.getUUID());
                int votes = CoffeesAeroAuth.VOTE_REWARDS.voteCount(player.getUUID());

                player.sendSystemMessage(Component.literal(
                    com.coffeesaerosmp.auth.util.TextUtil.PREFIX + "§6✦ Vote for the server §7— " + url));
                if (wait > 0) {
                    player.sendSystemMessage(Component.literal(
                        com.coffeesaerosmp.auth.util.TextUtil.PREFIX + "§7You can vote again in §e"
                            + com.coffeesaerosmp.auth.util.TextUtil.formatRemaining(wait) + "§7."));
                    com.coffeesaerosmp.auth.util.Sounds.error(player);
                } else {
                    player.sendSystemMessage(Component.literal(
                        com.coffeesaerosmp.auth.util.TextUtil.PREFIX + "§aYou can vote right now!"));
                    com.coffeesaerosmp.auth.util.Sounds.notify(player);
                }
                int cap = com.coffeesaerosmp.auth.config.AuthConfig.VOTE_REWARD_MAX_REWARDED.get();
                player.sendSystemMessage(Component.literal(
                    com.coffeesaerosmp.auth.util.TextUtil.PREFIX + "§7Votes: §e" + votes
                        + (cap > 0 ? "§7/§e" + cap + " §7rewarded" : "")
                        + " §8· §7rewards land automatically, even if you're offline."));
                if (cap > 0 && votes >= cap) {
                    player.sendSystemMessage(Component.literal(
                        com.coffeesaerosmp.auth.util.TextUtil.PREFIX
                            + "§6You've collected every vote reward §7— voting still helps the "
                            + "server climb the list. §6❤"));
                }
                return 1;
            })
        );

        // /daily — claim the once-per-interval streak reward (items + XP; no currency)
        dispatcher.register(Commands.literal("daily")
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                if (CoffeesAeroAuth.DAILY_REWARDS == null) {
                    player.sendSystemMessage(Component.literal("§cDaily rewards are not ready yet."));
                    return 0;
                }
                return CoffeesAeroAuth.DAILY_REWARDS.claim(player) ? 1 : 0;
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
            .then(Commands.literal("greeter")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        String text = StringArgumentType.getString(ctx, "text").replace('&', '§');
                        net.minecraft.world.phys.AABB box = player.getBoundingBox().inflate(4.0);
                        java.util.List<net.minecraft.world.entity.decoration.ArmorStand> stands =
                            player.serverLevel().getEntitiesOfClass(
                                net.minecraft.world.entity.decoration.ArmorStand.class, box);
                        net.minecraft.world.entity.decoration.ArmorStand target = stands.stream()
                            .min(java.util.Comparator.comparingDouble(s -> s.distanceToSqr(player))).orElse(null);
                        if (target == null) {
                            player.sendSystemMessage(Component.literal(
                                "§c[Lobby] No armor stand within 4 blocks. Place your 'character' (an armor stand, dress it with a player head + armor) first, then run this next to it."));
                            return 0;
                        }
                        target.addTag("aero_spawn_greeter");
                        target.setCustomName(Component.literal(text));
                        target.setCustomNameVisible(true);
                        target.setInvulnerable(true);
                        target.setNoGravity(true);
                        player.sendSystemMessage(Component.literal(
                            "§a[Lobby] Greeter set — players right-click it to §e/spawn§a. Floating text: §f" + text));
                        return 1;
                    })
                )
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
                    if (!requireLobby(player, "setname")) return 0;
                    CoffeesAeroAuth.AUTH_MANAGER.handleSetName(
                        player, StringArgumentType.getString(ctx, "displayName"));
                    return 1;
                })
            )
        );
    }

    /**
     * <b>INVARIANT (2026-07-27): authentication happens IN THE LOBBY, NEVER OUTSIDE IT.</b>
     *
     * <p>Before this guard existed, {@code /login}, {@code /register} and {@code /setname} had no
     * dimension check at all — the invariant held only by <i>routing convention</i>, i.e. because
     * {@code AuthManager} happened to send unauthenticated players to the lobby before they could
     * type anything. ({@code /setname} was even commented "lobby only" while being enforced
     * nowhere.) Convention is not enforcement: any future code path, admin teleport, config change
     * or bug that leaves an unauthenticated player standing in the world would have let them
     * authenticate from there — which is precisely the breach worth preventing, because it means
     * they reached the world <i>without</i> passing through auth.</p>
     *
     * <p>This is defence in depth. It does not replace the routing in {@code AuthManager}; it makes
     * the routing's guarantee impossible to violate by accident. A rejection here is a real bug
     * signal, so it is logged at WARN with the player's dimension.</p>
     *
     * <p><b>Deliberately NOT guarded: {@code /changepassword}.</b> That requires the player to be
     * already authenticated and to supply their existing password, so it cannot be used to gain
     * access — it only rotates a credential for someone who already has it. Forcing a player to
     * travel back to the lobby to change their password would be a usability regression with no
     * security benefit. If that judgement is ever revisited, this is the place to change it.</p>
     *
     * @return {@code true} if the player is in the auth lobby and the command may proceed
     */
    private static boolean requireLobby(ServerPlayer player, String action) {
        if (player.level().dimension()
                == com.coffeesaerosmp.auth.lobby.PrivateRoomManager.LOBBY_DIMENSION) {
            return true;
        }
        player.sendSystemMessage(Component.literal(
            "§8[§bAero§8] §cAuthentication can only be done in the lobby."));
        CoffeesAeroAuth.LOGGER.warn(
            "[Auth] BLOCKED /{} from {} outside the lobby (dimension {}) — an unauthenticated "
            + "player should never have been able to reach that dimension. Investigate the routing.",
            action, player.getGameProfile().getName(), player.level().dimension().location());
        return false;
    }
}
