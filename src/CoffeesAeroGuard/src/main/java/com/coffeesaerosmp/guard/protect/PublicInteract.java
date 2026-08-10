package com.coffeesaerosmp.guard.protect;

import com.coffeesaerosmp.guard.config.GuardConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Lets ANY player use a listed block inside someone else's claim — seats, chairs, ship controls.
 *
 * <h2>Why a mixin-free un-cancel, and why a tag</h2>
 * The blocker is <b>AeroClaims</b>, not FTB Chunks. Proven 2026-08-08 by string-matching the exact
 * in-game message to {@code aeroclaims-0.9.1.jar}:
 * {@code "message.aeroclaims.no_access_use_block": "§cYou don't have permission to use this block"}.
 * (FTB Chunks' wording is "Interaction prevented here by claim protection!" — a different mod, a
 * different message, and the reason the FTB Chunks {@code interact_whitelist} tag had no effect.)
 * AeroClaims exposes <b>no</b> allowlist config — only party/claim providers and explosion/kinetic
 * toggles — so there is nothing to configure our way out of.
 *
 * <p>AeroClaims denies by <b>cancelling</b> {@link PlayerInteractEvent.RightClickBlock}. A listener
 * registered at {@code LOWEST} priority with {@code receiveCanceled = true} therefore runs after it
 * and can reverse the decision — the same mechanism {@link AdminBypass} already uses for ops. No
 * mixin, no jar patch, and it keeps working if AeroClaims is updated, because it depends only on
 * the event contract rather than on any internal class.
 *
 * <p><b>The list is a block tag, not config or code</b>, so it is datapack-driven:
 * {@code data/coffees_aero_auth/tags/block/public_interact.json}. Adding or removing blocks is a
 * file edit plus {@code /reload} — no jar rebuild, no restart. That matters because "which blocks
 * should passengers be allowed to touch" is a policy question that will keep changing.
 *
 * <p><b>This deliberately weakens claim protection for exactly the tagged blocks.</b> It is not a
 * bypass for a player, it is a property of the block, so anything listed is usable by everyone
 * everywhere. Keep the tag to things that are safe to share.
 */
public final class PublicInteract {

    private PublicInteract() {}

    /** Blocks anyone may use inside any claim. Populated by datapack; empty tag = feature is inert. */
    public static final TagKey<Block> PUBLIC_INTERACT = TagKey.create(
        Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("coffees_aero_guard", "public_interact"));

    /** LOWEST priority + receiveCanceled=true — registered that way in CoffeesAeroAuth. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!GuardConfig.PUBLIC_INTERACT_ENABLED.get()) return;
        if (!event.isCanceled()) return;                  // nobody denied it; nothing to undo

        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!state.is(PUBLIC_INTERACT)) return;

        event.setCanceled(false);
        // Restoring both TriStates matters: a protection mod that DENYs useBlock leaves the click
        // dead even after un-cancelling, because vanilla then skips the block's own use handler.
        event.setUseBlock(TriState.DEFAULT);
        event.setUseItem(TriState.DEFAULT);
    }
}
