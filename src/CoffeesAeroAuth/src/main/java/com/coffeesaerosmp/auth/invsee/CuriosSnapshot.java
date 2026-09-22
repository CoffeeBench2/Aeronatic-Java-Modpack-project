package com.coffeesaerosmp.auth.invsee;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A READ-ONLY snapshot of a player's Curios and Accessories slots.
 *
 * <h2>Why this exists separately from the vanilla view</h2>
 * Gear in these slots is invisible to {@code /invsee}, which reads the vanilla {@code Inventory}
 * only. This pack runs BOTH systems — {@code curios-9.5.1} and {@code accessories-1.1.0} — and the
 * codebase already knows it matters: the lobby menu-slam comment calls out a Sophisticated Backpack
 * riding in an Accessories slot where the vanilla stash cannot reach it. An admin checking a
 * suspected duper against the vanilla view alone would see a clean inventory and miss the container
 * holding everything.
 *
 * <h2>Reflection, and why</h2>
 * Neither mod is on this project's compile classpath, and adding their jars to {@code libs/} would
 * break a clean checkout — that directory is gitignored with several jars already untracked. Same
 * trade {@code SableShips} and {@code SimAssemblyContraptionMixin} already make.
 *
 * <p>Only the two static entry points are reflective. Everything after them uses REAL types:
 * Curios hands back a NeoForge {@link IItemHandler}, and an Accessories entry is a record with a
 * {@code stack()} accessor returning a vanilla {@code ItemStack}. Each system degrades independently
 * — if one is absent or its API moved, the other still reports.
 *
 * <h2>🔴 Online players only</h2>
 * Both APIs take a live {@code LivingEntity}. Offline data lives inside each mod's own private NBT
 * format, and writing a parser for two third-party formats would be guesswork that fails silently
 * when either changes. The command says so rather than showing an empty box that reads as "nothing
 * equipped".
 */
public final class CuriosSnapshot {

    /** Rows are capped at five; a player with more equipped than this is vanishingly unlikely. */
    private static final int MAX_SLOTS = InvseeSlotMap.VIEW_SIZE;

    private static Method curiosGetInventory;
    private static Method curiosGetEquipped;
    private static boolean curiosResolved;

    private static Method accessoriesGetOptionally;
    private static Method accessoriesGetAllEquipped;
    private static Method accessoriesStack;
    private static boolean accessoriesResolved;

    private CuriosSnapshot() {}

    /** What was found, so the caller can title the view and report honestly when it is empty. */
    public record Result(SimpleContainer container, int curios, int accessories) {
        public int total() { return curios + accessories; }
    }

    /**
     * Collects everything equipped in either system into a detached, copied container.
     *
     * <p>Stacks are {@link ItemStack#copy() copied}, never referenced. A live reference in a
     * container the admin can interact with is a route to mutating the player's real equipment,
     * which is exactly what a read-only view must not permit.
     */
    public static Result of(ServerPlayer target) {
        List<ItemStack> found = new ArrayList<>();
        int curios = collectCurios(target, found);
        int accessories = collectAccessories(target, found);

        int size = found.size() <= 27 ? 27 : MAX_SLOTS;
        SimpleContainer c = new SimpleContainer(size);
        for (int i = 0; i < found.size() && i < size; i++) c.setItem(i, found.get(i));
        if (found.size() > size) {
            CoffeesAeroAuth.LOGGER.warn("[Invsee] {} has {} equipped curios/accessories — only the "
                + "first {} are shown.", target.getGameProfile().getName(), found.size(), size);
        }
        return new Result(c, curios, accessories);
    }

    // ── Curios ──────────────────────────────────────────────────────────────

    private static int collectCurios(ServerPlayer target, List<ItemStack> out) {
        try {
            if (!curiosResolved) {
                curiosResolved = true;
                try {
                    Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
                    curiosGetInventory = api.getMethod("getCuriosInventory", LivingEntity.class);
                    Class<?> handler = Class.forName(
                        "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
                    curiosGetEquipped = handler.getMethod("getEquippedCurios");
                } catch (Throwable notPresent) {
                    curiosGetInventory = null;
                }
            }
            if (curiosGetInventory == null) return 0;

            Object opt = curiosGetInventory.invoke(null, target);
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) return 0;
            Object equipped = curiosGetEquipped.invoke(o.get());
            if (!(equipped instanceof IItemHandler h)) return 0;   // real NeoForge type

            int n = 0;
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack s = h.getStackInSlot(i);
                if (s != null && !s.isEmpty()) { out.add(s.copy()); n++; }
            }
            return n;
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[Invsee] Curios read failed for {}: {}",
                target.getGameProfile().getName(), t.toString());
            return 0;
        }
    }

    // ── Accessories ─────────────────────────────────────────────────────────

    private static int collectAccessories(ServerPlayer target, List<ItemStack> out) {
        try {
            if (!accessoriesResolved) {
                accessoriesResolved = true;
                try {
                    Class<?> cap = Class.forName(
                        "io.wispforest.accessories.api.AccessoriesCapability");
                    accessoriesGetOptionally = cap.getMethod("getOptionally", LivingEntity.class);
                    accessoriesGetAllEquipped = cap.getMethod("getAllEquipped");
                    accessoriesStack = Class.forName(
                        "io.wispforest.accessories.api.slot.SlotEntryReference").getMethod("stack");
                } catch (Throwable notPresent) {
                    accessoriesGetOptionally = null;
                }
            }
            if (accessoriesGetOptionally == null) return 0;

            Object opt = accessoriesGetOptionally.invoke(null, target);
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) return 0;
            Object list = accessoriesGetAllEquipped.invoke(o.get());
            if (!(list instanceof List<?> entries)) return 0;

            int n = 0;
            for (Object entry : entries) {
                if (entry == null) continue;
                Object s = accessoriesStack.invoke(entry);
                if (s instanceof ItemStack stack && !stack.isEmpty()) { out.add(stack.copy()); n++; }
            }
            return n;
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[Invsee] Accessories read failed for {}: {}",
                target.getGameProfile().getName(), t.toString());
            return 0;
        }
    }
}
