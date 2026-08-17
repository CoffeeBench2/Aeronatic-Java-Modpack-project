package com.coffeesaerosmp.guard.mixin;

import com.coffeesaerosmp.guard.CoffeesAeroGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * TEMPORARY DIAGNOSTIC — cuts the bank-terminal / vendor investigation in half.
 *
 * <p>Both blocks reach {@code PublicInteract} with {@code canceled=false}, meaning nothing on the
 * server denies the click, yet neither menu opens. Both blocks do exactly one thing at the end of
 * their {@code useItemOn}: call {@code Utils.openScreen}. So the question is binary, and this
 * answers it:
 *
 * <ul>
 *   <li><b>This line appears</b> → the server DID open the menu. The failure is client-side and
 *       nothing more on the server is worth looking at.</li>
 *   <li><b>This line does NOT appear</b> → the block bailed before opening. For the terminal that
 *       can only be the authorisation branch; for the vendor there is no such branch at all, which
 *       would itself be a significant finding.</li>
 * </ul>
 *
 * <p>Lives in its own mixin config with {@code required=false} on purpose: it targets ANOTHER MOD'S
 * class by name, so if Numismatics ever renames {@code Utils.openScreen} this must degrade to a log
 * warning, never to a boot crash on a live server. Remove once the cause is known.
 */
@Mixin(targets = "dev.ithundxr.createnumismatics.util.Utils", remap = false)
public class NumismaticsOpenScreenDebugMixin {

    @Inject(method = "openScreen", at = @At("HEAD"), remap = false, require = 0)
    private static void aero$logOpenScreen(ServerPlayer player, MenuProvider provider,
                                           Consumer<?> extraData, CallbackInfo ci) {
        try {
            CoffeesAeroGuard.LOGGER.info(
                "[Numismatics] openScreen REACHED — player={} provider={} pos={}",
                player == null ? "?" : player.getName().getString(),
                provider == null ? "null" : provider.getClass().getName(),
                player == null ? "?" : player.blockPosition());
        } catch (Throwable ignored) {
            // A diagnostic must never be able to break the interaction it is observing.
        }
    }
}
