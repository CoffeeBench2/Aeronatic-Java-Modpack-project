package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.auth.NameMask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Masks the real account name in every server-composed message built from
 * {@code getDisplayName} — most importantly the vanilla JOIN message, which fires before auth
 * completes (i.e. before {@link NameMask} swaps the GameProfile). Death/leave/advancement messages
 * are already covered by the post-auth profile swap; this closes the pre-auth window using the
 * display name stored on the DB profile.
 *
 * <p>MUST target {@link Player} — {@code getDisplayName} is declared here, NOT overridden in
 * ServerPlayer (targeting ServerPlayer crash-looped Apex on 1.6.0: "could not find any targets
 * matching 'getDisplayName'"). {@code require = 0}: if the hook ever fails to apply, the pre-auth
 * join message shows the real name briefly instead of taking the server down.</p>
 */
@Mixin(Player.class)
public abstract class PlayerDisplayNameMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void aeroauth$maskDisplayName(CallbackInfoReturnable<Component> cir) {
        if (!((Object) this instanceof ServerPlayer sp)) return;   // client/other contexts untouched
        String masked = NameMask.maskedNameFor(sp);
        if (masked == null) return;   // no profile yet, or already showing the display name
        cir.setReturnValue(PlayerTeam.formatNameForTeam(sp.getTeam(), Component.literal(masked)));
    }
}
