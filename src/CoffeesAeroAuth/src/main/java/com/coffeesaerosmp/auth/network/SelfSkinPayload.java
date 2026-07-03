package com.coffeesaerosmp.auth.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * {@code aerosmp:self_skin} — server → client. Tells the wearer's own client what skin to render for
 * ITSELF. Needed because vanilla's {@code PlayerInfo.createSkinLookup} filters out non-"secure" skins
 * for the LOCAL player only ({@code skin.secure() || !isLocalPlayer}): on this offline-UUID backend the
 * signature can never validate against the offline profile, so the wearer sees Steve while everyone
 * else renders the skin fine. CoffeesAeroCore (client) receives this and installs its own skin lookup
 * for the local player-info entry, bypassing that filter.
 *
 * @param skinProfileId the profile UUID the textures belong to (real Mojang UUID for premium, the skin
 *                      donor's UUID for offline /skin). Used by the client's SkinManager cache key and
 *                      guaranteed ≠ the local offline UUID, which sidesteps the local-player filter.
 * @param texturesValue base64 "textures" property value; empty string = revert to the default skin.
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
