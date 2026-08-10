package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.protect.PlotGuard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clamps a player loading in inside Sable plot space back to spawn before they can create a
 * chunk ticket there — see {@link PlotGuard} for the crash this exists to stop.
 *
 * <p>INJECTION POINT, verified against the decompiled 1.21.1 source rather than assumed:
 * {@code Entity.load(CompoundTag)} reads {@code "Pos"} (line 1734), applies it via
 * {@code setPosRaw} (1742), and only then calls {@code readAdditionalSaveData} (1797).
 * {@code ServerPlayer} overrides it as {@code public void readAdditionalSaveData(CompoundTag)}
 * (ServerPlayer.java:342). So at TAIL the saved position is live, but
 * {@code PlayerList.placeNewPlayer} has not yet reached {@code level.addNewPlayer} and no
 * ticket exists — the last moment a plain {@code setPos} is enough and no teleport is needed.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerPlotGuardMixin {

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void aeroauth$plotGuardOnLoad(CompoundTag tag, CallbackInfo ci) {
        PlotGuard.clampOnLoad((ServerPlayer) (Object) this);
    }
}
