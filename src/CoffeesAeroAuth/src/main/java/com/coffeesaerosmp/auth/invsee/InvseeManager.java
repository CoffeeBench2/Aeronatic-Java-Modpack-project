package com.coffeesaerosmp.auth.invsee;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens invsee views and closes them when the target leaves.
 *
 * <p>A live view is backed by the target's real {@code Inventory}. If the target disconnects while a
 * menu is open, that menu is writing into an object vanilla is about to discard — so every viewer is
 * closed on logout. {@code PlayerLoggedOutEvent} firing BEFORE the save is exactly right here:
 * closing before the save is what we want.
 */
public final class InvseeManager {

    /** target uuid -> viewer uuids currently looking at them. */
    private static final Map<UUID, Set<UUID>> VIEWERS = new ConcurrentHashMap<>();

    private InvseeManager() {}

    public static void trackOpen(UUID target, UUID viewer) {
        VIEWERS.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet()).add(viewer);
    }

    /** Closes every open invsee menu pointed at this player. Call from the logout handler. */
    public static void closeViewersOf(MinecraftServer server, UUID target) {
        Set<UUID> viewers = VIEWERS.remove(target);
        if (viewers == null || server == null) return;
        for (UUID v : viewers) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(v);
            if (viewer != null && viewer.containerMenu instanceof InvseeMenu.Marker) {
                viewer.closeContainer();
                viewer.sendSystemMessage(Component.literal(
                    "§7The player you were viewing disconnected — view closed."));
            }
        }
    }

    /** Drops a departing viewer from every watch list, so the map cannot grow without bound. */
    public static void forgetViewer(UUID viewer) {
        VIEWERS.values().forEach(s -> s.remove(viewer));
        VIEWERS.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /**
     * Reads an OFFLINE player's inventory or ender chest out of their {@code .dat} into a detached
     * snapshot.
     *
     * <p>🔴 One-directional by design. This is never written back — writing an offline {@code .dat}
     * is how inventories get duped or lost, and the menu that shows this is read-only for the same
     * reason.
     *
     * @param enderChest true to read {@code EnderItems}, false to read {@code Inventory}
     * @return the snapshot, or null if there is no playerdata for this uuid
     */
    public static Container offlineSnapshot(MinecraftServer server, UUID uuid, boolean enderChest) {
        Path dat = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
        if (!Files.exists(dat)) return null;
        try {
            CompoundTag root = NbtIo.readCompressed(dat, NbtAccounter.unlimitedHeap());
            ListTag list = root.getList(enderChest ? "EnderItems" : "Inventory", Tag.TAG_COMPOUND);
            HolderLookup.Provider lookup = server.registryAccess();

            int size = enderChest ? 27 : InvseeSlotMap.VIEW_SIZE;
            SimpleContainer snap = new SimpleContainer(size);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag item = list.getCompound(i);
                int slot = item.getByte("Slot") & 255;
                // Vanilla stores armour and offhand in the SAME Inventory list, at slots 100-103
                // and 150. Left unmapped they fall outside the container and silently vanish from
                // the view — an admin would conclude the player has no armour.
                if (!enderChest) {
                    if (slot >= 100 && slot <= 103) slot = 36 + (slot - 100);
                    else if (slot == 150) slot = 40;
                }
                if (slot < 0 || slot >= size) continue;
                ItemStack stack = ItemStack.parse(lookup, item).orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) snap.setItem(slot, stack);
            }
            return snap;
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Invsee] offline read failed for {}: {}", uuid, e.toString());
            return null;
        }
    }
}
