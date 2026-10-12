package com.coffeesaerosmp.auth.store.gui;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.GameTestPlayers;
import com.coffeesaerosmp.auth.store.PremiumGate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The Wardrobe payload as the server really builds it: every category present, prices on sale items, rank needs
 * on rank items, one colour per letter of the name for colours and gradients, particle settings on trails and
 * contrails, and under the size budget. Saved to wardrobe-sample.json for the AeroCore screen harness, so the
 * client is tested against exactly what the server sends.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class WardrobeSyncGameTests {

    @GameTest(template = "empty", batch = "wardrobe", timeoutTicks = 40)
    public static void wardrobePayloadIsCompleteAndSmall(GameTestHelper h) throws Exception {
        GameTestPlayers.floor(h);
        var p = GameTestPlayers.survival(h, new BlockPos(1, 1, 1), "wardrobe");
        PremiumGate.TEST_PREMIUM.add(p.getUUID());
        try {
            var json = WardrobeSync.build(p, 1250, true);
            String s = json.toString();
            Files.writeString(Path.of("wardrobe-sample.json"), s);
            if (s.length() > 64_000) h.fail("payload too big: " + s.length() + " bytes");
            var items = json.getAsJsonArray("items");
            int trails = 0, contrails = 0, colours = 0, grads = 0, extras = 0;
            String name = json.get("name").getAsString();
            for (var el : items) {
                var o = el.getAsJsonObject();
                switch (o.get("cat").getAsString()) {
                    case "TRAILS" -> trails++;
                    case "CONTRAILS" -> contrails++;
                    case "COLOURS" -> colours++;
                    case "GRADIENTS" -> grads++;
                    case "EXTRAS" -> extras++;
                    default -> h.fail("unknown category " + o);
                }
                String id = o.get("id").getAsString();
                if ((id.startsWith("trail:") || id.startsWith("contrail:")) && !o.has("fx")) h.fail("no fx for " + id);
                if ((id.startsWith("colour:") || id.startsWith("gradient:"))
                    && o.getAsJsonArray("nameRgb").size() != name.length()) h.fail("nameRgb length for " + id);
                if (id.equals("trail:kettle") && o.get("price").getAsInt() != 150) h.fail("kettle price");
                if (id.equals("contrail:aurora") && !"Admiral".equals(o.get("rank").getAsString())) h.fail("aurora rank");
            }
            if (trails != 8 || contrails != 5 || colours < 10 || grads < 8 || extras != 2)
                h.fail("counts t" + trails + " c" + contrails + " col" + colours + " g" + grads + " x" + extras);
            if (json.get("balance").getAsInt() != 1250) h.fail("balance");
        } finally {
            PremiumGate.TEST_PREMIUM.remove(p.getUUID());
            GameTestPlayers.remove(h, p);
        }
        h.succeed();
    }
}
