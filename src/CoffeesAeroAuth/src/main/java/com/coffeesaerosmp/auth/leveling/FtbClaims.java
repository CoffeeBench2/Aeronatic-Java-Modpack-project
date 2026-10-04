package com.coffeesaerosmp.auth.leveling;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Sets a player's FTB Chunks EXTRA claim allowance, by reflection so the auth jar keeps loading on a
 * server without FTB Chunks (the lobby, test servers).
 *
 * <p>Why the API and not {@code /ftbchunks admin extra_claim_chunks <player> set n}: that argument resolves
 * the player BY NAME, and NameMask swaps the name players see — the command would miss or, worse, hit the
 * wrong person. The API takes the uuid.
 *
 * <h2>🔴 PERSONAL data, never the current team's</h2>
 * {@code getOrCreateData(player)} returns the player's CURRENT team — for a party member that is the
 * PARTY, and setting its extra would make the party's allowance whatever the last-checked member's was
 * (1.13.0–1.13.3 did exactly that). FTB computes a party's limit from each member's PERSONAL allowance
 * ({@code ChunkTeamDataImpl.updateLimits → TeamMemberData.getMaxClaims}), combined by
 * {@code party_limit_mode} — "sum" on Season 3 (owner, 2026-10-04), capped by {@code hard_team_claim_limit}.
 * So: set the personal extra, then ask the current team to recompute its limits.
 *
 * <pre>
 * mgr = FTBChunksAPI.api().getManager()
 * mgr.getPersonalData(uuid).setExtraClaimChunks(n)          // the player's own allowance
 * mgr.getOrCreateData(player).updateLimits()                 // their party (or personal team) re-sums
 * </pre>
 * Verified present in ftb-chunks 2101.1.19 (S2) and 2101.1.22 (S3).
 */
final class FtbClaims {

    private static volatile boolean resolved, available;
    private static Method api, getManager, getPersonalData, getOrCreateData, getExtra, setExtra;

    private FtbClaims() {}

    /** @return the extra now in force, or -1 if FTB Chunks is absent or the call failed */
    static int setExtra(ServerPlayer player, int extra) {
        if (!resolve()) return -1;
        try {
            Object mgr = getManager.invoke(api.invoke(null));
            Object personal = getPersonalData.invoke(mgr, player.getUUID());
            if (personal == null) personal = getOrCreateData.invoke(mgr, player);   // first login: no team data yet
            if (personal == null) return -1;
            int now = (int) getExtra.invoke(personal);
            if (now != extra) setExtra.invoke(personal, extra);
            updateLimits(personal);
            Object current = getOrCreateData.invoke(mgr, player);
            if (current != null && current != personal) updateLimits(current);         // party re-sums
            return extra;
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[Level] could not set FTB claim extra for {}: {}",
                player.getGameProfile().getName(), t.toString());
            return -1;
        }
    }

    /** {@code updateLimits()} is public on the implementation but not on the API interface. */
    private static void updateLimits(Object teamData) {
        try {
            teamData.getClass().getMethod("updateLimits").invoke(teamData);
        } catch (Throwable ignored) {
            // Older/newer FTB without it: the limit still updates on FTB's own next recalculation.
        }
    }

    private static boolean resolve() {
        if (resolved) return available;
        synchronized (FtbClaims.class) {
            if (resolved) return available;
            try {
                Class<?> apiCls = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI");
                api = apiCls.getMethod("api");
                getManager = api.getReturnType().getMethod("getManager");
                Class<?> mgrCls = getManager.getReturnType();
                getPersonalData = mgrCls.getMethod("getPersonalData", UUID.class);
                getOrCreateData = mgrCls.getMethod("getOrCreateData", ServerPlayer.class);
                Class<?> dataCls = getOrCreateData.getReturnType();
                getExtra = dataCls.getMethod("getExtraClaimChunks");
                setExtra = dataCls.getMethod("setExtraClaimChunks", int.class);
                available = true;
                CoffeesAeroAuth.LOGGER.info("[Level] FTB Chunks API found — claim limits follow levels.");
            } catch (Throwable t) {
                available = false;
                CoffeesAeroAuth.LOGGER.info("[Level] FTB Chunks API not available ({}) — claim limits unchanged.",
                    t.getClass().getSimpleName());
            }
            resolved = true;
            return available;
        }
    }
}
