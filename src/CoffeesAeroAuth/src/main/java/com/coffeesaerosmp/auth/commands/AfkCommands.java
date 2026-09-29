package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.afk.AfkTracker;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /afkcheck <player>} — what the AFK tracker believes about an online player right now: idle
 * time, the last input that counted, whether they are riding / on a Sable ship, and any input the
 * macro detector is refusing. Exists so a report like "he's been AFK in his cockpit for an hour" can be
 * checked in seconds instead of by reading logs.
 *
 * <p>Uses an entity selector for the target on purpose: it only ever inspects ONLINE players, and it
 * cannot collide with another mod's same-named node (the {@code /invsee} lesson, 2026-09-22) because the
 * literal is ours alone.
 */
public final class AfkCommands {

    private AfkCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("afkcheck")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("target", EntityArgument.player())
                .executes(ctx -> {
                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                    for (String line : AfkTracker.describe(target)) {
                        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                })));
    }
}
