package com.coffeesaerosmp.skins.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * {@code aerosmp:skin_sync} — server → client. One entry of the server's skin table: "render PLAYER
 * with these textures, cached under the DONOR's UUID". Broadcast to every modded client on each skin
 * apply/reset, and replayed in full to a client when it joins.
 *
 * <p>This channel supersedes the 1.6.5-era {@code aerosmp:self_skin} (wearer-only) channel: syncing
 * every viewer through the mod sidesteps vanilla's local-player secure-skin filter AND observer-side
 * render caches (ETF/3D-Skin-Layers style) that latch the pre-apply default skin and miss the
 * server's one-shot player-info rebroadcast.</p>
 *
 * @param playerId      the ONLINE player this skin belongs to (their offline UUID on this backend).
 * @param donorId       the profile UUID the textures belong to (real Mojang UUID for premium, the skin
 *                      donor's UUID for offline /skin). Used as the client SkinManager cache key and
 *                      guaranteed ≠ the local offline UUID, which sidesteps the local-player filter.
 * @param texturesValue base64 "textures" property value; empty string = revert to the default skin.
 */
public record SkinSyncPayload(UUID playerId, UUID donorId, String texturesValue) implements CustomPacketPayload {

    public static final Type<SkinSyncPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("aerosmp", "skin_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SkinSyncPayload> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,      SkinSyncPayload::playerId,
            UUIDUtil.STREAM_CODEC,      SkinSyncPayload::donorId,
            ByteBufCodecs.STRING_UTF8,  SkinSyncPayload::texturesValue,
            SkinSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
