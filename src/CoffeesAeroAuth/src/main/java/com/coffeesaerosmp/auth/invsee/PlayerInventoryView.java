package com.coffeesaerosmp.auth.invsee;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A live 45-slot {@link Container} over another player's real {@code Inventory}.
 *
 * <p>Reads and writes pass STRAIGHT THROUGH — an admin removing an item removes it from the player,
 * and the player sees it go. Slots 41–44 are inert padding (see {@link InvseeSlotMap}) and render as
 * barriers.
 *
 * <p>Vanilla inventory only. Curios and Accessories live in a different container entirely and are
 * shown by {@code /invsee_curios}.
 */
public class PlayerInventoryView implements Container {

    private final ServerPlayer target;

    public PlayerInventoryView(ServerPlayer target) {
        this.target = target;
    }

    public ServerPlayer target() {
        return target;
    }

    /** Visual filler for the four unbacked slots. Clicks on them are swallowed by the menu. */
    private static ItemStack padding() {
        ItemStack s = new ItemStack(Items.BARRIER);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("§8— not an inventory slot —"));
        return s;
    }

    @Override
    public int getContainerSize() {
        return InvseeSlotMap.VIEW_SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < InvseeSlotMap.BACKED_SIZE; i++) {
            if (!target.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        int idx = InvseeSlotMap.toInventoryIndex(slot);
        return idx < 0 ? padding() : target.getInventory().getItem(idx);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        int idx = InvseeSlotMap.toInventoryIndex(slot);
        return idx < 0 ? ItemStack.EMPTY : target.getInventory().removeItem(idx, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        int idx = InvseeSlotMap.toInventoryIndex(slot);
        return idx < 0 ? ItemStack.EMPTY : target.getInventory().removeItemNoUpdate(idx);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        int idx = InvseeSlotMap.toInventoryIndex(slot);
        if (idx < 0) return;                       // padding swallows writes rather than shifting them
        target.getInventory().setItem(idx, stack);
    }

    /**
     * Pushes the change to the TARGET's own client.
     *
     * <p>Without this the admin sees the edit and the player does not, until something else happens
     * to resync them — which looks exactly like an item vanishing.
     */
    @Override
    public void setChanged() {
        target.getInventory().setChanged();
        target.containerMenu.broadcastChanges();
        target.inventoryMenu.broadcastChanges();
    }

    /** Valid only while the target is still actually connected. */
    @Override
    public boolean stillValid(Player player) {
        return !target.hasDisconnected();
    }

    /**
     * Deliberately unsupported.
     *
     * <p>"Clear this player's inventory" is not an invsee operation, and vanilla calls
     * {@code clearContent} in places an admin would never expect (container teardown paths). A
     * stray call here would be silent mass item loss with no audit trail.
     */
    @Override
    public void clearContent() {
    }
}
