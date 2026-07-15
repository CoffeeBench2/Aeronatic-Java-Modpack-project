package com.coffeesaerosmp.railguard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Makes FTB-claimed chunks fireproof against Burnt (and vanilla fire). FTB claims block only PLAYER
 * edits; fire spread is an unattributed world mechanic, so a claimed house can still burn down — and
 * Burnt makes fire aggressive (fast spread, charring, collapse, fire devils). This closes that gap:
 * inside a claim, fire/flame/smoldering blocks can't appear, and no claimed block can be turned to
 * air while fire is adjacent — so nothing catches, chars, or collapses.
 *
 * <p>Deliberately narrow so legitimate builds keep working: it only touches the open fire block and
 * fire-driven block changes. Campfires, furnaces, blaze burners, torches, and lanterns are DIFFERENT
 * blocks and are untouched. Fire OUTSIDE claims keeps Burnt's full behaviour.</p>
 *
 * <p>Burnt is an MCreator "procedures" mod (net.pixelbank.burnt.procedures.*) — its fire spread and
 * charring run as scripts that call {@code Level.setBlock} directly, so the setBlock mixin is the one
 * chokepoint that catches all of it. Burnt's fire/smoldering blocks extend plain {@code Block} (not
 * {@code BaseFireBlock}), so they're matched by registry name. All FTB classes live in {@link Impl};
 * the guard no-ops if FTB Chunks isn't loaded.</p>
 */
public final class ClaimFireGuard {

    private ClaimFireGuard() {}

    /** On by default — the whole point is that claims can't burn. Toggle in-memory if it ever misbehaves. */
    public static volatile boolean ENABLED = true;

    private static boolean ftbPresent = false;

    /** Fire/flame/smoldering blocks, resolved once from the frozen registry (identity set = O(1) lookups). */
    private static volatile Set<Block> fireBlocks;

    public static void install() {
        ftbPresent = ModList.get().isLoaded("ftbchunks");
        CoffeesAeroRailguard.LOGGER.info(ftbPresent
            ? "[Railguard] FTB Chunks present — claimed chunks are fireproof against Burnt/vanilla fire."
            : "[Railguard] FTB Chunks not present — claim fire guard idle.");
    }

    /** Called from the {@code Level.setBlock} mixin. Returns true → cancel this block change. */
    public static boolean shouldBlock(ServerLevel level, BlockPos pos, BlockState newState) {
        if (!ENABLED || !ftbPresent) return false;
        try {
            boolean placingFire = isFireLike(newState);
            // Hot-path gate: only a fire/smoldering block appearing, or a block cleared to air (possible
            // burn-out/collapse), can damage a claim. Everything else returns immediately.
            if (!placingFire && !newState.isAir()) return false;
            if (!Impl.isClaimed(level, pos)) return false;

            if (placingFire) return true;   // no fire/char may appear inside a claim

            // newState is air in a claim: block it only when fire is eating the block (fire adjacent),
            // so players and machines can still break their own blocks normally.
            for (Direction d : Direction.values()) {
                if (isFireLike(level.getBlockState(pos.relative(d)))) return true;
            }
        } catch (Throwable ignored) {
            // Never let the guard break a world edit.
        }
        return false;
    }

    private static boolean isFireLike(BlockState state) {
        return fireBlocks().contains(state.getBlock());
    }

    private static Set<Block> fireBlocks() {
        Set<Block> set = fireBlocks;
        if (set != null) return set;
        synchronized (ClaimFireGuard.class) {
            if (fireBlocks != null) return fireBlocks;
            Set<Block> s = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Block b : BuiltInRegistries.BLOCK) {
                if (b instanceof BaseFireBlock) { s.add(b); continue; }  // fire, soul_fire
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
                if ("burnt".equals(id.getNamespace())) {
                    String p = id.getPath();
                    if (p.contains("fire") || p.contains("flame") || p.contains("smolder") || p.contains("ember"))
                        s.add(b);   // Burnt spread + charring (smoldering_*) blocks
                }
            }
            fireBlocks = s;
            return s;
        }
    }

    /** FTB classes are only referenced here, so the mod loads fine without FTB Chunks. */
    private static final class Impl {
        static boolean isClaimed(ServerLevel level, BlockPos pos) {
            dev.ftb.mods.ftbchunks.api.FTBChunksAPI.API api = dev.ftb.mods.ftbchunks.api.FTBChunksAPI.api();
            if (api == null || !api.isManagerLoaded()) return false;
            return api.getManager().getChunk(
                new dev.ftb.mods.ftblibrary.math.ChunkDimPos(level, pos)) != null;
        }
    }
}
