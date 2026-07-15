package com.coffeesaerosmp.railguard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Keeps FTB-claimed chunks playing by <em>vanilla</em> fire rules: inside a claim you can still light
 * fires and normal Minecraft fire spreads/burns as usual, but Burnt's aggressive extras (its own fire
 * blocks, charring, smouldering, sooting) can't touch the claim — so farms and builds don't get eaten.
 *
 * <p><b>How Burnt works</b> (checked against burnt 1.9.x): it does NOT hijack flint&amp;steel or mixin
 * vanilla fire — lighting a fire places a plain {@code minecraft:fire}. Burnt's procedures then react to
 * {@code Blocks.FIRE}: they "upgrade" it to Burnt fire blocks ({@code burnt:wood_fire}, {@code soul_fire_N},
 * {@code *_flames}, {@code stairs_fire}…) and convert adjacent flammables in place via {@code Level.setBlock}
 * (e.g. {@code wheat → smoldering_crops → burnt_crops}, {@code oak_log → burnt_log}). That single setBlock
 * chokepoint is what {@link com.coffeesaerosmp.railguard.mixin.ClaimFireMixin} guards.</p>
 *
 * <p><b>The rule, inside a claim only:</b></p>
 * <ol>
 *   <li>Vanilla fire and everything non-Burnt: <b>untouched</b> — lighting works, fire behaves vanilla.</li>
 *   <li>A Burnt <b>fire/flame</b> block appearing: <b>cancelled</b> — so Burnt's "upgrade" of the vanilla
 *       fire is refused and the plain {@code minecraft:fire} stays. Net effect: claims show vanilla fire.</li>
 *   <li>Any other Burnt block <b>replacing an existing (non-air, non-replaceable) block</b>: <b>cancelled</b>
 *       — that's fire charring/sooting a player's crop/log/wool, i.e. the farm damage.</li>
 *   <li>A Burnt block placed on air/replaceable: <b>allowed</b> — that's a player building with decorative
 *       burnt/ember/sooty blocks in their own claim.</li>
 * </ol>
 *
 * <p>Fire OUTSIDE claims keeps Burnt's full behaviour. FTB classes are isolated in {@link Impl} so the mod
 * loads fine without FTB Chunks.</p>
 */
public final class ClaimFireGuard {

    private ClaimFireGuard() {}

    /** On by default — the whole point is that claims play by vanilla fire rules. Toggle in-memory if needed. */
    public static volatile boolean ENABLED = true;

    private static boolean ftbPresent = false;

    /** All {@code burnt:} blocks, and the fire/flame subset — resolved once from the frozen registry. */
    private static volatile Set<Block> burntBlocks;
    private static volatile Set<Block> burntFire;

    public static void install() {
        ftbPresent = ModList.get().isLoaded("ftbchunks");
        CoffeesAeroRailguard.LOGGER.info(ftbPresent
            ? "[Railguard] FTB Chunks present — claimed chunks play by vanilla fire rules (Burnt extras suppressed inside claims)."
            : "[Railguard] FTB Chunks not present — claim fire guard idle.");
    }

    /** Called from the {@code Level.setBlock} mixin HEAD. Returns true → cancel this block change. */
    public static boolean shouldBlock(ServerLevel level, BlockPos pos, BlockState newState) {
        if (!ENABLED || !ftbPresent) return false;
        try {
            Block b = newState.getBlock();
            // Fast reject: only a Burnt block appearing can ever be blocked. This is the hot path — one
            // identity-set lookup for every vanilla/other setBlock, no claim lookup, no work.
            if (!burnt().contains(b)) return false;
            if (!Impl.isClaimed(level, pos)) return false;

            // Burnt's own fire/flame: refuse it so the vanilla minecraft:fire it was upgrading stays put.
            if (fire().contains(b)) return true;

            // Otherwise it's a char/soot/smoulder variant. Block it only when it's REPLACING a real block
            // (fire eating a crop/log/wool). Placing on air/replaceable is a player building — allow it.
            BlockState old = level.getBlockState(pos);
            return !old.isAir() && !old.canBeReplaced();
        } catch (Throwable ignored) {
            // Never let the guard break a world edit.
            return false;
        }
    }

    private static Set<Block> burnt() {
        Set<Block> set = burntBlocks;
        return set != null ? set : build()[0];
    }

    private static Set<Block> fire() {
        Set<Block> set = burntFire;
        return set != null ? set : build()[1];
    }

    /** Lazily split the block registry once: all burnt: blocks, and the fire/flame subset among them. */
    private static synchronized Set<Block>[] build() {
        if (burntBlocks == null || burntFire == null) {
            Set<Block> all  = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Block> fire = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Block b : BuiltInRegistries.BLOCK) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(b);
                if (!"burnt".equals(id.getNamespace())) continue;
                all.add(b);
                if (isBurntFire(id.getPath())) fire.add(b);
            }
            burntBlocks = all;
            burntFire = fire;
        }
        @SuppressWarnings("unchecked")
        Set<Block>[] out = new Set[]{burntBlocks, burntFire};
        return out;
    }

    /**
     * True for Burnt's transient fire/flame blocks (the spreading visuals) — NOT campfires, fire barrels,
     * ember/sooty building blocks, or the firestarter. Matches {@code *_fire}, {@code *_fire_<n>}
     * (wood_fire, soul_fire_7, stairs_fire, tall_grass_fire) and anything containing {@code flames}
     * (grass_flames, tall_flames, devil_flames, soul_flames_ground).
     */
    private static boolean isBurntFire(String path) {
        if (path.contains("flames")) return true;
        if (path.endsWith("_fire")) return true;
        int u = path.lastIndexOf("_fire_");
        if (u >= 0) {                    // e.g. soul_fire_10, stairs_fire_3 — trailing part must be digits
            String tail = path.substring(u + 6);
            return !tail.isEmpty() && tail.chars().allMatch(Character::isDigit);
        }
        return false;
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
