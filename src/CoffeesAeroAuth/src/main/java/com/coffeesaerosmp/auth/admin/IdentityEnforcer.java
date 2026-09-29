package com.coffeesaerosmp.auth.admin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.admin.IdentityGate.Verdict;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.watchdog.Severity;
import com.coffeesaerosmp.auth.watchdog.WatchdogEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The side-effecting half of {@link IdentityGate}: reads the profile, applies the verdict, tells the
 * player and staff. Kept out of {@code AuthManager} so the join path gains one call, not a page.
 */
public final class IdentityEnforcer {

    private IdentityEnforcer() {}

    private static final long ALERT_COOLDOWN_MS = 10 * 60_000L;
    /** Per profile. A refused player reconnecting in a loop must not flood the Discord webhook — the
     *  movement watchdog once did exactly that and took chat delivery down with it. */
    private static final Map<UUID, Long> lastAlert = new ConcurrentHashMap<>();

    public static boolean enforcing() {
        try { return AuthConfig.IDENTITY_GATE_ENFORCE.get(); }
        catch (Exception e) { return true; }   // unreadable config fails CLOSED
    }

    /** The gate's inputs, from the profile getOrCreate just refreshed from MySQL. */
    public static Verdict judge(PlayerProfile profile, boolean isNew, boolean premium, UUID mojangUuid,
                                boolean dbBacked) {
        IdentityGate.Stored stored = isNew ? null : new IdentityGate.Stored(
            profile.accountType, profile.mojangLink, profile.identityHold,
            profile.passwordHash != null && !profile.passwordHash.isBlank(),
            profile.totalPlaytimeSeconds);
        IdentityGate.Arrival arrival = premium ? IdentityGate.Arrival.premium(mojangUuid)
                                               : IdentityGate.Arrival.offline();
        return IdentityGate.decide(arrival, stored, dbBacked);
    }

    /**
     * Refuse the login. Safe at this point in the join: the player has been frozen in AWAITING_TYPE since
     * they arrived, so the profile's playerdata was loaded but never touched, and vanilla writing it back
     * on disconnect writes back exactly what was there.
     */
    public static void refuse(ServerPlayer player, PlayerProfile profile, Verdict v, UUID arriving) {
        String name = player.getGameProfile().getName();
        CoffeesAeroAuth.LOGGER.error("[Identity] REFUSED {} ({}) on profile {} — {}. Stored link {} ({}), hold {}.",
            name, arriving, profile.getUUID(), v, profile.mojangLink, profile.linkSource, profile.identityHold);
        alert(profile, v, arriving, true);
        player.connection.disconnect(Component.literal(message(v)));
    }

    /** Alert-only mode: the verdict would have refused, the login proceeds, staff are told. */
    public static void observe(PlayerProfile profile, Verdict v, UUID arriving) {
        CoffeesAeroAuth.LOGGER.error("[Identity] WOULD REFUSE (identityGateEnforce=false) profile {} — {}; "
            + "arriving {}.", profile.getUUID(), v, arriving);
        alert(profile, v, arriving, false);
    }

    static String message(Verdict v) {
        String contact = "\n§7If this is your account, contact staff on Discord.";
        return switch (v) {
            case DENY_MISMATCH -> "§cThis profile belongs to a different Minecraft account."
                + "\n§7The name was used here by someone else before you." + contact;
            case DENY_HELD -> "§cThis profile is on hold while staff verify who owns it." + contact;
            case DENY_UNCLAIMABLE -> "§cThis name has an existing offline account with no password,"
                + "\n§cso it cannot be claimed automatically." + contact;
            case DENY_UNVERIFIABLE -> "§eWe can't verify account ownership right now."
                + "\n§7Please try again in a few minutes.";
            case CLAIM_REQUIRED -> "§eLog in through the lobby to claim this account.";
            case ALLOW -> "";
        };
    }

    private static void alert(PlayerProfile profile, Verdict v, UUID arriving, boolean refused) {
        if (v == Verdict.DENY_UNVERIFIABLE) return;   // an outage, not an incident — the log has it
        long now = System.currentTimeMillis();
        Long last = lastAlert.get(profile.getUUID());
        if (last != null && now - last < ALERT_COOLDOWN_MS) return;
        lastAlert.put(profile.getUUID(), now);
        if (lastAlert.size() > 512) lastAlert.clear();

        if (CoffeesAeroAuth.WATCHDOG == null) return;
        try {
            CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(Severity.HIGH,
                refused ? "Identity gate refused a login" : "Identity gate WOULD refuse (alert-only mode)",
                refused ? "Login refused; profile untouched. Resolve with /aeroid."
                        : "identityGateEnforce=false, so the login was ALLOWED.",
                "Profile",         String.valueOf(profile.username),
                "Profile uuid",    String.valueOf(profile.getUUID()),
                "Verdict",         v.name(),
                "Linked Mojang",   profile.mojangLink == null ? "(none)" : profile.mojangLink,
                "Link source",     profile.linkSource == null ? "(none)" : profile.linkSource,
                "Hold",            profile.identityHold == null ? "(none)" : profile.identityHold,
                "Arriving Mojang", String.valueOf(arriving),
                "Check",           "https://api.mojang.com/users/profiles/minecraft/" + profile.username));
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[Identity] alert failed: {}", t.toString());
        }
    }

    /**
     * The old offline password was just given for a profile a premium login arrived on: the same human
     * bought the game. Flip the account to premium and bind the Mojang account, so every later login is
     * checked against it and never asks for the password again.
     */
    public static void completeClaim(ServerPlayer player, PlayerProfile profile, UUID mojangUuid) {
        profile.accountType  = PlayerProfile.AccountType.PREMIUM.name();
        profile.nameApproved = true;
        profile.mojangLink   = mojangUuid.toString();
        CoffeesAeroAuth.PROFILE_STORE.save(profile);
        // Guarded bind (only-if-NULL); runs after the save above on the single AsyncIo thread.
        AccountTransfer.rememberMojangUuid(profile.getUUID(), mojangUuid);
        com.coffeesaerosmp.auth.compat.SkinsHook.applyPremium(player, mojangUuid);
        CoffeesAeroAuth.LOGGER.info("[Identity] {} CLAIMED offline profile {} with its password — now "
            + "premium, linked to {}.", player.getGameProfile().getName(), profile.getUUID(), mojangUuid);
        if (CoffeesAeroAuth.WATCHDOG != null) {
            try {
                CoffeesAeroAuth.WATCHDOG.alert(WatchdogEvent.of(Severity.LOW,
                    "Offline account claimed by premium login",
                    "Old password verified; account upgraded and linked.",
                    "Profile", String.valueOf(profile.username),
                    "Mojang",  mojangUuid.toString()));
            } catch (Throwable ignored) {}
        }
    }
}
