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
            .then(Commands.literal("debug")
                .executes(ctx -> toggleDebug(ctx.getSource())))
            .then(Commands.literal("chunkhere")
                .executes(ctx -> chunkHere(ctx.getSource(), 0))
                .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 8))
                    .executes(ctx -> chunkHere(ctx.getSource(),
                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius")))))
            .then(Commands.literal("unclaimchunkhere")
                .executes(ctx -> unclaimChunkHere(ctx.getSource())))
        );
    }

    private static int toggleDebug(CommandSourceStack source) {
        ProtectionEvents.DEBUG = !ProtectionEvents.DEBUG;
        boolean on = ProtectionEvents.DEBUG;
        source.sendSuccess(() -> Component.literal("§6[Railguard]§7 Debug logging "
            + (on ? "§aON§7 — every denial is logged with actor, path and position." : "§coff§7.")), true);
        return 1;
    }

    /** Marks the sender's chunk (± radius) as server railway property — the quick tool for the
     *  spawn station and other sections generated before the guard existed. */
    private static int chunkHere(CommandSourceStack source, int radius) {
        var player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("§cPlayers only.")); return 0; }
        RailguardData data = RailguardData.get(source.getLevel());
        int cx = player.blockPosition().getX() >> 4, cz = player.blockPosition().getZ() >> 4;
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                data.addChunk(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz));
                n++;
            }
        int added = n;
        source.sendSuccess(() -> Component.literal("§6[Railguard]§a Marked " + added
            + " chunk" + (added == 1 ? "" : "s") + " around you as railway property. §7(total: "
            + data.chunkCount() + ")"), true);
        return 1;
    }

    private static int unclaimChunkHere(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("§cPlayers only.")); return 0; }
        RailguardData data = RailguardData.get(source.getLevel());
        data.removeChunk(net.minecraft.world.level.ChunkPos.asLong(
            player.blockPosition().getX() >> 4, player.blockPosition().getZ() >> 4));
        source.sendSuccess(() -> Component.literal("§6[Railguard]§a This chunk is no longer railway property. §7(total: "
            + data.chunkCount() + ")"), true);
        return 1;
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
                data.add(pos.immutable());
            } else {
                data.releasePosition(pos.immutable());
            }
            changed++;
        }
        int n = changed;
        source.sendSuccess(() -> Component.literal("§6[Railguard]§a " + (protect ? "Protected " : "Released ")
            + n + " position" + (n == 1 ? "" : "s") + ". §7(dimension: " + data.positionCount()
            + " positions / " + data.chunkCount() + " chunks)"), true);
        return 1;
    }

    private static int check(CommandSourceStack source, BlockPos pos) {
        RailguardData data = RailguardData.get(source.getLevel());
        boolean p = data.isPositionProtected(pos);
        boolean c = data.isChunkProtected(pos);
        source.sendSuccess(() -> Component.literal("§6[Railguard]§7 " + pos.toShortString() + " → "
            + (p || c ? "§aPROTECTED §7(" + (p ? "position" : "") + (p && c ? " + " : "") + (c ? "chunk" : "") + ")"
                      : "§cnot protected")), false);
        return p || c ? 1 : 0;
    }

    private static int count(CommandSourceStack source) {
        RailguardData data = RailguardData.get(source.getLevel());
        source.sendSuccess(() -> Component.literal("§6[Railguard]§7 " + data.positionCount()
            + " protected positions, " + data.chunkCount() + " railway chunks in this dimension. §8Bypassing: "
            + ProtectionEvents.BYPASS.size() + ", debug: " + (ProtectionEvents.DEBUG ? "on" : "off")), false);
        return 1;
    }
}
