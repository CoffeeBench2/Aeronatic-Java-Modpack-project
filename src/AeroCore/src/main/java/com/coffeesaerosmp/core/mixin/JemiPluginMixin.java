package com.coffeesaerosmp.core.mixin;

import com.coffeesaerosmp.core.client.RecipeViewer;
import mezz.jei.api.registration.IRuntimeRegistration;
import com.mojang.logging.LogUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the player actually choose between EMI and JEI.
 *
 * <h2>Why a mixin is the only way</h2>
 * The pack ships both. EMI does not merely draw over JEI — it ships a JEI plugin
 * ({@code dev.emi.emi.jemi.JemiPlugin}) whose {@code registerRuntime} <b>replaces JEI's entire
 * runtime</b> during mod load:
 *
 * <pre>
 *   registration.setIngredientListOverlay(new JemiIngredientListOverlay());
 *   registration.setBookmarkOverlay(new JemiBookmarkOverlay());
 *   registration.setRecipesGui(...);
 *   registration.setIngredientFilter(...);
 * </pre>
 *
 * JEI's real overlay, bookmarks, recipe GUI and filter are swapped for EMI stand-ins. That is why
 * simply flipping {@code EmiConfig.enabled} at runtime gives you NEITHER viewer: EMI hides itself,
 * and the JEI underneath was already replaced by EMI shims that are now hidden too. Verified from
 * EMI 1.1.24 bytecode, 2026-08-17, after the naive toggle shipped and did exactly that.
 *
 * <p>Cancelling this one call leaves JEI holding its own runtime, so JEI behaves as if EMI's JEI
 * integration were not installed at all.
 *
 * <h2>Why switching needs a restart</h2>
 * {@code registerRuntime} runs once, during plugin registration, long before any settings screen
 * exists. Nothing can un-swap a runtime afterwards. The setting is therefore read here and applied
 * on the next launch — which the toggle says plainly rather than appearing to work and not.
 *
 * <p>Registered in a SEPARATE mixin config with {@code required=false}: it targets another mod's
 * class by name, and EMI is optional. If EMI is absent or renames the class, this must degrade to
 * a log line, never a boot crash.
 */
@Mixin(targets = "dev.emi.emi.jemi.JemiPlugin", remap = false)
public class JemiPluginMixin {

    @Inject(method = "registerRuntime", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    // The parameter type MUST be the target's real type. Declaring it as Object gave
    // "Invalid descriptor ... Expected IRuntimeRegistration but found Object" and, because this
    // config is require=0, the mixin failed SILENTLY and EMI kept the runtime (2026-08-18).
    private void aero$keepJeiRuntime(IRuntimeRegistration registration, CallbackInfo ci) {
        try {
            if (RecipeViewer.preferJei()) {
                ci.cancel();
                LogUtils.getLogger().info(
                    "[AeroCore] Recipe viewer = JEI — skipped EMI's JEI runtime takeover.");
            }
        } catch (Throwable t) {
            // Never let a preference lookup break mod loading; default is EMI (the previous behaviour).
            LogUtils.getLogger().warn("[AeroCore] recipe viewer check failed, leaving EMI in charge: {}",
                t.toString());
        }
    }
}
