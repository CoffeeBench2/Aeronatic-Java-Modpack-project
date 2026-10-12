package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import com.coffeesaerosmp.auth.cosmetics.TryOn;
import com.coffeesaerosmp.auth.store.gui.ButtonMenu;
import com.coffeesaerosmp.auth.store.gui.ShopGui;
import com.coffeesaerosmp.auth.store.gui.ShopGuiProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The shop on a real server (BETA test DB, never live): Tebex retries can't double-deliver, offline accounts
 * can't open the shop, premium accounts get the GUI and can navigate it, and a try-on sends particles.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class ShopGameTests {

    @GameTest(template = "empty", batch = "tebex", timeoutTicks = 200_000)   // unthrottled ticks: not seconds
    public static void tebexDeliveriesAreOnceOnly(GameTestHelper h) {
        UUID who = UUID.randomUUID();                       // never linked: the parked-grant path
        String txn = "tbx-gametest-" + who.toString().substring(0, 8);
        AtomicReference<String> result = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            try {
                long deadline = System.currentTimeMillis() + 30_000;
                while (!BeanLedger.available() && System.currentTimeMillis() < deadline) Thread.sleep(100);
                if (!StoreGrants.claim("gt-claim-" + txn, "TEST", who, null)) { result.set("first claim refused"); return; }
                if (StoreGrants.claim("gt-claim-" + txn, "TEST", who, null)) { result.set("second claim allowed"); return; }

                // A paid rank for a buyer we can't resolve yet is parked once, however often Tebex retries.
                StoreGrants.grantRank(who, Rank.NAVIGATOR, 30, txn);
                StoreGrants.grantRank(who, Rank.NAVIGATOR, 30, txn);
                StoreGrants.grantRank(who, Rank.NAVIGATOR, 30, txn);
                int parked = count("SELECT COUNT(*) FROM store_pending_grants WHERE mojang_uuid = ?", who);
                if (parked != 1) { result.set("retries parked " + parked + " grants (want 1)"); return; }

                // Beans: same transaction twice = one credit.
                var a = BeanLedger.credit(who, 550, "PURCHASE", "tebex:" + txn, "gametest");
                var b = BeanLedger.credit(who, 550, "PURCHASE", "tebex:" + txn, "gametest retry");
                if (a.result() != BeanLedger.Result.APPLIED || b.result() != BeanLedger.Result.DUPLICATE || BeanLedger.balance(who) != 550) {
                    result.set("beans retry: " + a + " / " + b + " balance " + BeanLedger.balance(who)); return;
                }
                result.set("ok");
            } catch (Throwable t) {
                result.set("threw " + t);
            } finally {
                cleanup(who, txn);
            }
        });
        h.succeedWhen(() -> {
            String r = result.get();
            if (r == null) throw new GameTestAssertException("running");
            if (!r.equals("ok")) h.fail(r);
        });
    }

    @GameTest(template = "empty", batch = "shop_gate", timeoutTicks = 200_000)
    public static void offlineAccountsCannotOpenTheShop(GameTestHelper h) {
        GameTestPlayers.floor(h);
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "shopoff");
        ShopGui.openMain(p);                                // not premium: refused before any DB work
        h.runAfterDelay(40, () -> {
            if (p.containerMenu instanceof ButtonMenu) h.fail("an offline account got the shop window");
            GameTestPlayers.remove(h, p);
            h.succeed();
        });
    }

    @GameTest(template = "empty", batch = "shop_gui", timeoutTicks = 200_000)
    public static void premiumPlayersGetANavigableShop(GameTestHelper h) {
        GameTestPlayers.floor(h);
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "shopon");
        PremiumGate.TEST_PREMIUM.add(p.getUUID());
        ShopGui.openMain(p);
        AtomicReference<Object> mainMenu = new AtomicReference<>();
        h.succeedWhen(() -> {
            if (!(p.containerMenu instanceof ButtonMenu menu)) throw new GameTestAssertException("waiting for the main menu");
            if (mainMenu.get() == null) {
                mainMenu.set(menu);
                var title = menu.getSlot(4).getItem().get(DataComponents.CUSTOM_NAME);
                if (title == null || !title.getString().contains("Coffee's Aero Shop")) h.fail("main menu title item: " + title);
                menu.clicked(20, 0, ClickType.PICKUP, p);        // Trails
                throw new GameTestAssertException("waiting for the trails page");
            }
            if (p.containerMenu == mainMenu.get()) throw new GameTestAssertException("waiting for the trails page");
            var first = p.containerMenu.getSlot(10).getItem().get(DataComponents.CUSTOM_NAME);
            if (first == null || !first.getString().equals("Cloud Walker")) h.fail("first trail: " + first);
            // The rank each item needs is worked out from the entitlement rules, not hard-coded.
            var entries = ShopGuiProbe.trailRanks(p);
            if (!entries.contains("trail:cloudwalk=DECKHAND") || !entries.contains("trail:brass=NAVIGATOR")
                || !entries.contains("trail:kettle=none")) h.fail("rank needs: " + entries);
            PremiumGate.TEST_PREMIUM.remove(p.getUUID());
            p.closeContainer();
            GameTestPlayers.remove(h, p);
        });
    }

    @GameTest(template = "empty", batch = "tryon", timeoutTicks = 400)
    public static void tryOnShowsTheTrailToTheWearerOnly(GameTestHelper h) {
        GameTestPlayers.floor(h);
        ServerPlayer p = GameTestPlayers.survival(h, new BlockPos(3, 1, 3), "tryon");
        long before = ShopGuiProbe.tryOnPackets();
        if (!TryOn.start(p, "trail:petal")) h.fail("try-on refused a real trail");
        for (int t = 0; t < 60; t += 2) {
            h.runAfterDelay(t, () -> {
                Vec3 at = p.position().add(0.6, 0, 0);
                p.teleportTo(h.getLevel(), at.x, at.y, at.z, 0f, 0f);
            });
        }
        h.runAfterDelay(80, () -> {
            if (ShopGuiProbe.tryOnPackets() <= before) h.fail("try-on sent nothing while walking");
            if (!TryOn.isTrying(p.getUUID())) h.fail("try-on ended early");
            TryOn.stop(p.getUUID());
            GameTestPlayers.remove(h, p);
            h.succeed();
        });
    }

    private static int count(String sql, UUID who) throws Exception {
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, who.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : -1; }
        }
    }

    private static void cleanup(UUID who, String txn) {
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
            for (String t : new String[]{"store_pending_grants", "beans_ledger", "beans_balance", "store_deliveries"}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE mojang_uuid = ?")) {
                    ps.setString(1, who.toString());
                    ps.executeUpdate();
                }
            }
        } catch (Exception ignored) { }
    }
}
