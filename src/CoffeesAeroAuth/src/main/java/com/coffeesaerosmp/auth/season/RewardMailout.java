package com.coffeesaerosmp.auth.season;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.mail.MailService;
import net.minecraft.server.MinecraftServer;

/**
 * At SMP start, mail every one-time season grant to everyone who is owed one — online or not, staff
 * included (owner 2026-10-07: "start the reward system now, even for admins and offline players").
 *
 * <p>Before this, the starter kit and the veteran reward were only paid on a player's first world
 * entry, so anyone who had not entered yet (or entered by a path that skipped the grant) had nothing
 * waiting. Now the mail is already in their box when they arrive.
 *
 * <p><b>Idempotent, safe on every boot.</b> The welcome kit uses the SAME dedupe key as the join path
 * ({@code welcome:s<season>:<uuid>}), so it can never be sent twice whichever path runs first; and it
 * deliberately does NOT set {@code startupBonusGiven} — that flag also drives the first-arrival banner,
 * so it is left for the join path, which then finds the mail already stored (a dedupe no-op). Veteran
 * rewards are claimed as each mail is stored, so they leave the owed set after one boot.
 */
public final class RewardMailout {
    private RewardMailout() {}

    public static void run(MinecraftServer server) {
        try {
            if (LobbyHandoff.isLobbyRole()) return;                 // one-time grants are the SMP's job
            if (!AuthConfig.SEASON_GRANT_REWARDS.get()) return;     // test/creative server: nothing to grant
            if (!MailService.enabled() || CoffeesAeroAuth.PROFILE_STORE == null) {
                CoffeesAeroAuth.LOGGER.warn("[Rewards] Startup mail-out skipped: mail or the profile store is unavailable. "
                    + "Owed grants are still paid on each player's next world entry.");
                return;
            }
            int spurs = AuthConfig.STARTUP_BONUS_SPURS.get();
            int kits = 0;
            for (PlayerProfile p : CoffeesAeroAuth.PROFILE_STORE.getAll()) {
                if (p.startupBonusGiven) continue;
                String name = p.displayName != null && !p.displayName.isBlank() ? p.displayName : p.username;
                if (MailService.sendSeasonWelcomeTo(server, p.getUUID(), name, spurs)) kits++;
            }
            int vets = VeteranReward.mailAllOwed(server);
            CoffeesAeroAuth.LOGGER.info("[Rewards] Startup mail-out: {} starter kit(s) and {} veteran reward(s) queued "
                + "(already-delivered ones are dedupe no-ops).", kits, vets);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Rewards] Startup mail-out failed — grants stay owed and are paid on world entry", e);
        }
    }
}
