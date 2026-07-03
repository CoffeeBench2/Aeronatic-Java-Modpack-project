package com.coffeesaerosmp.auth.network;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers and handles the {@code aerosmp:player_type} channel used by the AeroVelocity proxy
 * to tell this backend whether a connecting player is premium (Mojang-verified) or cracked.
 *
 * <p>The channel is registered as {@code optional()} so that regular (non-proxied) clients,
 * which never advertise this channel, can still connect normally.</p>
 */
public final class AeroNetworking {

    private AeroNetworking() {}

    /**
     * Forwarding secret resolved at server start. Prefer AERO_FORWARDING_SECRET from .env
     * (per the project's "secrets in .env, never in config" rule); falls back to the
     * velocityForwardingSecret config value if .env does not set it.
     */
    private static volatile String forwardingSecret = "";

    /** Called from server startup with the loaded .env map to resolve the forwarding secret. */
    public static void initSecret(java.util.Map<String, String> env) {
        String fromEnv = env.get("AERO_FORWARDING_SECRET");
        forwardingSecret = (fromEnv != null && !fromEnv.isBlank())
            ? fromEnv.trim()
            : AuthConfig.VELOCITY_SHARED_SECRET.get();
    }

    /** Mod-bus listener — wires up the payload type + handler. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1.0.0").optional();
        registrar.playToServer(PlayerTypePayload.TYPE, PlayerTypePayload.STREAM_CODEC, AeroNetworking::handle);
        // Clientbound own-skin channel — handled by CoffeesAeroCore on the client (this mod is
        // server-only, so the handler below never runs; it exists to satisfy registration).
        registrar.playToClient(SelfSkinPayload.TYPE, SelfSkinPayload.STREAM_CODEC, (payload, ctx) -> {});
        CoffeesAeroAuth.LOGGER.info("[Net] Registered aerosmp:player_type (playToServer) + aerosmp:self_skin (playToClient), optional.");
    }

    private static void handle(PlayerTypePayload payload, IPayloadContext context) {
        // Network thread → bounce to the server thread before touching auth state.
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (CoffeesAeroAuth.AUTH_MANAGER == null) return;

            CoffeesAeroAuth.LOGGER.info("[Net] Received player_type for {} (raw=\"{}\").",
                player.getGameProfile().getName(), payload.data());

            String data    = payload.data() == null ? "" : payload.data().trim();
            String typeStr = data;
            String secret  = "";
            int idx = data.indexOf(':');
            if (idx >= 0) {
                typeStr = data.substring(0, idx);
                secret  = data.substring(idx + 1);
            }

            // Anti-spoof: if a shared secret is configured, the proxy must echo it. A cracked
            // client could otherwise send "PREMIUM" itself and skip authentication entirely.
            String configured = forwardingSecret;
            if (configured != null && !configured.isBlank() && !configured.equals(secret)) {
                CoffeesAeroAuth.LOGGER.warn(
                    "[Net] Ignored player_type for {} — missing/invalid forwarding secret (possible spoof).",
                    player.getGameProfile().getName());
                return;
            }

            boolean premium = "PREMIUM".equalsIgnoreCase(typeStr);
            CoffeesAeroAuth.AUTH_MANAGER.resolvePlayerType(player, premium);
        });
    }
}
