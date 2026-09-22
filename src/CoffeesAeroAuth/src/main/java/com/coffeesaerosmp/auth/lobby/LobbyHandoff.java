package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundStoreCookiePacket;
import net.minecraft.network.protocol.common.ClientboundTransferPacket;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * The standalone lobby's exit: hands an authenticated player to the SMP.
 *
 * <h2>Why a re-signed cookie and not a new trust mechanism</h2>
 * The gate already proves identity to a backend by signing a cookie with {@code AERO_GATE_SECRET}
 * and letting the backend verify it. The lobby holds that same secret, so once IT has verified the
 * gate's cookie it can re-sign the same decision for the SMP. The SMP then verifies a handoff
 * through the exact code path it already uses for the gate — {@code CoffeesAeroAuth.handleAuthCookie}
 * — and needs <b>no changes at all</b>. One trust mechanism, already in production, twice.
 *
 * <h2>Order of operations matters</h2>
 * {@code StoreCookie} must reach the client BEFORE {@code Transfer}. Cookies are the vanilla
 * mechanism for carrying state across a transfer, but only if the client has the cookie when it
 * reconnects. The transfer itself is a real disconnect/reconnect — the client tears down this
 * connection and dials the SMP itself, which is why {@code handoffHost} has to be the address a
 * PLAYER can reach, not an internal one.
 *
 * <h2>🔴 The UUID trap</h2>
 * On an offline-mode backend {@code player.getUUID()} is derived from the username (2d1532de…) while
 * the gate verified a real Mojang UUID (98b33d4e…). The receiving server passes the cookie's UUID to
 * {@code SkinsHook.applyPremium} to fetch the real skin, so signing the local UUID gives every
 * transferred premium player a broken skin. We re-sign
 * {@link CoffeesAeroAuth#VERIFIED_PREMIUM_UUID}, and refuse to claim premium if we do not have it.
 *
 * <h2>What this deliberately does NOT do</h2>
 * It does not pay first-join grants, restore the inventory stash, or touch gameplay state. Those
 * stay the SMP's job and run on the SMP's own entry path, unchanged. The lobby is an identity door.
 */
public final class LobbyHandoff {

    private LobbyHandoff() {}

    /** True when this process is configured as the standalone lobby. Anything unknown means SMP. */
    public static boolean isLobbyRole() {
        try {
            return "LOBBY".equalsIgnoreCase(AuthConfig.SERVER_ROLE.get().trim());
        } catch (Exception e) {
            return false;   // config not loaded / bad value -> behave as the SMP
        }
    }

    /**
     * Attempts to hand {@code player} to the SMP.
     *
     * @return {@code true} if the transfer was sent and the caller must stop — the player is leaving
     *         this server, so nothing downstream (teleport, stash restore, grants) may run.
     */
    public static boolean tryTransfer(ServerPlayer player) {
        if (!isLobbyRole()) return false;

        boolean enabled;
        try { enabled = AuthConfig.HANDOFF_ENABLED.get(); } catch (Exception e) { enabled = false; }
        if (!enabled) {
            deny(player, "§eThe main server is not accepting arrivals right now. Please try again shortly.");
            return true;    // still consumed: a LOBBY must never fall through to a local teleport
        }

        // The SMP is confirmed down (N consecutive failed polls) - hold them here rather than
        // transferring them into a dead port, which would disconnect them to a connection error.
        // Checked BEFORE the liveness ping on purpose. An SLP goes green the moment the port binds,
        // which on a 40 GB world is minutes before anyone should actually be let in — so an admin's
        // explicit lock has to outrank it, or players get readmitted into a server that is still
        // loading and the lock achieves nothing.
        if (LockdownState.isLocked()) {
            String why = LockdownState.reason();
            deny(player, "§e⏳ Survival is still under process."
                + (why == null || why.isBlank() ? "" : " §7(" + why + ")")
                + "\n§7Stay here — an admin will open it back up shortly.");
            return true;
        }

        if (!SmpLiveness.isUp()) {
            deny(player, "§e⏳ The survival server is restarting. Hang on here — you will be let in automatically.");
            return true;
        }

        String host;
        int port;
        try {
            host = AuthConfig.HANDOFF_HOST.get().trim();
            port = AuthConfig.HANDOFF_PORT.get();
        } catch (Exception e) {
            host = ""; port = 0;
        }
        if (host.isEmpty()) {
            CoffeesAeroAuth.LOGGER.error(
                "[Handoff] serverRole=LOBBY but handoffHost is blank — {} cannot be sent anywhere. "
                + "Set handoffHost/handoffPort to the SMP's PUBLIC address.", player.getGameProfile().getName());
            deny(player, "§cThis lobby is misconfigured and cannot send you through. Tell an admin.");
            return true;
        }

        var cookieAuth = CoffeesAeroAuth.COOKIE_AUTH;
        if (cookieAuth == null || !cookieAuth.enabled()) {
            CoffeesAeroAuth.LOGGER.error(
                "[Handoff] AERO_GATE_SECRET is not set on the lobby — cannot sign a handoff cookie for {}. "
                + "Without it the SMP would resolve them OFFLINE, silently demoting premium players.",
                player.getGameProfile().getName());
            deny(player, "§cThis lobby is misconfigured and cannot send you through. Tell an admin.");
            return true;
        }

        // Premium is claimed ONLY when we hold the gate-verified Mojang UUID. Claiming it with the
        // local UUID would hand the SMP a premium identity whose skin lookup then fails — worse than
        // arriving as offline, because it looks like it worked.
        UUID mojang = CoffeesAeroAuth.VERIFIED_PREMIUM_UUID.get(player.getUUID());
        boolean premium = mojang != null;
        UUID signUuid = premium ? mojang : player.getUUID();

        if (!premium && isProfilePremium(player)) {
            CoffeesAeroAuth.LOGGER.warn(
                "[Handoff] {} is PREMIUM in the profile store but this session has no gate-verified "
                + "Mojang UUID — handing off as OFFLINE. They will keep their account, but the real "
                + "Mojang skin will not apply until they reconnect through the gate.",
                player.getGameProfile().getName());
        }

        int ttl;
        try { ttl = AuthConfig.HANDOFF_COOKIE_TTL_SECONDS.get(); } catch (Exception e) { ttl = 30; }

        byte[] cookie = cookieAuth.sign(premium, signUuid,
            player.getGameProfile().getName(), ttl * 1000L);
        if (cookie == null) {
            CoffeesAeroAuth.LOGGER.error("[Handoff] Failed to sign a handoff cookie for {}.",
                player.getGameProfile().getName());
            deny(player, "§cCould not hand you through. Tell an admin.");
            return true;
        }

        try {
            // StoreCookie FIRST — the client must be holding it before it reconnects.
            player.connection.send(new ClientboundStoreCookiePacket(CoffeesAeroAuth.AUTH_COOKIE_KEY, cookie));
            player.connection.send(new ClientboundTransferPacket(host, port));
            CoffeesAeroAuth.LOGGER.info("[Handoff] {} -> {}:{} as {} (cookie ttl {}s).",
                player.getGameProfile().getName(), host, port, premium ? "PREMIUM" : "OFFLINE", ttl);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Handoff] Transfer failed for {}: {}",
                player.getGameProfile().getName(), e.toString());
            deny(player, "§cCould not hand you through. Tell an admin.");
        }
        return true;
    }

    /**
     * The reverse trip: SMP → standalone lobby, for {@code /lobby}.
     *
     * <p>🔑 <b>It must sign a cookie, not just transfer.</b> A player who arrives at the lobby with
     * no cookie resolves OFFLINE — that is the anti-spoof rule, verified live 2026-09-07 — so a naive
     * transfer would silently strip premium status from everyone who used this command. The SMP holds
     * the same {@code AERO_GATE_SECRET} and has the verified Mojang UUID from their own arrival, so
     * it can re-sign exactly as the lobby does in the other direction.
     *
     * @return true if the transfer was sent; false if unconfigured or unavailable, in which case the
     *         caller should fall back to local behaviour rather than leaving the player with nothing.
     */
    public static boolean returnToLobby(ServerPlayer player) {
        String host;
        int port;
        try {
            host = AuthConfig.LOBBY_RETURN_HOST.get().trim();
            port = AuthConfig.LOBBY_RETURN_PORT.get();
        } catch (Exception e) {
            return false;
        }
        if (host.isEmpty()) return false;                 // not configured — caller keeps old behaviour

        var cookieAuth = CoffeesAeroAuth.COOKIE_AUTH;
        if (cookieAuth == null || !cookieAuth.enabled()) {
            CoffeesAeroAuth.LOGGER.error(
                "[Handoff] Cannot send {} back to the lobby — AERO_GATE_SECRET is not set, so they "
                + "would arrive as OFFLINE. Refusing rather than demoting them.",
                player.getGameProfile().getName());
            deny(player, "§cCannot send you to the lobby right now. Tell an admin.");
            return true;                                  // consumed: do NOT fall through
        }

        UUID mojang = CoffeesAeroAuth.VERIFIED_PREMIUM_UUID.get(player.getUUID());
        boolean premium = mojang != null;
        int ttl;
        try { ttl = AuthConfig.HANDOFF_COOKIE_TTL_SECONDS.get(); } catch (Exception e) { ttl = 30; }

        byte[] cookie = cookieAuth.sign(premium, premium ? mojang : player.getUUID(),
            player.getGameProfile().getName(), ttl * 1000L);
        if (cookie == null) {
            deny(player, "§cCould not send you to the lobby. Tell an admin.");
            return true;
        }
        try {
            player.connection.send(new ClientboundStoreCookiePacket(CoffeesAeroAuth.AUTH_COOKIE_KEY, cookie));
            player.connection.send(new ClientboundTransferPacket(host, port));
            CoffeesAeroAuth.LOGGER.info("[Handoff] {} -> lobby {}:{} as {} (return trip).",
                player.getGameProfile().getName(), host, port, premium ? "PREMIUM" : "OFFLINE");
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Handoff] Return transfer failed for {}: {}",
                player.getGameProfile().getName(), e.toString());
            deny(player, "§cCould not send you to the lobby. Tell an admin.");
        }
        return true;
    }

    /** Clears the per-session gate state. Called on logout so neither collection grows unbounded.
     *
     *  <p>🔴 GATE_VERIFIED must be cleared here too. It is what lets an offline arrival skip /login,
     *  so a stale entry would mean a later session for the same UUID — one that arrived with no
     *  cookie at all — inheriting a bypass it never earned. */
    public static void forget(UUID playerUuid) {
        CoffeesAeroAuth.VERIFIED_PREMIUM_UUID.remove(playerUuid);
        CoffeesAeroAuth.GATE_VERIFIED.remove(playerUuid);
    }

    private static boolean isProfilePremium(ServerPlayer player) {
        try {
            if (CoffeesAeroAuth.PROFILE_STORE == null) return false;
            PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID());
            return p != null && p.getAccountType() == PlayerProfile.AccountType.PREMIUM;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deny(ServerPlayer player, String message) {
        try {
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX + message));
        } catch (Exception ignored) {}
    }
}
