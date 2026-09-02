package com.coffeesaerosmp.auth.afk;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;

import java.util.Date;

/**
 * Disconnects a player once {@link AfkTracker} marks them AFK, and blocks re-entry for a short
 * cooling-off period.
 *
 * <h2>Why a timed ban and not just a kick</h2>
 * A bare kick is not a deterrent, it is an inconvenience — the client reconnects in three seconds
 * and the AFK farm carries on. Worse, on this server every rejoin costs a full login round trip
 * through the gate and a profile load, so a kick that is instantly undone is <b>more</b> load than
 * leaving the player standing there. A short ban is what makes the kick actually free a slot.
 *
 * <h2>Why vanilla's ban list rather than our own</h2>
 * {@link UserBanList} already does everything needed and does it correctly:
 * <ul>
 *   <li>It is checked in {@code PlayerList.canPlayerLogin}, which the transfer-gate path still goes
 *       through — the gate hands the client to this server, it does not bypass login.</li>
 *   <li>Entries carry an <b>expiry date</b> and vanilla purges them itself, so nothing here has to
 *       run a timer or clean up after a restart.</li>
 *   <li>🔑 <b>It is keyed on UUID</b> ({@code UserBanList.getKeyForUser} returns
 *       {@code profile.getId()}), not on name. That matters enormously here: NameMask swaps the
 *       {@code GameProfile} for one named after the display name, so a name-keyed ban would miss
 *       the player entirely, and on an {@code online-mode=false} server whose profile cache
 *       fabricates unknown names it could even land on a phantom account.</li>
 * </ul>
 *
 * <h2>Who is exempt, and why</h2>
 * <ul>
 *   <li><b>Ops</b> (permission ≥ 2) by default — an admin parked in spectator watching a chunk, or
 *       idling while reading a log, is doing their job. Configurable.</li>
 *   <li><b>Anyone not yet authenticated</b>, i.e. still in the lobby. They are mid-login by
 *       definition; banning someone for being slow to type a password would be absurd, and it
 *       would fight the auth timeout that already handles that case.</li>
 *   <li><b>Anyone the ban list already holds</b>, so a manual ban is never silently overwritten
 *       with a five-minute one.</li>
 * </ul>
 *
 * <h2>⚠ Accepted limits</h2>
 * This inherits {@link AfkTracker}'s definition of idle, including the two gaps documented there:
 * a physical mouse jiggler defeats it, and a player standing on a moving Sable ship is not a
 * vanilla passenger so their position genuinely changes. Neither is made worse by kicking; they
 * simply are not caught in the first place.
 *
 * <p>Every failure path here is <b>fail-open</b>: if anything throws, the player stays connected.
 * A bug in an idle timer must never be able to remove somebody from the server.
 */
public final class AfkKick {

    private AfkKick() {}

    private static final String SOURCE = "AFK auto-kick";

    /**
     * Kicks and short-bans {@code player} if the feature is on and they are not exempt.
     * Must be called on the server thread — {@link AfkTracker#onServerTick} already is.
     */
    public static void consider(ServerPlayer player) {
        try {
            if (!AuthConfig.AFK_KICK_ENABLED.get()) return;
            if (player == null || player.server == null) return;
            if (isExempt(player)) return;

            int minutes = AuthConfig.AFK_KICK_BAN_MINUTES.get();
            apply(player, minutes);

        } catch (Exception e) {
            // Never let this remove a player by accident, and never let it break the tick loop.
            CoffeesAeroAuth.LOGGER.warn("[AFK] auto-kick skipped for {}: {}",
                player == null ? "?" : player.getGameProfile().getName(), e.toString());
        }
    }

    private static boolean isExempt(ServerPlayer player) {
        if (AuthConfig.AFK_KICK_EXEMPT_OPS.get() && player.hasPermissions(2)) return true;

        // Not through the login flow yet — they are in the lobby, not idling in the world.
        if (CoffeesAeroAuth.AUTH_MANAGER != null
                && !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) return true;

        // Already banned by a human. Do not clobber that with a 5-minute entry.
        UserBanList bans = player.server.getPlayerList().getBans();
        return bans.isBanned(player.getGameProfile());
    }

    private static void apply(ServerPlayer player, int minutes) {
        GameProfile profile = player.getGameProfile();
        Component reason = Component.literal(
            "§eYou were idle for " + AuthConfig.AFK_TIMEOUT_MINUTES.get() + " minutes.\n\n"
            + "§7This is an automatic AFK kick to keep the server running smoothly —\n"
            + "§7you are not in trouble and nothing has been taken from you.\n\n"
            + "§7You can rejoin in §f" + minutes + " minute" + (minutes == 1 ? "" : "s") + "§7.");

        if (minutes > 0) {
            Date expires = new Date(System.currentTimeMillis() + minutes * 60_000L);
            // Plain text for the ban file — the disconnect screen gets the formatted version above.
            String stored = "Idle for " + AuthConfig.AFK_TIMEOUT_MINUTES.get()
                + " min — automatic AFK kick. Rejoin after " + expires + ".";
            player.server.getPlayerList().getBans()
                .add(new UserBanListEntry(profile, null, SOURCE, expires, stored));
        }

        player.connection.disconnect(reason);

        CoffeesAeroAuth.LOGGER.info("[AFK] Auto-kicked {} (idle {} min); rejoin blocked for {} min.",
            profile.getName(), AuthConfig.AFK_TIMEOUT_MINUTES.get(), minutes);
    }
}
