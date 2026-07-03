package com.coffeesaerosmp.auth.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Lets {@link com.coffeesaerosmp.auth.tablist.TabListManager} build UPDATE_DISPLAY_NAME packets with
 * hand-crafted entries — used for the admin-only tab overlay that shows "DisplayName (RealName)".
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoPacketAccessor {

    @Mutable
    @Accessor("entries")
    void aeroauth$setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
