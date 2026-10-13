package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import com.coffeesaerosmp.auth.cosmetics.fx.FxPatterns;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.PremiumGate;
import com.coffeesaerosmp.auth.store.gui.ButtonMenu;
import com.coffeesaerosmp.auth.store.gui.ShopGui;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The remade effects and the server-side fitting room on a real server.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class FittingRoomGameTests {

    private static final java.util.Set<String> LEGACY = java.util.Set.of("trail:cloudwalk", "trail:brass", "trail:clockwork",
        "trail:espresso", "trail:kettle", "trail:petal", "trail:ember", "trail:snow", "contrail:vapour", "contrail:steam",
        "contrail:espresso", "contrail:sakura", "contrail:aurora");

    /** Every 1.13 cosmetic has a designed pattern, and no emit ever sends more than the budget. */
    @GameTest(template = "empty", batch = "fx_budget", timeoutTicks = 20)
    public static void everyEffectIsDesignedAndWithinBudget(GameTestHelper h) {
        List<String> bad = new ArrayList<>();
        for (var c : CosmeticCatalog.all()) {
            int max = 0, min = Integer.MAX_VALUE;
            for (long t = 0; t < 400; t += 2) {
                AtomicInteger n = new AtomicInteger();
                boolean designed = FxPatterns.emit(c.id(),
                    new FxPatterns.Ctx(0, 64, 0, 1, 0, 2.5, 1.8, t, t / 2), (o, lr, x, y, z, dx, dy, dz, sp, cnt) -> n.incrementAndGet());
                // The 1.13 trails and contrails have server-designed patterns; the 1.14 cosmetics are drawn by
                // AeroCore on each client, and the server only has their plain catalog fallback.
                if (!designed) {
                    if (LEGACY.contains(c.id())) bad.add(c.id() + " has no designed pattern");
                    max = -1;
                    break;
                }
                max = Math.max(max, n.get());
                min = Math.min(min, n.get());
            }
            if (max < 0) continue;
            if (max > FxPatterns.BUDGET) bad.add(c.id() + " sends " + max + " packets in one emit");
            if (min == 0 && max == 0) bad.add(c.id() + " never sends anything");
        }
        if (!bad.isEmpty()) h.fail(String.join("; ", bad));
        h.succeed();
    }

    /** Shop opens → mannequin + nameplate; contrail → ship; trail → back; shop closes → all gone. */
    @GameTest(template = "empty", batch = "fitting_room", timeoutTicks = 400)
    public static void fittingRoomFollowsTheShop(GameTestHelper h) {
        GameTestPlayers.floor(h);
        var p = GameTestPlayers.survival(h, new BlockPos(3, 1, 3), "fitting");
        PremiumGate.TEST_PREMIUM.add(p.getUUID());
        long before = FittingRoom.packetsSent();
        ShopGui.openClassic(p);
        int[] stage = {0};
        h.succeedWhen(() -> {
            switch (stage[0]) {
                case 0 -> {
                    if (!(p.containerMenu instanceof ButtonMenu)) throw new GameTestAssertException("waiting for the shop");
                    if (!FittingRoom.isOpen(p.getUUID())) h.fail("no fitting room beside the shop");
                    if (FittingRoom.entityIds(p.getUUID()).size() != 2) h.fail("mannequin + plate expected: " + FittingRoom.entityIds(p.getUUID()));
                    FittingRoom.preview(p, "contrail:aurora");
                    if (!"SHIP".equals(FittingRoom.mode(p.getUUID()))) h.fail("contrail did not switch to the ship");
                    if (FittingRoom.entityIds(p.getUUID()).size() != 9) h.fail("ship parts: " + FittingRoom.entityIds(p.getUUID()).size());
                    stage[0] = 1;
                    throw new GameTestAssertException("animating");
                }
                case 1 -> {
                    if (FittingRoom.packetsSent() - before < 30) throw new GameTestAssertException("waiting for animation packets");
                    FittingRoom.preview(p, "trail:petal");
                    if (!"PERSON".equals(FittingRoom.mode(p.getUUID()))) h.fail("trail did not switch back to the mannequin");
                    p.closeContainer();
                    stage[0] = 2;
                    throw new GameTestAssertException("closing");
                }
                case 2 -> {
                    if (FittingRoom.isOpen(p.getUUID())) throw new GameTestAssertException("waiting for the room to close");
                    PremiumGate.TEST_PREMIUM.remove(p.getUUID());
                    GameTestPlayers.remove(h, p);
                }
                default -> { }
            }
        });
    }

    /**
     * With the resource pack: a lit stage + backdrop, the model airship, and sprites that KEEP their model through
     * every keyframe (2026-10-12: re-posing without "item"/"brightness" blanked every display).
     */
    @GameTest(template = "empty", batch = "fitting_room_rp", timeoutTicks = 600)
    public static void resourcePackStageShipAndSprites(GameTestHelper h) {
        GameTestPlayers.floor(h);
        RpModels.testForce = true;
        var p = GameTestPlayers.survival(h, new BlockPos(3, 1, 3), "fittingrp");
        PremiumGate.TEST_PREMIUM.add(p.getUUID());
        ShopGui.openClassic(p);
        int[] stage = {0};
        long[] at = {0};
        h.succeedWhen(() -> {
            var ids = FittingRoom.entityIds(p.getUUID());
            switch (stage[0]) {
                case 0 -> {
                    // With the pack, the shop is the pack-drawn Wardrobe and the in-world room stays shut
                    if (!(p.containerMenu instanceof com.coffeesaerosmp.auth.store.gui.ButtonMenu bm) || !bm.hidesInventory()) {
                        throw new GameTestAssertException("waiting for the pack Wardrobe");
                    }
                    if (FittingRoom.isOpen(p.getUUID())) h.fail("the pack Wardrobe left the in-world fitting room open");
                    FittingRoom.ensure(p);                     // the room itself still works with the pack
                    stage[0] = 10;
                    throw new GameTestAssertException("room");
                }
                case 10 -> {
                    if (!FittingRoom.isOpen(p.getUUID())) throw new GameTestAssertException("waiting for the room");
                    if (ids.size() != 4) h.fail("mannequin + plate + stage + backdrop expected: " + ids.size());
                    FittingRoom.preview(p, "trail:petal");
                    at[0] = h.getTick();
                    stage[0] = 1;
                    throw new GameTestAssertException("petals");
                }
                case 1 -> {
                    if (h.getTick() - at[0] < 40) throw new GameTestAssertException("letting keyframes run");
                    if (ids.size() != 4 + 10) h.fail("10 petal sprites expected: " + ids.size());
                    var blank = FittingRoom.blankDisplays(p.getUUID());
                    if (!blank.isEmpty()) h.fail("displays lost their model/light after keyframes: " + blank);
                    FittingRoom.preview(p, "contrail:aurora");
                    if (!"SHIP".equals(FittingRoom.mode(p.getUUID()))) h.fail("contrail did not switch to the ship");
                    at[0] = h.getTick();
                    stage[0] = 2;
                    throw new GameTestAssertException("aurora");
                }
                case 2 -> {
                    if (h.getTick() - at[0] < 40) throw new GameTestAssertException("letting keyframes run");
                    // stage + ship model + 12 ribbons; the backdrop and mannequin are gone
                    if (ids.size() != 1 + 1 + 12) h.fail("stage + ship + 12 ribbons expected: " + ids.size());
                    var blank = FittingRoom.blankDisplays(p.getUUID());
                    if (!blank.isEmpty()) h.fail("ship/ribbons lost their model/light: " + blank);
                    FittingRoom.previewName(p, "colour:gold", false);
                    if (!"PERSON".equals(FittingRoom.mode(p.getUUID()))) h.fail("name preview did not bring the mannequin back");
                    if (FittingRoom.entityIds(p.getUUID()).size() != 4) h.fail("contrail sprites left behind: " + FittingRoom.entityIds(p.getUUID()).size());
                    p.closeContainer();
                    stage[0] = 3;
                    throw new GameTestAssertException("closing");
                }
                case 3 -> {
                    if (FittingRoom.isOpen(p.getUUID())) throw new GameTestAssertException("waiting for the room to close");
                    RpModels.testForce = false;
                    PremiumGate.TEST_PREMIUM.remove(p.getUUID());
                    GameTestPlayers.remove(h, p);
                }
                default -> { }
            }
        });
    }
}
