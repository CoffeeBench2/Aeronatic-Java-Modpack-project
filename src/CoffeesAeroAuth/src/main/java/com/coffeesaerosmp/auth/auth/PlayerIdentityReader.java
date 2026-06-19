package com.coffeesaerosmp.auth.auth;

import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.server.level.ServerPlayer;

/**
 * Single responsibility: read a connecting player's account type from their <em>forwarded identity</em>.
 *
 * <p>Per the project architecture, the backend performs NO premium/cracked <em>detection</em> — that
 * happens at the Velocity proxy. This reader only interprets what the proxy forwarded:</p>
 * <ul>
 *   <li>UUID version 4 → Mojang-issued → {@link Identity#PREMIUM}.</li>
 *   <li>UUID version 3 → offline name-hash → {@link Identity#OFFLINE}.</li>
 * </ul>
 *
 * <p>The forwarded UUID is only trustworthy when Velocity modern forwarding is active and the backend
 * game port is firewalled to the proxy. Until that is verified on the live stack
 * ({@link AuthConfig#TRUST_FORWARDED_UUID} = false), this reader returns {@link Identity#UNKNOWN} and
 * the flow falls back to the {@code aerosmp:player_type} plugin-message route handled elsewhere.</p>
 */
public final class PlayerIdentityReader {

    private PlayerIdentityReader() {}

    public enum Identity { PREMIUM, OFFLINE, UNKNOWN }

    /** Interprets the forwarded UUID, or {@link Identity#UNKNOWN} if forwarding is not yet trusted. */
    public static Identity read(ServerPlayer player) {
        if (!AuthConfig.TRUST_FORWARDED_UUID.get()) return Identity.UNKNOWN;
        return player.getUUID().version() == 4 ? Identity.PREMIUM : Identity.OFFLINE;
    }

    public static boolean isPremium(ServerPlayer player) {
        return read(player) == Identity.PREMIUM;
    }
}
