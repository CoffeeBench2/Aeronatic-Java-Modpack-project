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

        // 🔴 WHY server.execute AND NOT AsyncIo.submit — verified against the NeoForge-patched
        // PlayerList source, not assumed:
        //
        //     public void remove(ServerPlayer p) {
        //         EventHooks.firePlayerLoggedOut(p);   // <-- we are HERE
        //         ...
        //         this.save(p);                        // playerdata written AFTER us
        //         this.players.remove(p);              // still in the list while we run
        //         this.stats.remove(uuid);             // stats/advancements flushed AFTER us
        //     }
        //
        // PlayerLoggedOutEvent is the FIRST statement of remove(). So at this instant the player is
        // still in PlayerList and their data has NOT been written yet. Handing the migration to a
        // worker thread here caused two separate faults:
        //
        //   1. the both-offline gate saw them ONLINE and refused every migration — and because it
        //      raced with remove(), it refused only *sometimes*, which is worse than always;
        //   2. when the worker won the race, it moved <old>.dat onto <new>.dat and vanilla then
        //      wrote the departing player's EMPTY data over it — destroying the restored account
        //      and the original in one step, since the source had already been moved.
        //
        // Queuing onto the server thread fixes both by construction: a task submitted from inside
        // remove() cannot run until remove() has returned, so save(), players.remove() and the
        // stats/advancements flush have all completed. No polling, no sleep, no race.
        //
        // The transfer then runs ON the server thread, which is also what AccountTransfer now
        // requires. It costs one brief hitch on a rare event; the alternative is the data loss above.
        server.execute(() -> {
          // Nothing in here may throw. This body runs as a task ON THE TICK LOOP, and an escaping
          // exception there is a server crash, not a failed migration — so the whole thing is
          // wrapped and the worst case degrades to "they reconnect as a new account and the log
          // says why". AccountTransfer.plan() also throws IllegalStateException off-thread by
          // design, which makes an unguarded body here a crash waiting on a refactor.
          try {
            // PROFILE_STORE is volatile and null before init / after shutdown. Read it ONCE into a
            // local: re-reading a volatile field can return null on the second read.
            com.coffeesaerosmp.auth.db.ProfileStore store = CoffeesAeroAuth.PROFILE_STORE;
            if (store == null) {
                CoffeesAeroAuth.LOGGER.error(
                    "[Rename] profile store unavailable — {} NOT migrated. "
                  + "Run: /authmod transferaccount <oldname> {}", p.newName(), p.newName());
                return;
            }
            PlayerProfile old = store.get(p.priorUuid());
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
          } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.error(
                "[Rename] migration threw for {} — reconnect will land on a NEW account. "
              + "Run: /authmod transferaccount <oldname> {}", p.newName(), p.newName(), t);
          } finally {
            // Normally cleared by LobbyHandoff.forget() in onPlayerLeave, but the rename path
            // returns before that runs, so this entry would otherwise live until restart.
            CoffeesAeroAuth.VERIFIED_PREMIUM_UUID.remove(localUuid);
          }
        });
    }

    /** True while this session is waiting to be migrated — used to suppress the normal join flow. */
    public static boolean isPending(UUID localUuid) {
        return PENDING.containsKey(localUuid);
    }
}
