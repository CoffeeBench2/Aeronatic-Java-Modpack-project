package com.coffeesaerosmp.core.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * {@code aerosmp:self_skin} — server → client, sent by CoffeesAeroAuth on the backend. Carries the
 * skin the LOCAL player should render for itself. Must stay wire-identical to the server-side copy in
 * CoffeesAeroAuth ({@code com.coffeesaerosmp.auth.network.SelfSkinPayload}).
 *
 * <p>Why this exists: the backend runs offline-mode, so the local player's textures can never be
 * "secure", and vanilla's {@code PlayerInfo.createSkinLookup} filters insecure skins for the local
 * player only — everyone else sees your skin, you see Steve. {@link com.coffeesaerosmp.core.client.SelfSkinHandler}
 * installs a custom skin lookup that bypasses that filter.</p>
 *
 * @param skinProfileId profile UUID the textures belong to (≠ local offline UUID by construction)
 * @param texturesValue base64 "textures" property value; empty = revert to default skin
 */
public record SelfSkinPayload(UUID skinProfileId, String texturesValue) implements CustomPacketPayload {

    public static final Type<SelfSkinPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("aerosmp", "self_skin"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelfSkinPayload> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,      SelfSkinPayload::skinProfileId,
            ByteBufCodecs.STRING_UTF8,  SelfSkinPayload::texturesValue,
            SelfSkinPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
