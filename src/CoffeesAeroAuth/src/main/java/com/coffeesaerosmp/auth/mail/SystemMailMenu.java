package com.coffeesaerosmp.auth.mail;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The {@code /mail system} window (GENERIC_9x3, vanilla client). Top row: buttons around one real
 * BOOK slot (a Book & Quill or written book — its pages are the mail). Middle row: nine real attachment
 * slots. Bottom row: buttons. Same item rules as {@link ParcelMenu}: buttons can never be lifted,
 * attachments refuse Numismatics at any depth.
 *
 * <p>The container belongs to the DRAFT, not to this window: closing the window to type a subject in chat
 * must not throw the book back into the inventory. {@link SystemMailCompose} decides on close whether the
 * items go home ({@link #returnItems}) or stay with the draft.
 */
public class SystemMailMenu extends AbstractContainerMenu {

    public static final int SIZE = 27;
    public static final int BOOK = 4;
    public static final int ATTACH_FIRST = 9, ATTACH_END = 18;     // [9, 18)
    private static final int INV_START = SIZE, INV_END = SIZE + 36;

    private final SimpleContainer box;
    private final ServerPlayer owner;
    private final IntConsumer onButton;
    private final Runnable onBookChanged;
    private final Runnable onClosed;

    public SystemMailMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer owner,
                          IntConsumer onButton, Runnable onBookChanged, Runnable onClosed) {
        super(MenuType.GENERIC_9x3, id);
        this.box = box;
        this.owner = owner;
        this.onButton = onButton;
        this.onBookChanged = onBookChanged;
        this.onClosed = onClosed;
        for (int i = 0; i < SIZE; i++) {
            if (i == BOOK) addSlot(new BookSlot(box, i));
            else if (i >= ATTACH_FIRST && i < ATTACH_END) addSlot(new AttachSlot(box, i));
            else addSlot(new ButtonSlot(box, i));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 84 + r * 18));
        }
        for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c, 8 + c * 18, 142));
    }

    public static boolean isButton(int slot) {
        return slot >= 0 && slot < SIZE && slot != BOOK && (slot < ATTACH_FIRST || slot >= ATTACH_END);
    }

    static boolean isBook(ItemStack s) {
        return s.is(Items.WRITABLE_BOOK) || s.is(Items.WRITTEN_BOOK);
    }

    /** Copies of the attachments. */
    public List<ItemStack> attachments() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = ATTACH_FIRST; i < ATTACH_END; i++) if (!box.getItem(i).isEmpty()) out.add(box.getItem(i).copy());
        return out;
    }

    /** Gives back the book and every attachment (inventory, else at their feet) and empties them. Idempotent. */
    public static void returnItems(SimpleContainer box, Player player) {
        for (int i = 0; i < SIZE; i++) {
            if (isButton(i)) continue;
            ItemStack s = box.removeItemNoUpdate(i);
            if (s.isEmpty()) continue;
            if (!player.getInventory().add(s)) player.drop(s, false);
        }
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (isButton(slot)) {
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) onButton.accept(slot);
            this.sendAllDataToRemote();
            return;
        }
        super.clicked(slot, button, type, player);
        onBookChanged.run();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index < SIZE) {
            if (isButton(index) || !moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else {
            boolean moved = false;
            if (isBook(stack) && box.getItem(BOOK).isEmpty()) moved = moveItemStackTo(stack, BOOK, BOOK + 1, false);
            if (!moved && !MailItems.containsBlocked(stack, owner.registryAccess()))
                moved = moveItemStackTo(stack, ATTACH_FIRST, ATTACH_END, false);
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return before;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        onClosed.run();
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    private static final class BookSlot extends Slot {
        BookSlot(Container c, int i) { super(c, i, 8 + i * 18, 18); }
        @Override public boolean mayPlace(ItemStack s) { return isBook(s); }
        @Override public int getMaxStackSize() { return 1; }
    }

    private final class AttachSlot extends Slot {
        AttachSlot(Container c, int i) { super(c, i, 8 + (i - ATTACH_FIRST) * 18, 36); }
        @Override public boolean mayPlace(ItemStack s) { return !MailItems.containsBlocked(s, owner.registryAccess()); }
    }

    private static final class ButtonSlot extends Slot {
        ButtonSlot(Container c, int i) { super(c, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18); }
        @Override public boolean mayPlace(ItemStack s) { return false; }
        @Override public boolean mayPickup(Player p) { return false; }
    }
}
