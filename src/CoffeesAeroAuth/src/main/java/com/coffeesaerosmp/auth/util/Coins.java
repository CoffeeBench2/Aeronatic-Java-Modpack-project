package com.coffeesaerosmp.auth.util;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Paying a player in Numismatics currency.
 *
 * <p>Spurs are the base unit; the coins above them are worth cog=64, sprocket=16, bevel=8. Paying
 * 200 spurs as 200 individual spur items would eat four inventory slots and read as a joke, so this
 * makes greedy change the way a cashier would: 200 -> 3 cogs + 1 bevel.
 *
 * <p>Every method is a no-op returning {@code false} if Numismatics is absent, so callers never need
 * to check whether the mod is installed.
 */
public final class Coins {

    private Coins() {}

    /** Coin denominations in spur value, highest first — must stay parallel to {@link #NAMES}. */
    private static final int[]    VALUES = {64, 16, 8, 1};
    private static final String[] NAMES  = {"cog", "sprocket", "bevel", "spur"};

    /**
     * Pays {@code spurs} worth of Numismatics currency into the player's inventory, spilling to the
     * ground when it is full. Returns true if anything was actually given.
     */
    public static boolean pay(ServerPlayer player, int spurs) {
        if (player == null || spurs <= 0) return false;
        boolean gaveAny = false;
        int remaining = spurs;
        for (int i = 0; i < VALUES.length; i++) {
            int count = remaining / VALUES[i];
            if (count <= 0) continue;
            remaining -= count * VALUES[i];
            if (giveItem(player, ResourceLocation.fromNamespaceAndPath("numismatics", NAMES[i]), count)) {
                gaveAny = true;
            }
        }
        return gaveAny;
    }

    /**
     * Gives {@code count} of any item id, splitting across stacks and dropping the overflow at the
     * player's feet rather than deleting it. Returns false if the item does not exist.
     */
    public static boolean giveItem(ServerPlayer player, ResourceLocation id, int count) {
        if (player == null || count <= 0) return false;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) return false;   // mod absent, or a typo'd id
        int max = new ItemStack(item).getMaxStackSize();
        int remaining = count;
        while (remaining > 0) {
            int n = Math.min(remaining, max);
            ItemStack stack = new ItemStack(item, n);
            if (!player.getInventory().add(stack)) player.drop(stack, false);
            remaining -= n;
        }
        return true;
    }
}
