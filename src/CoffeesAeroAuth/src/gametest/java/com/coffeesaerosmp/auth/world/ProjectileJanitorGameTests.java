package com.coffeesaerosmp.auth.world;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.projectile.Arrow;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * The janitor on a real server. Arrows stand in for gun bullets (the gun mod is not in the test
 * environment); a pig is the control that must never be touched.
 */
@GameTestHolder(CoffeesAeroAuth.MOD_ID)
@PrefixGameTestTemplate(false)
public class ProjectileJanitorGameTests {

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void sweepRemovesOnlyListedProjectilesOlderThanTheLimit(GameTestHelper h) {
        ProjectileJanitor.reset();
        Arrow arrow = h.spawn(EntityType.ARROW, new BlockPos(3, 2, 3));
        arrow.setNoGravity(true);
        Pig pig = h.spawn(EntityType.PIG, new BlockPos(1, 1, 1));
        var server = h.getLevel().getServer();
        Set<String> types = Set.of("minecraft:arrow");
        long max = 100;

        ProjectileJanitor.sweep(server, types, max, 1_000_000, false);          // first sight
        ProjectileJanitor.sweep(server, types, max, 1_000_050, false);
        if (arrow.isRemoved()) h.fail("a 50-tick-old arrow was removed (limit 100)");
        ProjectileJanitor.sweep(server, types, max, 1_000_101, false);          // 101 ticks since first sight
        if (!arrow.isRemoved()) h.fail("an arrow seen 101 ticks ago survived a 100-tick limit");
        if (pig.isRemoved()) h.fail("control: the pig was removed — the janitor touched an unlisted type");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aListedProjectileFromDiskIsRefusedButAFreshOneIsNot(GameTestHelper h) {
        var configured = AuthConfig.PROJECTILE_CLEANUP_TYPES.get();
        try {
            AuthConfig.PROJECTILE_CLEANUP_TYPES.set(List.of("minecraft:arrow"));
            Arrow fromDisk = new Arrow(EntityType.ARROW, h.getLevel());
            EntityJoinLevelEvent loaded = new EntityJoinLevelEvent(fromDisk, h.getLevel(), true);
            NeoForge.EVENT_BUS.post(loaded);
            if (!loaded.isCanceled()) h.fail("a listed projectile loaded from disk was let back in");

            Arrow fresh = new Arrow(EntityType.ARROW, h.getLevel());
            EntityJoinLevelEvent fired = new EntityJoinLevelEvent(fresh, h.getLevel(), false);
            NeoForge.EVENT_BUS.post(fired);
            if (fired.isCanceled()) h.fail("a freshly fired projectile was refused");

            Pig pig = new Pig(EntityType.PIG, h.getLevel());
            EntityJoinLevelEvent pigLoad = new EntityJoinLevelEvent(pig, h.getLevel(), true);
            NeoForge.EVENT_BUS.post(pigLoad);
            if (pigLoad.isCanceled()) h.fail("control: an unlisted entity loaded from disk was refused");
        } finally {
            AuthConfig.PROJECTILE_CLEANUP_TYPES.set(configured);
        }
        h.succeed();
    }
}
