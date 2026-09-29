package com.coffeesaerosmp.auth.protect;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * Clears a STUCK invulnerability flag out of a player's saved data, once, at join.
 *
 * <h2>The bug this repairs</h2>
 * Something set {@code Invulnerable} on players and vanilla then persisted it. That tag is written
 * by {@code Entity.addAdditionalSaveData} and read back by {@code Entity.load}, so it lives in
 * {@code playerdata/<uuid>.dat} and <b>survives every relog, restart and world load</b> — the same
 * shape of problem as the plot-space position [[PlotGuard]] exists for: bad state that re-arms
 * itself from disk every login.
 *
 * <p>What it does to the player, from {@code Entity.isInvulnerableTo}:
 * <pre>
 *   this.invulnerable &amp;&amp; !source.is(BYPASSES_INVULNERABILITY) &amp;&amp; !source.isCreativePlayer()
 * </pre>
 * i.e. immune to mobs, fall, fire, drowning, PvP — everything except {@code /kill}-class damage and
 * a creative player's hit. An unkillable survival player.
 *
 * <h2>🔴 Why this is a mod hook and not a datapack</h2>
 * <b>A datapack cannot write player NBT.</b> Verified in the 1.21.1 source, not from memory —
 * {@code net.minecraft.server.commands.data.EntityDataAccessor.setData} opens with:
 * <pre>
 *   if (this.entity instanceof Player) { throw ERROR_NO_PLAYERS.create(); }
 * </pre>
 * so {@code /data modify|merge entity &lt;player&gt; …} fails with "Unable to modify player data" for
 * every player, always. {@code getData()} carries no such guard, which is why a datapack can still
 * DETECT this ({@code execute if data entity @s {Invulnerable:1b}}) — see the companion datapack
 * {@code aero-invuln-audit}, which reports but cannot repair. The only vanilla lever that clears the
 * flag is death: {@code PlayerList.respawn} builds a fresh {@code ServerPlayer} and
 * {@code restoreFrom} does not copy it. Killing players to fix a bug we caused is not a fix.
 *
 * <h2>Why join, and why only ever clearing</h2>
 * Join is the one moment the stale value is guaranteed to be loaded and the player is guaranteed not
 * to be mid-anything. Running it on a tick would be a permanent tax for a one-off repair, and — more
 * importantly — would fight any admin or mod that sets invulnerability deliberately at runtime.
 * <b>This never sets the flag, only clears it, and only at join.</b> Normal gameplay cannot be
 * affected by a handler that runs once per login and whose common path is one boolean read.
 *
 * <h2>The creative/spectator exemption is not the obvious one</h2>
 * ⚠️ Creative mode does <b>NOT</b> use this flag. It uses {@code abilities.invulnerable}, a
 * different field in a different NBT compound. Confirmed against this server's own playerdata: the
 * two flagged accounts in the 08-25 snapshot both read {@code Invulnerable=0} with
 * {@code abilities.invulnerable=1} — one creative, one spectator. So skipping creative/spectator
 * here costs nothing and is pure belt-and-braces; the repair for THOSE accounts, if one is ever
 * needed, is the abilities half below.
 */
public final class InvulnerableRepair {

    private InvulnerableRepair() {}

    /** Players repaired since boot, for the log line and {@code /authmod status}. */
    private static volatile int repaired = 0;

    public static int repairedCount() { return repaired; }

    /**
     * Called at the very top of the join handler, before anything that can kick or return early.
     *
     * <p>Defensive on config exactly like {@link PlotGuard}: this runs during a login handshake, and
     * a SERVER config that has not finished loading throws "Cannot get config value before config is
     * loaded" — that exact throw during a join is what crashed every connecting client on
     * 2026-07-20. Default to ON if the config cannot be read; the repair is safe, being unable to
     * damage a player is not.
     */
    public static void onJoin(ServerPlayer player) {
        boolean enabled;
        try { enabled = AuthConfig.CLEAR_STUCK_INVULNERABLE.get(); } catch (Exception e) { enabled = true; }
        if (!enabled) return;

        try {
            GameType mode = player.gameMode.getGameModeForPlayer();
            if (mode == GameType.CREATIVE || mode == GameType.SPECTATOR) return;

            // The common path for a healthy player: one boolean read, then out.
            boolean entityFlag = player.isInvulnerable();
            boolean abilityFlag = player.getAbilities().invulnerable;
            if (!entityFlag && !abilityFlag) return;

            if (entityFlag) player.setInvulnerable(false);
            if (abilityFlag) {
                // Survival/adventure with abilities.invulnerable set is the same class of stuck
                // state, reached when a gamemode change did not resync. onUpdateAbilities() pushes
                // the corrected abilities to the client; without it the server and client disagree
                // about whether the player can take damage.
                player.getAbilities().invulnerable = false;
                player.onUpdateAbilities();
            }

            repaired++;
            CoffeesAeroAuth.LOGGER.warn(
                "[InvulnRepair] Cleared stuck invulnerability for {} ({}) — entityFlag={} abilitiesFlag={} "
                + "mode={}. Saved data carried it; it is now corrected and will persist on next save.",
                player.getGameProfile().getName(), player.getUUID(), entityFlag, abilityFlag, mode);
        } catch (Throwable t) {
            // A repair must never be able to break a login. Worst case the player stays invincible.
            CoffeesAeroAuth.LOGGER.warn("[InvulnRepair] skipped for {}: {}",
                player.getGameProfile().getName(), t.toString());
        }
    }
}
