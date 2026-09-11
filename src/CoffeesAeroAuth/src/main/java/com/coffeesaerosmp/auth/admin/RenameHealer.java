package com.coffeesaerosmp.auth.admin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Automatic recovery for a PREMIUM player who changed their Minecraft name.
 *
 * <h3>Why a rename loses everything here</h3>
 * The backend is {@code online-mode=false} and the gate transfers clients to it directly, so the
 * server mints every uuid as {@code md5("OfflinePlayer:" + name)} — <b>derived from the name</b>.
 * Rename, and you are a different player: new profile row, new {@code playerdata/*.dat}, new
 * advancements, new stats. Nothing follows you.
 *
 * <h3>How this detects it — and why it cannot be wrong</h3>
 * A Mojang uuid never changes. {@link AccountTransfer#rememberMojangUuid} stamps it onto the profile
 * on every gate-verified premium join, so a row filed under this Mojang uuid but a DIFFERENT local
 * uuid is <i>proof</i> the same human is back under a new name. No name similarity, no IP guessing,
 * no timing window — an identity the player cannot forge, because it arrives inside the gate's
 * HMAC-signed cookie.
 *
 * <h3>Why it disconnects instead of migrating in place</h3>
 * 🔴 By the time the cookie is read, the player object already exists and vanilla holds their
 * freshly-created (empty) {@code .dat} open, rewriting it on disconnect. Moving files under a live
 * player is silently undone — the failure mode is "it worked in testing and lost someone's base in
 * production". Ending the session first makes the migration a plain offline file move, which is the
 * one shape that is reliable. The cost is a single reconnect.
 *
 * <h3>Failure posture</h3>
 * If anything goes wrong the player is simply left as a new account and an admin is told — the old
 * account is never deleted, only re-keyed, and the transfer rolls back as a unit. There is no state
 * where both identities are half-populated.
 */
public final class RenameHealer {

    /** Local uuid → the older identity to fold in, recorded while we wait for them to drop. */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private record Pending(UUID priorUuid, String newName, long at) {}

    private RenameHealer() {}

    /**
     * Disconnect the player with an explanation and queue the migration for once they are gone.
     *
     * @param player   the session that just proved, via the gate cookie, that it is a renamed account
     * @param priorId  the offline uuid this Mojang account used before the rename
     * @param newName  the name they are logging in with now
     */
    public static void scheduleFor(ServerPlayer player, UUID priorId, String newName) {
        PlayerProfile old = CoffeesAeroAuth.PROFILE_STORE == null ? null
                : CoffeesAeroAuth.PROFILE_STORE.get(priorId);
        String oldName = old != null ? old.username : "your previous name";

        PENDING.put(player.getUUID(), new Pending(priorId, newName, System.currentTimeMillis()));
        CoffeesAeroAuth.LOGGER.info(
            "[Rename] {} is {} renamed (mojang identity matches profile {}). Disconnecting to migrate.",
            newName, oldName, priorId);

        player.connection.disconnect(Component.literal(
            "§6§lName change detected\n\n"
          + "§7Welcome back, §f" + newName + "§7.\n"
          + "§7We found your account under §f" + oldName + "§7 and are moving\n"
          + "§7everything across — inventory, playtime, level and progress.\n\n"
          + "§a§lPlease reconnect in a few seconds.§r\n"
          + "§8Nothing has been lost. If anything looks wrong, tell an admin."));
    }

    /**
     * Called once the player has actually left. Runs the migration off the server thread.
     *
     * <p>Hooked from the disconnect path rather than a timer: the file move is only safe after
     * vanilla has finished writing their data out, and "they are gone" is the only reliable signal
     * for that.
     */
    public static void onDisconnect(MinecraftServer server, UUID localUuid) {
        Pending p = PENDING.remove(localUuid);
        if (p == null) return;

        com.coffeesaerosmp.auth.util.AsyncIo.submit(() -> {
            PlayerProfile old = CoffeesAeroAuth.PROFILE_STORE.get(p.priorUuid());
            if (old == null) {
                CoffeesAeroAuth.LOGGER.warn("[Rename] prior profile {} vanished — nothing migrated.",
                                            p.priorUuid());
                return;
            }
            // execute() re-runs its own preflight, including the both-offline check, so a player who
            // reconnected faster than this ran is caught there rather than corrupted here.
            AccountTransfer.Result r = AccountTransfer.execute(server, old.username, p.newName());
            if (r.ok()) {
                CoffeesAeroAuth.LOGGER.info("[Rename] {} → {} migrated successfully.",
                                            old.username, p.newName());
                AccountTransfer.rememberMojangUuid(
                    AccountTransfer.offlineUuid(p.newName()),
                    CoffeesAeroAuth.VERIFIED_PREMIUM_UUID.getOrDefault(localUuid, null));
                for (String line : r.lines()) CoffeesAeroAuth.LOGGER.info("[Rename]   {}", line);
            } else {
                // Loud, and actionable: the player is about to reconnect into an empty account and
                // will report it, so the log must already say why.
                CoffeesAeroAuth.LOGGER.error(
                    "[Rename] MIGRATION REFUSED for {} → {}: {}  "
                  + "Run: /authmod transferaccount {} {}",
                    old.username, p.newName(),
                    r.lines().isEmpty() ? "(no reason given)" : r.lines().get(0),
                    old.username, p.newName());
            }
        });
    }

    /** True while this session is waiting to be migrated — used to suppress the normal join flow. */
    public static boolean isPending(UUID localUuid) {
        return PENDING.containsKey(localUuid);
    }
}
