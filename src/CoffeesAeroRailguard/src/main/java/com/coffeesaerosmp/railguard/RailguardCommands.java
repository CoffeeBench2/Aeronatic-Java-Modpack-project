package com.coffeesaerosmp.railguard;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Admin tools (op 3+):
 * <ul>
 *   <li>{@code /railguard mark <from> <to>} — protect every NON-AIR block in the box. For railway
 *       sections generated BEFORE Railguard was installed (live tracking only sees new ones).</li>
 *   <li>{@code /railguard unmark <from> <to>} — release a box.</li>
 *   <li>{@code /railguard check <pos>} — is this position protected?</li>
 *   <li>{@code /railguard count} — protected positions in this dimension.</li>
 * </ul>
 */
public final class RailguardCommands {

    private RailguardCommands() {}

    /** Safety cap so a fat-fingered box can't freeze the server. */
    private static final long MAX_BOX_VOLUME = 4_000_000L;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("railguard")
            .requires(src -> src.hasPermission(3))
            .then(Commands.literal("mark")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(ctx -> mark(ctx.getSource(),
                            BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                            BlockPosArgument.getLoadedBlockPos(ctx, "to"), true)))))
            .then(Commands.literal("unmark")
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(ctx -> mark(ctx.getSource(),
                            BlockPosArgument.getLoadedBlockPos(ctx, "from"),
                            BlockPosArgument.getLoadedBlockPos(ctx, "to"), false)))))
            .then(Commands.literal("check")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .executes(ctx -> check(ctx.getSource(),
                        BlockPosArgument.getLoadedBlockPos(ctx, "pos")))))
            .then(Commands.literal("count")
                .executes(ctx -> count(ctx.getSource())))
            .then(Commands.literal("bypass")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> toggleBypass(ctx.getSource())))
        );
    }

    private static int toggleBypass(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("§cPlayers only."));
            return 0;
        }
        boolean on;
        if (ProtectionEvents.BYPASS.remove(player.getUUID())) {
            on = false;
        } else {
            ProtectionEvents.BYPASS.add(player.getUUID());
            on = true;
        }
        source.sendSuccess(() -> Component.literal(on
            ? "§6[Railguard]§e BYPASS ON §7— your breaks now go through AND un-protect those positions."
            : "§6[Railguard]§a Bypass off — the railway is protected from you again."), true);
        return 1;
    }

    private static int mark(CommandSourceStack source, BlockPos from, BlockPos to, boolean protect) {
        ServerLevel level = source.getLevel();
        BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()));
        BlockPos max = new BlockPos(Math.max(from.getX(), to.getX()), Math.max(from.getY(), to.getY()), Math.max(from.getZ(), to.getZ()));
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > MAX_BOX_VOLUME) {
            source.sendFailure(Component.literal("§cBox too large (" + volume + " blocks; max " + MAX_BOX_VOLUME + ")."));
            return 0;
        }
        RailguardData data = RailguardData.get(level);
        int changed = 0;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            if (protect) {
                if (level.getBlockState(pos).isAir()) continue;   // only real railway blocks
                data.add(pos.asLong());
            } else {
                data.remove(pos.asLong());
            }
            changed++;
        }
        int n = changed;
        source.sendSuccess(() -> Component.literal("§6[Railguard]§a " + (protect ? "Protected " : "Released ")
            + n + " position" + (n == 1 ? "" : "s") + ". §7(dimension total: " + data.size() + ")"), true);
        return 1;
    }

    private static int check(CommandSourceStack source, BlockPos pos) {
        boolean hit = RailguardData.get(source.getLevel()).contains(pos.asLong());
        source.sendSuccess(() -> Component.literal("§6[Railguard]§7 " + pos.toShortString() + " → "
            + (hit ? "§aPROTECTED" : "§cnot protected")), false);
        return hit ? 1 : 0;
    }

    private static int count(CommandSourceStack source) {
        int n = RailguardData.get(source.getLevel()).size();
        source.sendSuccess(() -> Component.literal("§6[Railguard]§7 " + n + " protected position"
            + (n == 1 ? "" : "s") + " in this dimension."), false);
        return 1;
    }
}
