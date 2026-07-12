package com.coffeesaerosmp.voicecaptions;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → client caption: "{speaker} said {text}". Sent to players near the speaker so their client
 * can render the transcript above the speaker's head. {@code text} empty = clear the caption.
 */
public record CaptionPayload(UUID speaker, String text) implements CustomPacketPayload {

    public static final Type<CaptionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(CoffeesAeroVoiceCaptions.MODID, "caption"));

    public static final StreamCodec<FriendlyByteBuf, CaptionPayload> CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC,      CaptionPayload::speaker,
        ByteBufCodecs.STRING_UTF8,  CaptionPayload::text,
        CaptionPayload::new);

    @Override
    public Type<CaptionPayload> type() { return TYPE; }
}
