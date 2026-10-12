package com.coffeesaerosmp.core.wardrobe;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code aerosmp:wardrobe} — server → client JSON describing one player's shop (schema v1). Mirror of
 * CoffeesAeroAuth's {@code network.WardrobePayload}: the type id and codec must match exactly. Registered
 * {@code optional()}, so servers without it (lobby, older auth) are unaffected.
 */
public record WardrobePayload(String json) implements CustomPacketPayload {

    public static final Type<WardrobePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("aerosmp", "wardrobe"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WardrobePayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.stringUtf8(1 << 18), WardrobePayload::json, WardrobePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
