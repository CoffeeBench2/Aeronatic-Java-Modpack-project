package com.coffeesaerosmp.auth.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code aerosmp:wardrobe} — server → client, JSON. Everything the AeroCore Wardrobe screen needs to draw the shop
 * for ONE player: items, prices, what they own and wear, balance, rank, and particle settings for the previews.
 * The client renders all previews locally; nothing streams while the screen is open.
 *
 * <p>Registered {@code optional()} on both sides: a client without the new Core never advertises it, so the server
 * never sends it and that player gets the chest-window shop instead. The JSON schema is versioned ({@code "v"}) so
 * the two mods can evolve without a lock-step release. Mirror: {@code com.coffeesaerosmp.core.wardrobe.WardrobePayload}.
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
