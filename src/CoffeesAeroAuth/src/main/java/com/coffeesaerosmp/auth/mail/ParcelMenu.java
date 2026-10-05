package com.coffeesaerosmp.auth.mail;

import net.minecraft.network.chat.Component;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The parcel window: a 3-row chest whose FIRST row is a real 9-slot parcel and whose other two rows
 * are buttons. Vanilla client, no client mod.
 *
 * <h2>Item safety</h2>
 * <ul>
 *   <li>Parcel slots refuse anything {@link MailItems#containsBlocked} flags (Numismatics at any depth),
 *       on click, shift-click and drag alike — the slot's {@code mayPlace} is the single gate.</li>
 *   <li>Button slots can be neither filled nor emptied, so the display items can never be lifted
 *       (the same rule {@link MailMenu} enforces for the whole inbox).</li>
 *   <li>Whatever is still in the parcel when the window closes — Cancel, Esc, logout, death — goes
 *       back to the player, then the slots are cleared, so it can only ever come back once. A send
 *       clears the slots BEFORE the window closes, so sent items are not returned.</li>
 * </ul>
 * Slot indices match vanilla's GENERIC_9x3 layout: 0–26 this container, 27–53 inventory, 54–62 hotbar.
 */
public class ParcelMenu extends AbstractContainerMenu {

    public static final int PARCEL = MailRules.PARCEL_SLOTS;   // slots 0..8
    public static final int SIZE = 27;
    private static final int INV_START = SIZE, INV_END = SIZE + 36;

    private final SimpleContainer box;
    private final IntConsumer onButton;
    private final ServerPlayer owner;

    public ParcelMenu(int id, Inventory inv, SimpleContainer box, ServerPlayer owner, IntConsumer onButton) {
        super(MenuType.GENERIC_9x3, id);
        this.box = box;
        this.owner = owner;
        this.onButton = onButton;
        for (int i = 0; i < SIZE; i++) {
            addSlot(i < PARCEL ? new ParcelSlot(box, i) : new ButtonSlot(box, i));
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 84 + r * 18));
        }
        for (int c = 0; c < 9; c++) addSlot(new Slot(inv, c, 8 + c * 18, 142));
    }

    /** Copies of everything currently packed. */
    public List<ItemStack> packed() {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < PARCEL; i++) if (!box.getItem(i).isEmpty()) out.add(box.getItem(i).copy());
        return out;
    }

    /** Empties the parcel WITHOUT returning anything — the items now belong to the mail. */
    public void takeAll() {
        for (int i = 0; i < PARCEL; i++) box.setItem(i, ItemStack.EMPTY);
    }

    /** Gives back whatever is packed (inventory, else at their feet) and empties the parcel. Idempotent. */
    public void returnItems(Player player) {
        for (int i = 0; i < PARCEL; i++) {
            ItemStack s = box.removeItemNoUpdate(i);
            if (s.isEmpty()) continue;
            if (!player.getInventory().add(s)) player.drop(s, false);
        }
    }

    @Override
    public void clicked(int slot, int button, ClickType type, Player player) {
        if (slot >= PARCEL && slot < SIZE) {                      // a button
            if (type == ClickType.PICKUP || type == ClickType.QUICK_MOVE) onButton.accept(slot);
            this.sendAllDataToRemote();
            return;
        }
        if (slot >= 0 && slot < PARCEL && type == ClickType.PICKUP
                && MailItems.containsBlocked(getCarried(), owner.registryAccess())) {
            refuse();
        }
        super.clicked(slot, button, type, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        if (index < PARCEL) {
            if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else if (index >= INV_START) {
            if (MailItems.containsBlocked(stack, owner.registryAccess())) { refuse(); return ItemStack.EMPTY; }
            if (!moveItemStackTo(stack, 0, PARCEL, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;                               // buttons never move
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        return before;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        returnItems(player);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    private void refuse() {
        owner.sendSystemMessage(Component.literal(
            "§c✉ Coins and other Numismatics items can't be mailed §7— they only change hands in the world. "
            + "§7Meet up, or use a shop or vendor."));
    }

    private final class ParcelSlot extends Slot {
        ParcelSlot(Container c, int i) { super(c, i, 8 + i * 18, 18); }
        @Override public boolean mayPlace(ItemStack s) { return !MailItems.containsBlocked(s, owner.registryAccess()); }
    }

    private static final class ButtonSlot extends Slot {
        ButtonSlot(Container c, int i) { super(c, i, 8 + (i % 9) * 18, 18 + (i / 9) * 18); }
        @Override public boolean mayPlace(ItemStack s) { return false; }
        @Override public boolean mayPickup(Player p) { return false; }
    }
}
