package com.coffeesaerosmp.auth.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code aerosmp:core_hello} — client → server, once per join: the AeroCore version. The server uses it to refuse
 * clients without Core (or with an old one) when {@code requireAeroCore} is on, see {@code cosmetics.CoreGate}.
 * Mirror: {@code com.coffeesaerosmp.core.cosmetics.CoreHelloPayload}.
 */
public record CoreHelloPayload(String version) implements CustomPacketPayload {

    public static final Type<CoreHelloPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("aerosmp", "core_hello"));

    /** Plain buffer, not registry-aware: the channel also runs in the configuration phase (no registries there). */
    public static final StreamCodec<FriendlyByteBuf, CoreHelloPayload> STREAM_CODEC =
        StreamCodec.composite(ByteBufCodecs.stringUtf8(32), CoreHelloPayload::version, CoreHelloPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
