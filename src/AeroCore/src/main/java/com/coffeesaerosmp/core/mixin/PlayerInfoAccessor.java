package com.coffeesaerosmp.core.mixin;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/**
 * Lets {@link com.coffeesaerosmp.core.client.SelfSkinHandler} replace the local player's skin lookup.
 * Vanilla's lookup ({@code PlayerInfo.createSkinLookup}) drops non-"secure" skins for the LOCAL player
 * ({@code .filter(skin -> skin.secure() || !isLocalPlayer)}), which on our offline-UUID backend means
 * the wearer's own model stays default forever. Swapping the supplier bypasses the filter cleanly — the skin
 * still loads through vanilla's SkinManager (cache, HTTP, texture registration).
 */
@Mixin(PlayerInfo.class)
public interface PlayerInfoAccessor {

    @Mutable
    @Accessor("skinLookup")
    void aerocore$setSkinLookup(Supplier<PlayerSkin> lookup);
}
