package com.coffeesaerosmp.auth.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets {@link com.coffeesaerosmp.auth.auth.NameMask} swap a player's GameProfile for one carrying the
 * approved /setname display name. The profile name feeds the tab list, the nametag, the scoreboard
 * name, and command targeting — swapping it masks the real account name everywhere at once (the real
 * name stays in the DB as {@code username}).
 */
@Mixin(Player.class)
public interface PlayerGameProfileAccessor {

    @Mutable
    @Accessor("gameProfile")
    void aeroauth$setGameProfile(GameProfile profile);
}
