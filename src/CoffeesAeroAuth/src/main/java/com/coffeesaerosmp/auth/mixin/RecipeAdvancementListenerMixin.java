package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.util.RecipeAdvancementFilter;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops recipe-unlock advancements registering criteria listeners.
 *
 * <p>The whole rationale, the measurements behind it and the safety argument live on
 * {@link RecipeAdvancementFilter} — this class is only the hook. In short: 7.89% of the live server
 * thread was {@code slotChanged → InventoryChangeTrigger.trigger}, and 98% of the listeners that scan
 * over are recipe unlocks that exist only to fill the recipe book.
 *
 * <h2>Why HEAD of registerListeners and not the trigger itself</h2>
 * The cost is proportional to how many listeners are registered, so the cheapest place to fix it is
 * the registration, not the scan. Cancelling here means the listener never enters
 * {@code InventoryChangeTrigger}'s per-player set at all, so the hot loop shrinks instead of gaining
 * an extra per-iteration test. It also keeps this mod out of the trigger's hot path entirely, which
 * matters: a mistake in {@code registerListeners} costs an unlock, a mistake inside the trigger loop
 * would cost frametime on every slot change.
 *
 * <h2>🔴 The overload trap</h2>
 * {@code PlayerAdvancements} has TWO methods named {@code registerListeners} — one taking a
 * {@link ServerAdvancementManager} (the bulk loop) and one taking an {@link AdvancementHolder} (the
 * single). The descriptor is spelled out in full on both injections; an unqualified {@code "method =
 * "registerListeners""} would match both and fail to apply.
 *
 * <h2>Why cancelling is safe</h2>
 * Vanilla's {@code registerListeners(AdvancementHolder)} already no-ops for any advancement that is
 * done, and for any individual criterion that is done. "Registered no listeners for this advancement"
 * is therefore a state the surrounding code handles as a matter of course — it is not a novel one.
 * Nothing is written to disk, no packet is sent, and no progress is revoked, so the change is fully
 * reversible by config with no migration.
 */
@Mixin(PlayerAdvancements.class)
public abstract class RecipeAdvancementListenerMixin {

    @Shadow private ServerPlayer player;

    private static final Logger COFFEES_AERO_AUTH$LOG =
        LoggerFactory.getLogger("CoffeesAeroAuth-AdvFilter");

    /** Snapshot of the global skip counter taken when a player's bulk load starts. */
    private long coffees_aero_auth$skippedAtLoadStart;

    @Inject(method = "registerListeners(Lnet/minecraft/advancements/AdvancementHolder;)V",
            at = @At("HEAD"), cancellable = true)
    private void coffees_aero_auth$skipRecipeUnlocks(AdvancementHolder advancement, CallbackInfo ci) {
        if (RecipeAdvancementFilter.isSkippableRecipeUnlock(advancement)) {
            ci.cancel();
        }
    }

    // ── Per-join summary ──────────────────────────────────────────────────────────
    // Without this there is no way to tell from the log whether the filter engaged or silently
    // matched nothing, and "silently matched nothing" looks identical to "deployed and working"
    // until the next profile is taken.

    @Inject(method = "registerListeners(Lnet/minecraft/server/ServerAdvancementManager;)V",
            at = @At("HEAD"))
    private void coffees_aero_auth$markLoadStart(ServerAdvancementManager manager, CallbackInfo ci) {
        this.coffees_aero_auth$skippedAtLoadStart = RecipeAdvancementFilter.skippedTotal();
    }

    @Inject(method = "registerListeners(Lnet/minecraft/server/ServerAdvancementManager;)V",
            at = @At("TAIL"))
    private void coffees_aero_auth$logLoadSummary(ServerAdvancementManager manager, CallbackInfo ci) {
        long skipped = RecipeAdvancementFilter.skippedTotal() - this.coffees_aero_auth$skippedAtLoadStart;
        if (skipped <= 0) return;
        String who = (this.player != null) ? this.player.getGameProfile().getName() : "<unknown>";
        COFFEES_AERO_AUTH$LOG.info("{}: skipped {} recipe-advancement listener registrations", who, skipped);
    }
}
