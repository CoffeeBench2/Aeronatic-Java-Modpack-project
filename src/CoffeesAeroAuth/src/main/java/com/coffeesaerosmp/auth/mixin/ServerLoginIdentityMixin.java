package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.auth.CookieAuth;
import com.coffeesaerosmp.auth.auth.LoginIdentity;
import com.coffeesaerosmp.auth.auth.PremiumReconnectGrace;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.cookie.ClientboundCookieRequestPacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;

/**
 * Reads the gate cookie in the LOGIN phase so a premium player's GameProfile carries their Mojang uuid
 * ({@code premiumKeepsMojangUuid}). The decision lives in {@link LoginIdentity}; this only moves bytes.
 *
 * <p>Vanilla 1.21.1, offline branch of {@code handleHello}:
 * <pre>
 *   validState(HELLO); validState(valid name); requestedUsername = name;
 *   ... else startClientVerification(UUIDUtil.createOfflineProfile(requestedUsername));
 * </pre>
 * We inject AT that {@code createOfflineProfile} call — after vanilla's own validation, and only on the
 * offline branch (never singleplayer, never online-mode) — send a cookie request, and cancel. The state
 * stays HELLO until the answer arrives, so the 600-tick slow-login timeout still applies. The client
 * always answers a cookie request (with an empty payload when it has none).
 *
 * <p>{@code handleCookieResponse} in the login phase is vanilla's "unexpected query" disconnect; we take
 * over only when WE asked, and only for our key. Everything else falls through to vanilla.
 *
 * <p>Both run on the netty thread. {@code startClientVerification} only sets two fields; the server
 * thread picks them up in {@code tick()}, exactly as it does for vanilla's own offline profile.
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginIdentityMixin {

    @Shadow @Final Connection connection;
    @Shadow String requestedUsername;

    @Shadow abstract void startClientVerification(GameProfile profile);

    @Unique private volatile boolean aeroauth$awaitingCookie;

    @Inject(method = "handleHello",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/core/UUIDUtil;createOfflineProfile(Ljava/lang/String;)Lcom/mojang/authlib/GameProfile;"),
            cancellable = true)
    private void aeroauth$askForCookie(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!aeroauth$active() || connection.isMemoryConnection()) return;
        aeroauth$awaitingCookie = true;
        connection.send(new ClientboundCookieRequestPacket(CoffeesAeroAuth.AUTH_COOKIE_KEY));
        ci.cancel();
    }

    @Inject(method = "handleCookieResponse", at = @At("HEAD"), cancellable = true)
    private void aeroauth$onCookie(ServerboundCookieResponsePacket packet, CallbackInfo ci) {
        if (!aeroauth$awaitingCookie || !CoffeesAeroAuth.AUTH_COOKIE_KEY.equals(packet.key())) return;
        aeroauth$awaitingCookie = false;
        ci.cancel();

        String name = requestedUsername;
        CookieAuth auth = CoffeesAeroAuth.COOKIE_AUTH;
        byte[] payload = packet.payload();
        CookieAuth.Verified v = (payload == null || auth == null) ? null : auth.verify(payload);
        String why = payload == null ? "no cookie" : (v == null ? "cookie invalid/expired/replay" : "");

        UUID grace = null;
        if (v == null || !name.equals(v.username())) {
            grace = PremiumReconnectGrace.check(name, aeroauth$ip());
        }
        LoginIdentity.Outcome outcome = LoginIdentity.decide(v, name, grace, why);
        UUID id = outcome.profileUuid(name);
        LoginIdentity.stash(id, outcome);
        CoffeesAeroAuth.LOGGER.info("[Login] {} -> {} {} ({}).",
            name, outcome.premium() ? "PREMIUM" : "OFFLINE", id, outcome.why());
        startClientVerification(new GameProfile(id, name));
    }

    @Unique
    private static boolean aeroauth$active() {
        return CoffeesAeroAuth.premiumKeepsMojangUuid();
    }

    /** Same formatting as {@code NetUtil.getPlayerIP}, so grace entries recorded in PLAY match here. */
    @Unique
    private String aeroauth$ip() {
        SocketAddress addr = connection.getRemoteAddress();
        if (addr instanceof InetSocketAddress inet) return inet.getAddress().getHostAddress();
        return addr == null ? null : addr.toString();
    }
}
