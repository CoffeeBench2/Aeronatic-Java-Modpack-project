package com.coffeesaerosmp.auth.mail;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.function.IntConsumer;

/**
 * A 6-row chest that is a set of buttons, not storage. Vanilla client, no client mod.
 *
 * <p>🔴 EVERY click is swallowed — pickup, shift-click, number keys, double-click collect, drag — and
 * only the slot index is passed on. Letting even one vanilla click path through would let a player lift
 * the displayed attachment copies straight out of the GUI, which is a free item duplicator. Items are
 * only ever handed over by {@link MailGui} after the database claim succeeded. Same rule as invsee's
 * read-only mode.
 */
public class MailMenu extends ChestMenu {

    public static final int ROWS = 6;
    public static final int SIZE = ROWS * 9;

    private final IntConsumer onSlot;

    public MailMenu(int id, Inventory viewer, Container display, IntConsumer onSlot) {
        super(MenuType.GENERIC_9x6, id, viewer, display, ROWS);
        this.onSlot = onSlot;
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        // Only plain left/right clicks on OUR rows count as button presses.
        if (slot >= 0 && slot < SIZE && (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) {
            onSlot.accept(slot);
        }
        // Resync so the client's predicted move (it thinks it picked something up) is undone.
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
}
