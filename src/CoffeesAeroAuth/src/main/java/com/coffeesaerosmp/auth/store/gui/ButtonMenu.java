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

    public ButtonMenu(int id, Inventory inv, int rows, SimpleContainer box, Handler handler) {
        super(type(rows), id);
        this.size = rows * 9;
        this.handler = handler;
        for (int i = 0; i < size; i++) addSlot(new ButtonSlot(box, i));
        int yOff = (rows - 4) * 18;
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) addSlot(new Locked(inv, c + r * 9 + 9, 8 + c * 18, 103 + r * 18 + yOff));
        for (int c = 0; c < 9; c++) addSlot(new Locked(inv, c, 8 + c * 18, 161 + yOff));
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
        if (slot >= 0 && slot < size) {
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
