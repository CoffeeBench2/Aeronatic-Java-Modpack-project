package com.coffeesaerosmp.guard.protect;

import com.coffeesaerosmp.guard.CoffeesAeroGuard;
import com.coffeesaerosmp.guard.config.GuardConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Season 3 team claims (owner, 2026-10-04): a team's allowance is the SUM of its members' (FTB
 * {@code party_limit_mode = "sum"}, capped by {@code hard_team_claim_limit}), and "if a player leaves the
 * team, that player's claims go with them".
 *
 * <p>FTB recalculates the party's LIMIT when someone leaves, but never removes chunks: a team that claimed
 * up to its old, larger limit would simply keep them. This trims the party back under its new limit the
 * moment a member leaves.
 *
 * <p>Which chunks: FTB does not record which member claimed a chunk, so the leaver's own claims cannot be
 * identified. The NEWEST claims go first — the team's core (oldest, usually the base) stays. Claims the
 * leaving player owns personally (their own team) are untouched.
 *
 * <p>Same containment pattern as {@link EndArenaClaims}: the FTB Teams/Chunks classes are only touched when
 * both are loaded, and failures are non-fatal.
 */
public final class TeamClaimTrim {

    private TeamClaimTrim() {}

    public static void install() {
        if (!ModList.get().isLoaded("ftbchunks") || !ModList.get().isLoaded("ftbteams")) return;
        try {
            Impl.install();
            CoffeesAeroGuard.LOGGER.info("[TeamClaims] listening for players leaving a team (claims trimmed to the new limit).");
        } catch (Throwable t) {
            CoffeesAeroGuard.LOGGER.warn("[TeamClaims] failed to install (non-fatal): {}", t.toString());
        }
    }

    private static final class Impl {
        static void install() {
            dev.ftb.mods.ftbteams.api.event.TeamEvent.PLAYER_LEFT_PARTY.register(e -> {
                if (e.getTeamDeleted()) return;                          // last member left: the team is gone
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                var team = e.getTeam();
                String who = e.getPlayer() != null ? e.getPlayer().getGameProfile().getName() : String.valueOf(e.getPlayerId());
                // Next tick: FTB Chunks recomputes the party's limits in its own handler for this event.
                server.execute(() -> {
                    try {
                        trim(server, team, who);
                    } catch (Throwable t) {
                        CoffeesAeroGuard.LOGGER.warn("[TeamClaims] trim failed for {}: {}", team.getShortName(), t.toString());
                    }
                });
            });
        }

        static void trim(MinecraftServer server, dev.ftb.mods.ftbteams.api.Team team, String leaver) {
            if (!GuardConfig.TEAM_LEAVE_TRIMS_CLAIMS.get()) return;
            var data = dev.ftb.mods.ftbchunks.api.FTBChunksAPI.api().getManager().getOrCreateData(team);
            try { data.getClass().getMethod("updateLimits").invoke(data); } catch (Throwable ignored) {}
            int max = data.getMaxClaimChunks();
            var claimed = new java.util.ArrayList<dev.ftb.mods.ftbchunks.api.ClaimedChunk>(data.getClaimedChunks());
            int excess = claimed.size() - max;
            if (excess <= 0) {
                CoffeesAeroGuard.LOGGER.info("[TeamClaims] {} left {} — {} claims within the new limit {}.",
                    leaver, team.getShortName(), claimed.size(), max);
                return;
            }
            claimed.sort(java.util.Comparator.comparingLong(
                (dev.ftb.mods.ftbchunks.api.ClaimedChunk c) -> c.getTimeClaimed()).reversed());
            var src = server.createCommandSourceStack().withSuppressedOutput();
            int released = 0;
            for (int i = 0; i < excess; i++) {
                claimed.get(i).unclaim(src, true);
                released++;
            }
            CoffeesAeroGuard.LOGGER.warn("[TeamClaims] {} left {} — limit now {}, released the {} newest claim(s).",
                leaver, team.getShortName(), max, released);
            Component msg = Component.literal("§e⚑ §f" + leaver + "§e left the team — the team's claim limit is now §f"
                + max + "§e, so the §f" + released + "§e newest claimed chunk(s) were released.");
            for (var p : team.getOnlineMembers()) p.sendSystemMessage(msg);
        }
    }
}
