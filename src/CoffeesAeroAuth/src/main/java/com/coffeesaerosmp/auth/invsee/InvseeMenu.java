package com.coffeesaerosmp.auth.invsee;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

/**
 * The container menu behind {@code /invsee}, {@code /invsee_echest} and {@code /invsee_curios}.
 *
 * <p>Implements {@link Marker} so {@code PlayerRestrictEvents}' lobby menu-slam guard can exempt it.
 * That guard closes any menu which is not the viewer's own inventory, for anyone below permission 4,
 * and would otherwise slam an invsee shut on the very next tick.
 *
 * <h2>🔴 Why read-only mode blocks EVERY click, not just container clicks</h2>
 * A read-only view is backed by a detached snapshot that is discarded when the menu closes. If the
 * viewer could click at all, they could drag or shift-click their OWN items INTO that snapshot — and
 * those items would be destroyed on close, silently, with the admin having done nothing obviously
 * wrong. So read-only swallows all clicks and {@link #quickMoveStack} returns empty. The cost is
 * that an admin cannot rearrange their own inventory while a snapshot is open; that is a fair price
 * for not eating their gear.
 */
public class InvseeMenu extends ChestMenu {

    /** Marker for the lobby menu-slam exemption. */
    public interface Marker {}

    /** The concrete type actually opened; carries {@link Marker}. */
    public static class Marked extends InvseeMenu implements Marker {
        Marked(MenuType<?> type, int id, Inventory viewer, Container backing, int rows, boolean readOnly) {
            super(type, id, viewer, backing, rows, readOnly);
        }
    }

    private final boolean readOnly;

    protected InvseeMenu(MenuType<?> type, int id, Inventory viewer, Container backing,
                         int rows, boolean readOnly) {
        super(type, id, viewer, backing, rows);
        this.readOnly = readOnly;
    }

    /** 45 slots — the vanilla inventory view. */
    public static Marked fiveRows(int id, Inventory viewer, Container backing, boolean readOnly) {
        return new Marked(MenuType.GENERIC_9x5, id, viewer, backing, 5, readOnly);
    }

    /** 27 slots — the ender chest, and small curios sets. */
    public static Marked threeRows(int id, Inventory viewer, Container backing, boolean readOnly) {
        return new Marked(MenuType.GENERIC_9x3, id, viewer, backing, 3, readOnly);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (readOnly) return;                       // see the class javadoc — this prevents item loss
        // Inert padding: only container slots are checked. Slot indices at or beyond the container
        // size belong to the VIEWER's own inventory and must keep working normally.
        if (slot >= 0 && slot < this.getContainer().getContainerSize()
                && InvseeSlotMap.isInert(slot)) {
            return;
        }
        super.clicked(slot, button, type, player);
    }

    /** Shift-click. In read-only mode this would move the viewer's items into a doomed snapshot. */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (readOnly) return ItemStack.EMPTY;
        return super.quickMoveStack(player, index);
    }

    @Override
    public boolean stillValid(Player player) {
        return this.getContainer().stillValid(player);
    }
}
