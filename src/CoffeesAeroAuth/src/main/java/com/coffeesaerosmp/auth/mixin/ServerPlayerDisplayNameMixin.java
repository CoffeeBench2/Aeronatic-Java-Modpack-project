package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.auth.NameMask;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Masks the real account name in every server-composed message built from
 * {@code ServerPlayer#getDisplayName} — most importantly the vanilla JOIN message, which fires
 * before auth completes (i.e. before {@link NameMask} swaps the GameProfile). Death/leave/
 * advancement messages are already covered by the post-auth profile swap; this closes the pre-auth
 * window using the display name stored on the DB profile.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDisplayNameMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void aeroauth$maskDisplayName(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        String masked = NameMask.maskedNameFor(self);
        if (masked == null) return;   // no profile yet, or already showing the display name
        cir.setReturnValue(PlayerTeam.formatNameForTeam(self.getTeam(), Component.literal(masked)));
    }
}
