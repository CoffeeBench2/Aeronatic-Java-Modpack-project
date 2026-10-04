package com.coffeesaerosmp.auth.mail;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.Coins;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Item plumbing for mail: (de)serialising attachments, parsing config item specs, checking space,
 * handing things over.
 *
 * <p>Attachments are stored as SNBT of {@code {items:[<ItemStack.save>...]}} — the same encoding vanilla
 * uses for chests, so data components (enchantments, custom names, Create schematics, shulker contents)
 * survive the round trip. Needs the server's registry access to decode, hence the {@code registries}
 * argument everywhere.
 */
public final class MailItems {

    /** Coin stacks a spur payout can need at most (cog, sprocket, bevel, spur — see Coins). */
    private static final int COIN_STACKS = 4;

    private MailItems() {}

    public static String encode(List<ItemStack> items, HolderLookup.Provider registries) {
        if (items == null || items.isEmpty()) return null;
        ListTag list = new ListTag();
        for (ItemStack s : items) {
            if (s != null && !s.isEmpty()) list.add(s.save(registries));
        }
        if (list.isEmpty()) return null;
        CompoundTag root = new CompoundTag();
        root.put("items", list);
        return root.toString();
    }

    /** Never throws: a corrupt row decodes to no items and is logged, rather than breaking the GUI. */
    public static List<ItemStack> decode(String snbt, HolderLookup.Provider registries) {
        List<ItemStack> out = new ArrayList<>();
        if (snbt == null || snbt.isBlank()) return out;
        try {
            CompoundTag root = TagParser.parseTag(snbt);
            ListTag list = root.getList("items", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                ItemStack s = ItemStack.parseOptional(registries, list.getCompound(i));
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Mail] could not decode attachment: {}", e.getMessage());
        }
        return out;
    }

    /**
     * {@code "minecraft:diamond*4"} → 4 diamonds. Unknown ids and malformed entries are skipped with a
     * warning: a typo in a reward list must not stop the rest of the reward.
     */
    public static List<ItemStack> parseSpecs(List<? extends String> specs) {
        List<ItemStack> out = new ArrayList<>();
        if (specs == null) return out;
        for (String raw : specs) {
            if (raw == null || raw.isBlank()) continue;
            String spec = raw.trim();
            int count = 1;
            int star = spec.lastIndexOf('*');
            if (star > 0) {
                try { count = Integer.parseInt(spec.substring(star + 1).trim()); }
                catch (NumberFormatException e) { count = -1; }
                spec = spec.substring(0, star).trim();
            }
            ResourceLocation id = ResourceLocation.tryParse(spec);
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
            if (count <= 0 || item == null || item == Items.AIR) {
                CoffeesAeroAuth.LOGGER.warn("[Mail] skipping bad item spec '{}' (want namespace:item*count).", raw);
                continue;
            }
            int max = new ItemStack(item).getMaxStackSize();
            while (count > 0) {                     // split into real stacks so the slot count is honest
                int n = Math.min(count, max);
                out.add(new ItemStack(item, n));
                count -= n;
            }
        }
        return out;
    }

    /** Main-inventory slots needed to receive these items plus a spur payout. */
    public static int slotsNeeded(List<ItemStack> items, int spurs) {
        return (items == null ? 0 : items.size()) + (spurs > 0 ? COIN_STACKS : 0);
    }

    public static int freeSlots(ServerPlayer player) {
        int free = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) if (inv.items.get(i).isEmpty()) free++;
        return free;
    }

    /** Hands everything over; anything that will not fit is dropped at the player's feet, never deleted. */
    public static void give(ServerPlayer player, List<ItemStack> items, int spurs) {
        for (ItemStack s : items) {
            ItemStack copy = s.copy();
            if (!player.getInventory().add(copy)) player.drop(copy, false);
        }
        if (spurs > 0) Coins.pay(player, spurs);
    }
}
