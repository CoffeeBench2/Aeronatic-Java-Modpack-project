package com.coffeesaerosmp.auth.store.gui;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import com.coffeesaerosmp.auth.cosmetics.RpModels;
import com.coffeesaerosmp.auth.store.PremiumGate;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/** The pack-drawn Wardrobe and its skin doll on a real server. */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class PackWardrobeGameTests {

    private static void textures(net.minecraft.server.level.ServerPlayer p, String url) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        p.getGameProfile().getProperties().removeAll("textures");
        p.getGameProfile().getProperties().put("textures",
            new Property("textures", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
    }

    /** A real Mojang skin URL is fetched off-thread and turned into a doll; the callback runs on the server. */
    @GameTest(template = "empty", batch = "pack_wardrobe_skin", timeoutTicks = 400_000)
    public static void skinIsFetchedAndCached(GameTestHelper h) {
        GameTestPlayers.floor(h);
        var p = GameTestPlayers.survival(h, new BlockPos(2, 1, 2), "doll");
        // jeb_'s skin (64x64, classic arms)
        textures(p, "http://textures.minecraft.net/texture/7fd9ba42a7c81eeea22f1524271ae85a8e045ce0af5a6ae16c6406ae917e68b5");
        AtomicBoolean ready = new AtomicBoolean();
        var first = SkinDoll.get(p, () -> ready.set(true));
        if (first != null) h.fail("first ask should start a fetch, not answer at once");
        h.succeedWhen(() -> {
            if (!ready.get()) throw new GameTestAssertException("waiting for the skin download");
            var d = SkinDoll.get(p, null);
            if (d == null) h.fail("cached doll missing after the callback");
            if (d.width() != 16) h.fail("classic skin should be 16 wide: " + d.width());
            if (java.util.Arrays.deepEquals(d.px(), SkinDoll.fallback().px())) h.fail("got the fallback, not the skin");
            GameTestPlayers.remove(h, p);
        });
    }

    /** Only Mojang's texture host is ever requested: anything else draws the plain figure, no fetch. */
    @GameTest(template = "empty", batch = "pack_wardrobe_skin", timeoutTicks = 200)
    public static void foreignSkinHostsAreNeverFetched(GameTestHelper h) {
        GameTestPlayers.floor(h);
        var p = GameTestPlayers.survival(h, new BlockPos(2, 1, 2), "dollx");
        textures(p, "http://127.0.0.1:1/evil.png");
        var d = SkinDoll.get(p, () -> h.fail("must not fetch"));
        if (d == null || !java.util.Arrays.deepEquals(d.px(), SkinDoll.fallback().px())) h.fail("expected the plain figure at once");
        GameTestPlayers.remove(h, p);
        h.succeed();
    }

    /**
     * /shop with the pack: the full-screen Wardrobe opens with the inventory area as buttons; a click on a row
     * selects it (redrawn in place); closing re-sends the real inventory, which the server never touched.
     */
    @GameTest(template = "empty", batch = "pack_wardrobe", timeoutTicks = 400)
    public static void wardrobeOpensSelectsAndGivesTheInventoryBack(GameTestHelper h) {
        GameTestPlayers.floor(h);
        RpModels.testForce = true;
        var p = GameTestPlayers.survival(h, new BlockPos(3, 1, 3), "packwd");
        PremiumGate.TEST_PREMIUM.add(p.getUUID());
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        ShopGui.openClassic(p);
        int[] stage = {0};
        int[] firstMenu = {-1};
        h.succeedWhen(() -> {
            switch (stage[0]) {
                case 0 -> {
                    if (!(p.containerMenu instanceof ButtonMenu bm)) throw new GameTestAssertException("waiting for the shop");
                    if (!bm.hidesInventory()) h.fail("pack Wardrobe should use the inventory area as buttons");
                    if (bm.slots.size() != 90) h.fail("expected 54 + 36 slots: " + bm.slots.size());
                    if (!bm.getSlot(54 + 27).getItem().isEmpty() && bm.getSlot(54 + 27).getItem().is(Items.DIAMOND)) {
                        h.fail("the player's own diamonds are showing in the Wardrobe");
                    }
                    if (bm.getSlot(9).getItem().isEmpty()) h.fail("first list row has no icon");
                    firstMenu[0] = bm.containerId;
                    bm.clicked(9 + 9 + 4, 0, ClickType.PICKUP, p);        // middle of row 2: select it
                    stage[0] = 1;
                    throw new GameTestAssertException("selecting");
                }
                case 1 -> {
                    if (!(p.containerMenu instanceof ButtonMenu bm) || bm.containerId == firstMenu[0]) {
                        throw new GameTestAssertException("waiting for the redraw");
                    }
                    if (!bm.hidesInventory()) h.fail("redraw lost the hidden-inventory mode");
                    p.closeContainer();
                    stage[0] = 2;
                    throw new GameTestAssertException("closing");
                }
                case 2 -> {
                    ItemStack d = p.getInventory().getItem(0);
                    if (!d.is(Items.DIAMOND) || d.getCount() != 5) h.fail("server inventory changed: " + d);
                    if (p.containerMenu != p.inventoryMenu) h.fail("still in a menu");
                    RpModels.testForce = false;
                    PremiumGate.TEST_PREMIUM.remove(p.getUUID());
                    GameTestPlayers.remove(h, p);
                }
                default -> { }
            }
        });
    }

    /** The title fits the client's limits and the canvas metrics load. */
    @GameTest(template = "empty", batch = "pack_wardrobe", timeoutTicks = 20)
    public static void canvasMetricsLoad(GameTestHelper h) {
        var m = TitleCanvas.M;
        if (m.rows.length != PackWardrobe.PAGE) h.fail("layout rows " + m.rows.length + " != page size " + PackWardrobe.PAGE);
        if (m.scenes.size() < 13) h.fail("scenes missing: " + m.scenes.keySet());
        if (TitleCanvas.width("Hello", false, false) <= 0) h.fail("zero width");
        // The smallest GUI screen in common use is 426x240 (auto scale at 720p/1440p/4K); the 176x222 chest is
        // centred in it, so the canvas may reach 125 px either side and 9 px below the window, no further.
        if (m.left[0] < -125 || m.right[1] > 176 + 125) h.fail("canvas wider than a 426 px screen: " + m.left[0] + ".." + m.right[1]);
        if (m.viewBottom > 222 + 9 || m.textYmax < 0) h.fail("viewport below a 240 px screen");
        for (var e : m.scenes.entrySet()) {
            if (e.getValue().width() > m.left[1] - m.left[0] - 8) h.fail("scene " + e.getKey() + " wider than the panel");
        }
        h.succeed();
    }
}
