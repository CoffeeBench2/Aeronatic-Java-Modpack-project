package com.coffeesaerosmp.lobby.gui;

import com.coffeesaerosmp.lobby.AeroLobbyPlugin;
import com.coffeesaerosmp.lobby.auth.LobbyAuthManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class GuiListener implements Listener {

    private final AeroLobbyPlugin plugin;
    private final LobbyAuthManager auth;

    public GuiListener(AeroLobbyPlugin plugin, LobbyAuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
            .plainText().serialize(event.getView().title());

        if (title.startsWith("Coffees Aero SMP")) {
            event.setCancelled(true);
            int slot = event.getRawSlot();

            if (slot == LoginGUI.SLOT_LOGIN) {
                player.closeInventory();
                player.sendMessage(Component.text("─── Login ───", NamedTextColor.GRAY));
                player.sendMessage(Component.text("Type your password in chat:", NamedTextColor.WHITE));
                auth.setPendingInput(player.getUniqueId(), LobbyAuthManager.ChatInputState.AWAITING_LOGIN_PASSWORD);
            } else if (slot == LoginGUI.SLOT_REGISTER) {
                player.closeInventory();
                player.sendMessage(Component.text("─── Create Account ───", NamedTextColor.GRAY));
                player.sendMessage(Component.text("Choose a display name (shown to other players):", NamedTextColor.WHITE));
                auth.setPendingInput(player.getUniqueId(), LobbyAuthManager.ChatInputState.AWAITING_DISPLAY_NAME);
            }
        } else if (title.startsWith("Welcome back")) {
            event.setCancelled(true);
            if (event.getRawSlot() == LoginGUI.SLOT_ENTER) {
                player.closeInventory();
                plugin.sendAuthComplete(player);
            }
        }
    }
}
