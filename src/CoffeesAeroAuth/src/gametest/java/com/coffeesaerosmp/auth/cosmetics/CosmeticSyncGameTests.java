package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import com.coffeesaerosmp.auth.store.PreviewSelections;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 1.14.0: the who-wears-what table AeroCore draws from, and the handshake gate. Mock players have no AeroCore
 * channel, so this checks the server half: what goes in the table, and that the gate leaves everyone alone while
 * {@code requireAeroCore} is off.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class CosmeticSyncGameTests {

    @GameTest(template = "empty", batch = "cosmetic_sync", timeoutTicks = 100)
    public static void theTableCarriesEverySlotAndOnlyWearers(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var wearer = GameTestPlayers.survival(h, new BlockPos(3, 1, 3), "sync");
        var plain = GameTestPlayers.survival(h, new BlockPos(5, 1, 3), "sync");
        CosmeticEngine.TEST_OPS.add(wearer.getUUID());                  // ops may preview anything
        CosmeticEngine.refresh(server);
        Selection look = Selection.DEFAULT.with(CosmeticCatalog.Type.TRAIL, "trail:rainbow")
            .with(CosmeticCatalog.Type.WINGS, "wings:seraph").with(CosmeticCatalog.Type.AURA, "aura:halo");
        String wantWings = CosmeticCatalog.WINGS_ON ? "wings:seraph" : null;           // parked wings are dropped
        PreviewSelections.put(wearer.getUUID(), look);
        try {
            var table = CosmeticSync.snapshot(server);
            var e = table.get(wearer.getUUID());
            if (e == null) { h.fail("the wearer is missing from the table"); return; }
            if (!"trail:rainbow".equals(e.trail()) || !java.util.Objects.equals(wantWings, e.wings()) || !"aura:halo".equals(e.aura())) {
                h.fail("slots lost on the way into the table: " + e);
            }
            if (e.contrail() != null) h.fail("contrail should be empty: " + e);
            if (table.containsKey(plain.getUUID())) h.fail("a player wearing nothing should not be in the table");
            // An unknown id never reaches clients (sanitise drops it).
            PreviewSelections.put(wearer.getUUID(), look.with(CosmeticCatalog.Type.WINGS, "wings:nope"));
            var e2 = CosmeticSync.snapshot(server).get(wearer.getUUID());
            if (e2 == null || e2.wings() != null) h.fail("unknown wings id reached the table: " + e2);
            // The gate is off by default: logging in without AeroCore must not disconnect anyone.
            CoreGate.onLogin(plain);
            CoreGate.tick(server);
            if (plain.hasDisconnected()) h.fail("CoreGate refused a player while requireAeroCore is off");
            h.succeed();
        } finally {
            PreviewSelections.forget(wearer.getUUID());
            CosmeticEngine.TEST_OPS.remove(wearer.getUUID());
            CosmeticEngine.forget(wearer.getUUID());
            CosmeticEngine.forget(plain.getUUID());
            GameTestPlayers.remove(h, wearer);
            GameTestPlayers.remove(h, plain);
        }
    }

    /** Ship fits reach the table; an online owner who can't wear the contrail any more has it withheld. */
    @GameTest(template = "empty", batch = "cosmetic_sync", timeoutTicks = 100)
    public static void fittedShipsSyncAndLapsedFitsAreWithheld(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var owner = GameTestPlayers.survival(h, new BlockPos(3, 1, 3), "fit");
        java.util.UUID shipA = java.util.UUID.randomUUID(), shipB = java.util.UUID.randomUUID();
        try {
            // a Beans contrail the owner does NOT own, and they're not a previewer: withheld while they're online
            ShipContrails.fit(shipA, "contrail:rainbow", owner.getUUID());
            // an owner who is offline: kept as last validated
            ShipContrails.fit(shipB, "contrail:sakura", java.util.UUID.randomUUID());
            var ships = CosmeticSync.ships(server);
            if (!com.coffeesaerosmp.auth.store.CosmeticCatalog.CONTRAILS_ON) {   // parked: no fit may reach clients
                if (!ships.isEmpty()) h.fail("parked contrails reached clients: " + ships);
                h.succeed();
                return;
            }
            if (ships.stream().anyMatch(f -> f.ship().equals(shipA))) h.fail("a lapsed fit reached clients");
            if (ships.stream().noneMatch(f -> f.ship().equals(shipB) && "contrail:sakura".equals(f.contrail())))
                h.fail("an offline owner's fit is missing: " + ships);
            // as a previewer (op) the same fit is shown
            CosmeticEngine.TEST_OPS.add(owner.getUUID());
            CosmeticEngine.refresh(server);
            if (CosmeticSync.ships(server).stream().noneMatch(f -> f.ship().equals(shipA))) h.fail("a valid fit was withheld");
            // clearing removes it
            ShipContrails.clear(shipB);
            if (CosmeticSync.ships(server).stream().anyMatch(f -> f.ship().equals(shipB))) h.fail("a cleared fit is still sent");
            h.succeed();
        } finally {
            ShipContrails.clear(shipA);
            ShipContrails.clear(shipB);
            CosmeticEngine.TEST_OPS.remove(owner.getUUID());
            CosmeticEngine.forget(owner.getUUID());
            GameTestPlayers.remove(h, owner);
        }
    }
}
