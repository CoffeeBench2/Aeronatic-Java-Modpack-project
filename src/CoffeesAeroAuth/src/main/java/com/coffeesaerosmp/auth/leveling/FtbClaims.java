package com.coffeesaerosmp.auth.leveling;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * Sets a player's FTB Chunks EXTRA claim allowance, by reflection so the auth jar keeps loading on a
 * server without FTB Chunks (the lobby, test servers).
 *
 * <p>Why the API and not {@code /ftbchunks admin extra_claim_chunks <player> set n}: that argument resolves
 * the player BY NAME, and NameMask swaps the name players see — the command would miss or, worse, hit the
 * wrong person. The API takes the {@link ServerPlayer} itself.
 *
 * <pre>FTBChunksAPI.api().getManager().getOrCreateData(player).setExtraClaimChunks(n)</pre>
 * Verified present in ftb-chunks 2101.1.19 (S2) and 2101.1.22 (S3). Party limits then follow FTB's own
 * {@code party_limit_mode} (default LARGEST), so a team gets its best member's allowance, never a sum.
 */
final class FtbClaims {

    private static volatile boolean resolved, available;
    private static Method api, getManager, getOrCreateData, getExtra, setExtra;

    private FtbClaims() {}

    /** @return the extra now in force, or -1 if FTB Chunks is absent or the call failed */
    static int setExtra(ServerPlayer player, int extra) {
        if (!resolve()) return -1;
        try {
            Object a = api.invoke(null);
            Object mgr = getManager.invoke(a);
            Object data = getOrCreateData.invoke(mgr, player);
            if (data == null) return -1;
            int now = (int) getExtra.invoke(data);
            if (now != extra) setExtra.invoke(data, extra);
            return extra;
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[Level] could not set FTB claim extra for {}: {}",
                player.getGameProfile().getName(), t.toString());
            return -1;
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
                getOrCreateData = getManager.getReturnType().getMethod("getOrCreateData", ServerPlayer.class);
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
