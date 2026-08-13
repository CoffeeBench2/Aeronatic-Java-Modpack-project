package com.coffeesaerosmp.guard.protect;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * Claim check for Create's block-entity configuration packet.
 *
 * <p><b>The hole this closes.</b> Create's {@code BlockEntityConfigurationPacket} — the packet behind
 * scroll-to-set-value on speed controllers, funnels, chutes, mechanical arms, sequenced gearshifts
 * and every other Create block with a value panel — applies its change after only four checks
 * (verified from Create 6.0.10 bytecode):
 *
 * <pre>
 *   isSpectator? · isAdventure? · chunk loaded? · within reach?  →  applySettings(...)
 * </pre>
 *
 * <p>It fires <b>no</b> {@code PlayerInteractEvent.RightClickBlock}, so FTB Chunks, AeroClaims and
 * {@link AdminBypass} never see it. Any player standing within reach could retune any Create block
 * inside anyone's claim. AeroClaims does mixin this packet, but AeroClaims guards <i>ships</i> — it
 * has nothing to say about an FTB Chunks land claim.
 *
 * <p><b>Why the FTB Chunks API and not a synthetic event.</b> The obvious trick — post a fake
 * {@code RightClickBlock} and see whether anything cancels it — is unsafe in this pack: RightClickHarvest
 * and FallingTree <i>act</i> on that event, so a permission probe could harvest a crop or fell a tree
 * as a side effect. {@code ClaimedChunkManager.shouldPreventInteraction} answers the same question
 * with no side effects at all.
 *
 * <p>All access is reflective — Guard deliberately has no FTB Chunks dependency — and every failure
 * path returns "allowed", so a missing or changed API can never lock players out of their own builds.
 */
public final class CreateConfigGuard {

    private CreateConfigGuard() {}

    private static boolean resolved;
    private static Method  apiMethod;        // FTBChunksAPI.api()
    private static Method  isManagerLoaded;
    private static Method  getManager;
    private static Method  shouldPrevent;    // ClaimedChunkManager.shouldPreventInteraction(...)
    private static Object  interactBlock;    // Protection.INTERACT_BLOCK
    private static boolean available;

    private static synchronized void resolve() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> api = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
            apiMethod = api.getMethod("api");
            Class<?> apiIface = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI$API");
            isManagerLoaded = apiIface.getMethod("isManagerLoaded");
            getManager = apiIface.getMethod("getManager");

            Class<?> mgr = Class.forName("dev.ftb.mods.ftbchunks.api.ClaimedChunkManager");
            Class<?> prot = Class.forName("dev.ftb.mods.ftbchunks.api.Protection");
            interactBlock = prot.getField("INTERACT_BLOCK").get(null);

            shouldPrevent = mgr.getMethod("shouldPreventInteraction",
                net.minecraft.world.entity.Entity.class,
                net.minecraft.world.InteractionHand.class,
                BlockPos.class,
                prot,
                net.minecraft.world.entity.Entity.class);

            available = true;
            LogUtils.getLogger().info("[AeroGuard] Create config-packet claim guard active (FTB Chunks).");
        } catch (Throwable t) {
            available = false;
            LogUtils.getLogger().info(
                "[AeroGuard] Create config-packet claim guard inactive — FTB Chunks API not found ({}).",
                t.toString());
        }
    }

    /**
     * @return {@code true} when this player may retune the Create block at {@code pos}.
     *         Fails open: anything unexpected returns {@code true}.
     */
    public static boolean allowed(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null) return true;

        // Ops with /aerobypass on keep working exactly as they did before this guard existed.
        if (player.hasPermissions(2) && AdminBypass.isEnabled(player.getUUID())) return true;

        resolve();
        if (!available) return true;

        try {
            Object apiObj = apiMethod.invoke(null);
            if (apiObj == null) return true;
            if (!Boolean.TRUE.equals(isManagerLoaded.invoke(apiObj))) return true;
            Object manager = getManager.invoke(apiObj);
            if (manager == null) return true;

            Object prevent = shouldPrevent.invoke(manager,
                player, net.minecraft.world.InteractionHand.MAIN_HAND, pos, interactBlock, null);
            return !Boolean.TRUE.equals(prevent);
        } catch (Throwable t) {
            LogUtils.getLogger().warn("[AeroGuard] claim check failed — allowing", t);
            return true;
        }
    }
}
