package com.coffeesaerosmp.auth.season;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.SeasonMigration;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * One-time Season 1 loyalty reward, granted on a veteran's first entry into the Season 2 world.
 *
 * <p><b>Tiers are sized to the real player base</b> (119 rows, snapshot 2026-08-13) rather than
 * picked out of the air — the counts below are how many players actually land in each band:
 *
 * <pre>
 *   100h+   4 players    64 diamonds + 1 netherite ingot
 *    20h+   9 players    32 diamonds
 *     5h+  13 players    16 diamonds
 *     1h+  23 players     8 diamonds
 *    &lt;1h   63 players    nothing (registered but never really played)
 * </pre>
 *
 * <p>This is on top of the existing 200-spur starter bonus, which the season migration re-arms for
 * everyone by clearing {@code startup_bonus_given} — so a returning player gets currency to restart
 * with plus a keepsake scaled to what they put into Season 1.
 *
 * <p><b>No database call on the join path.</b> Eligibility comes from the set
 * {@link SeasonMigration} loaded once at boot; only the "claimed" write touches MySQL, and that goes
 * through {@code AsyncIo}. The DB is cross-continent (~234 ms), and the login path already runs on
 * the main thread — this must not add to that.
 *
 * <p>Anything that will not fit goes to the ground at the player's feet rather than being silently
 * destroyed.
 */
public final class VeteranReward {

    private VeteranReward() {}

    private static final long H = 3600L;

    /** Grants the reward if this player is owed one. Safe to call on every world entry. */
    public static void grantIfOwed(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Long seconds = SeasonMigration.pendingRewardFor(uuid);
        if (seconds == null) return;

        int diamonds;
        boolean netherite = false;
        String tier;
        if (seconds >= 100 * H)      { diamonds = 64; netherite = true; tier = "Founder"; }
        else if (seconds >= 20 * H)  { diamonds = 32; tier = "Veteran"; }
        else if (seconds >= 5 * H)   { diamonds = 16; tier = "Regular"; }
        else                         { diamonds = 8;  tier = "Pioneer"; }

        give(player, "minecraft:diamond", diamonds);
        if (netherite) give(player, "minecraft:netherite_ingot", 1);

        // Claim only after the items exist, so a crash mid-grant leaves it collectable next join.
        SeasonMigration.markClaimed(uuid, CoffeesAeroAuth.DB_MANAGER);

        long hours = seconds / H;
        player.sendSystemMessage(TextUtil.info(
            "§6Season 1 " + tier + " reward §7— thank you for the §f" + hours + "§7 hours you flew with us."));
        player.sendSystemMessage(TextUtil.info(
            "§7Received: §b" + diamonds + " diamonds" + (netherite ? " §7+ §51 netherite ingot" : "")));

        CoffeesAeroAuth.LOGGER.info("[Season] Veteran reward to {} — tier={} ({}h): {} diamonds{}",
            player.getGameProfile().getName(), tier, hours, diamonds, netherite ? " + netherite ingot" : "");
    }

    /** Adds to the inventory, dropping the remainder at the player's feet if it will not fit. */
    private static void give(ServerPlayer player, String id, int count) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        if (item == null) {
            CoffeesAeroAuth.LOGGER.warn("[Season] reward item missing from registry: {}", id);
            return;
        }
        int left = count;
        while (left > 0) {
            int n = Math.min(left, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, n);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            left -= n;
        }
    }
}
