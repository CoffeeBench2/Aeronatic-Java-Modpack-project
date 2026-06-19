package com.coffeesaerosmp.auth.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;

/**
 * Serverbound payload sent by the AeroVelocity proxy on the {@code aerosmp:player_type}
 * channel right after a player connects to this backend. The body is the raw UTF-8 string
 * {@code "PREMIUM"} or {@code "CRACKED"}, optionally suffixed with {@code ":<sharedSecret>"}.
 *
 * <p>Because forwarding mode is NONE, every player reaches the backend with an offline (v3)
 * UUID, so the backend cannot tell premium from cracked on its own. This message carries the
 * decision the proxy already made via Mojang authentication.</p>
 *
 * <p>The codec reads/writes the bytes verbatim (no length prefix) so it matches the raw
 * payload Velocity's {@code sendPluginMessage} puts on the wire.</p>
 */
public record PlayerTypePayload(String data) implements CustomPacketPayload {

    public static final Type<PlayerTypePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath("aerosmp", "player_type"));

    public static final StreamCodec<FriendlyByteBuf, PlayerTypePayload> STREAM_CODEC =
        new StreamCodec<>() {
            @Override
            public PlayerTypePayload decode(FriendlyByteBuf buf) {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return new PlayerTypePayload(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public void encode(FriendlyByteBuf buf, PlayerTypePayload payload) {
                buf.writeBytes(payload.data().getBytes(StandardCharsets.UTF_8));
            }
        };

    @Override
    public Type<PlayerTypePayload> type() {
        return TYPE;
    }
}
