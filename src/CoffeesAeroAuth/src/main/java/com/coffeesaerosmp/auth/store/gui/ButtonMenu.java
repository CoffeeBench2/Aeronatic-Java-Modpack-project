package com.coffeesaerosmp.auth.store.gui;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A chest window made only of buttons (vanilla client, no mod needed). Nothing in the top part can be taken or
 * placed; a click is reported to {@link Handler} as left / right / shift instead, and the window is resent so a
 * client-side pickup prediction snaps back. The player's own inventory is shown but inert (no shift-moving in).
 */
public class ButtonMenu extends AbstractContainerMenu {

    public enum Click { LEFT, RIGHT, SHIFT }

    @FunctionalInterface
    public interface Handler { void clicked(int slot, Click click); }

    private final int size;
    private final Handler handler;
    /** The inventory area is buttons too (pack Wardrobe); the player's real items are re-sent when it closes. */
    private final boolean hidesInventory;

    public ButtonMenu(int id, Inventory inv, int rows, SimpleContainer box, Handler handler) {
        this(id, inv, rows, box, handler, null);
    }

    /**
     * @param lower when not null, the 36 inventory slots show THIS container (27 rows then 9 hotbar) instead of the
     *              player's items, and clicks there are buttons as well. The client writes what it's sent into its
     *              own copy of the inventory, so closing the window re-sends the real one (see {@link #removed}).
     *              The server-side inventory is never touched.
     */
    public ButtonMenu(int id, Inventory inv, int rows, SimpleContainer box, Handler handler, SimpleContainer lower) {
        super(type(rows), id);
        this.size = rows * 9;
        this.handler = handler;
        this.hidesInventory = lower != null;
        for (int i = 0; i < size; i++) addSlot(new ButtonSlot(box, i));
        int yOff = (rows - 4) * 18;
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) {
            int x = 8 + c * 18, y = 103 + r * 18 + yOff;
            addSlot(lower != null ? new Locked(lower, c + r * 9, x, y) : new Locked(inv, c + r * 9 + 9, x, y));
        }
        for (int c = 0; c < 9; c++) {
            int x = 8 + c * 18, y = 161 + yOff;
            addSlot(lower != null ? new Locked(lower, 27 + c, x, y) : new Locked(inv, c, x, y));
        }
    }

    private boolean handedOff;

    public boolean hidesInventory() { return hidesInventory; }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // The client drew our buttons into its copy of the inventory: put the real items back on its screen
        // (unless another button window is replacing this one; it sends its own lower slots next).
        if (hidesInventory && !handedOff) player.inventoryMenu.sendAllDataToRemote();
    }

    /**
     * Open {@code provider}, replacing a button window WITHOUT the close packet in between. openMenu would close
     * the old window first, and a close/open pair makes the client re-centre the cursor: every click in a list
     * would throw the mouse to the middle of the screen. Opening straight over the old screen keeps it put.
     */
    public static void open(net.minecraft.server.level.ServerPlayer p, net.minecraft.world.MenuProvider provider,
                            boolean nextHidesInventory) {
        if (p.containerMenu instanceof ButtonMenu old) {
            old.handedOff = nextHidesInventory && old.hidesInventory;
            p.doCloseContainer();                       // server side only: no ClientboundContainerClosePacket
        }
        p.openMenu(provider);
    }

    static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot >= 0 && slot < (hidesInventory ? size + 36 : size)) {
            Click c = null;
            if (type == ClickType.QUICK_MOVE) c = Click.SHIFT;
            else if (type == ClickType.PICKUP) c = button == 1 ? Click.RIGHT : Click.LEFT;
            if (c != null) {
                try { handler.clicked(slot, c); } catch (Exception ignored) { }
            }
        }
        // Never move anything; resync so the client's predicted pickup snaps back.
        this.sendAllDataToRemote();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    private static final class ButtonSlot extends Slot {
        ButtonSlot(Container c, int i) { super(c, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18); }
        @Override public boolean mayPlace(ItemStack s) { return false; }
        @Override public boolean mayPickup(Player p) { return false; }
    }

    private static final class Locked extends Slot {
        Locked(Container c, int i, int x, int y) { super(c, i, x, y); }
        @Override public boolean mayPickup(Player p) { return false; }
        @Override public boolean mayPlace(ItemStack s) { return false; }
    }
}
