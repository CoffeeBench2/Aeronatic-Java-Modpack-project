package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Beans against a REAL MySQL (run-gametest/.env = the S3 BETA test database, never live): credit, idempotent
 * re-delivery, a purchase, a double purchase, not enough Beans, never below zero, and two concurrent buys of
 * the same item charging once.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class BeanLedgerGameTests {

    @GameTest(template = "empty", batch = "beans", timeoutTicks = 200_000)   // the GameTest server ticks unthrottled (~1.5 ms): ticks are not seconds
    public static void beansLedgerRoundTrip(GameTestHelper h) {
        UUID who = UUID.randomUUID();
        AtomicReference<String> result = new AtomicReference<>();
        CompletableFuture.runAsync(() -> {
            try { result.set(scenario(who)); }
            catch (Throwable t) { result.set("threw " + t); }
            finally { cleanup(who); }
        });
        h.succeedWhen(() -> {
            String r = result.get();
            if (r == null) throw new GameTestAssertException("running");
            if (!r.equals("ok")) h.fail(r);
        });
    }

    private static String scenario(UUID who) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!BeanLedger.available()) {
            if (System.currentTimeMillis() > deadline) return "DB never came up (is run-gametest/.env set?)";
            Thread.sleep(100);
        }
        if (BeanLedger.balance(who) != 0) return "fresh player should have 0";

        var c = BeanLedger.credit(who, 400, "PURCHASE", "gt-txn-1", "gametest");
        if (c.result() != BeanLedger.Result.APPLIED || c.balance() != 400) return "credit: " + c;
        var dup = BeanLedger.credit(who, 400, "PURCHASE", "gt-txn-1", "gametest retry");
        if (dup.result() != BeanLedger.Result.DUPLICATE || BeanLedger.balance(who) != 400) return "retry was not a no-op: " + dup;

        var b = BeanLedger.buy(who, "trail:kettle");                      // 150
        if (b.result() != BeanLedger.Result.APPLIED || b.balance() != 250) return "buy: " + b;
        var again = BeanLedger.buy(who, "trail:kettle");
        if (again.result() != BeanLedger.Result.ALREADY_OWNED || BeanLedger.balance(who) != 250) return "double buy charged: " + again;

        var poor = BeanLedger.buy(who, "trail:rainbow");                  // 300 > 250 (a trail: contrails can be parked)
        if (poor.result() != BeanLedger.Result.INSUFFICIENT || BeanLedger.balance(who) != 250) return "insufficient: " + poor;
        if (BeanLedger.buy(who, "trail:cloudwalk").result() != BeanLedger.Result.NOT_FOR_SALE) return "rank item was sold";

        var neg = BeanLedger.credit(who, -1000, "REVERSAL", null, "gametest");
        if (neg.result() != BeanLedger.Result.INSUFFICIENT || BeanLedger.balance(who) != 250) return "went below zero: " + neg;

        // Two buys of the same item at once: the row lock must make exactly one of them pay.
        var f1 = CompletableFuture.supplyAsync(() -> BeanLedger.buy(who, "trail:petal"));   // 200
        var f2 = CompletableFuture.supplyAsync(() -> BeanLedger.buy(who, "trail:petal"));
        var r1 = f1.get();
        var r2 = f2.get();
        int applied = (r1.result() == BeanLedger.Result.APPLIED ? 1 : 0) + (r2.result() == BeanLedger.Result.APPLIED ? 1 : 0);
        if (applied != 1) return "concurrent buys applied " + applied + " times: " + r1 + " / " + r2;
        if (BeanLedger.balance(who) != 50) return "balance after concurrent buys " + BeanLedger.balance(who);

        var hist = BeanLedger.history(who, 10);
        if (hist.size() != 3) return "expected 3 ledger rows (credit, 2 spends), got " + hist.size();
        if (hist.get(0).balanceAfter() != 50) return "newest row balance_after " + hist.get(0).balanceAfter();
        return "ok";
    }

    private static void cleanup(UUID who) {
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection()) {
            for (String t : new String[]{"beans_ledger", "beans_balance", "cosmetics_owned"}) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE mojang_uuid = ?")) {
                    ps.setString(1, who.toString());
                    ps.executeUpdate();
                }
            }
        } catch (Exception ignored) { }
    }
}
