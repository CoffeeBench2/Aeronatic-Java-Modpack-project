package com.coffeesaerosmp.auth.leveling;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Free ship claims against the REAL mods: FTB Chunks 2101.1.22 + AeroClaims 0.9.4 with the live S3
 * configs (claimProvider FTB_CHUNKS, max_claimed_chunks 5, party_limit_mode sum) in run-gametest.
 *
 * <p>"Land" = FTB Chunks' effective max claim chunks for the player's team, which is what the claim map
 * enforces (AeroClaims' ChunkTeamDataMixin subtracts migrated ship slots from it).
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class ShipClaimsGameTests {

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void shipClaimsAreFreeAndLandIsUnchanged(GameTestHelper h) {
        GameTestPlayers.floor(h);
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "shiptest");
        h.runAfterDelay(20, () -> {
            if (!ShipClaims.available()) h.fail("AeroClaims not found in run-gametest/mods");
            int land = maxClaims(p);
            if (land != 5) h.fail("expected FTB base 5 for a fresh player, got " + land);

            // Lv 1: 2 ship claims — exactly what LevelService does: extra += ship, then top up.
            check(h, FtbClaims.setExtra(p, 2) >= 0, "FTB setExtra failed");
            check(h, ShipClaims.topUp(p, 2) == 2, "first grant should move 2 slots");
            check(h, migrated(p) == 2, "AeroClaims should hold 2 slots, has " + migrated(p));
            check(h, maxClaims(p) == land, "land changed: " + land + " -> " + maxClaims(p));
            check(h, ShipClaims.granted(p.getServer(), p.getUUID()) == 2, "ledger should record 2");

            // Re-check (every LevelService.check calls this): nothing granted twice.
            check(h, ShipClaims.topUp(p, 2) == 0, "granted twice");
            check(h, migrated(p) == 2, "slots grew on a repeat check: " + migrated(p));

            // Lv 6: +1 ship claim, land still unchanged.
            check(h, FtbClaims.setExtra(p, 3) >= 0, "FTB setExtra (Lv 6) failed");
            check(h, ShipClaims.topUp(p, 3) == 1, "level-up should add exactly 1");
            check(h, migrated(p) == 3, "AeroClaims should hold 3, has " + migrated(p));
            check(h, maxClaims(p) == land, "land changed at Lv 6: " + maxClaims(p));

            GameTestPlayers.remove(h, p);
            h.succeed();
        });
    }

    /**
     * CONTROL: the same grant WITHOUT first raising the FTB extra comes out of the player's land. Proves
     * the land check above can fail — and is why LevelService always raises the extra first.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void controlGrantWithoutExtraEatsLand(GameTestHelper h) {
        GameTestPlayers.floor(h);
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "shipctrl");
        h.runAfterDelay(20, () -> {
            int land = maxClaims(p);
            check(h, ShipClaims.topUp(p, 2) == 2, "control grant should still move 2");
            check(h, maxClaims(p) == land - 2, "control: land should drop by 2, " + land + " -> " + maxClaims(p));
            GameTestPlayers.remove(h, p);
            h.succeed();
        });
    }

    // ── helpers (reflection: FTB Chunks / AeroClaims are runtime-only here) ──────

    private static void check(GameTestHelper h, boolean ok, String msg) {
        if (!ok) h.fail(msg);
    }

    private static int maxClaims(ServerPlayer p) {
        try {
            Object api = Class.forName("dev.ftb.mods.ftbchunks.api.FTBChunksAPI").getMethod("api").invoke(null);
            Object mgr = api.getClass().getMethod("getManager").invoke(api);
            Object data = mgr.getClass().getMethod("getOrCreateData", ServerPlayer.class).invoke(mgr, p);
            return (int) data.getClass().getMethod("getMaxClaimChunks").invoke(data);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int migrated(ServerPlayer p) {
        try {
            return (int) Class.forName("com.mapter.aeroclaims.claim.AeroClaimManager")
                .getMethod("getMigratedSlots", ServerLevel.class, UUID.class).invoke(null, p.serverLevel(), p.getUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
