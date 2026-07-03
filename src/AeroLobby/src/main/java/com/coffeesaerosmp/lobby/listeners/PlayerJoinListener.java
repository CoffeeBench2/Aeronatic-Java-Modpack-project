package com.coffeesaerosmp.lobby.listeners;

import com.coffeesaerosmp.lobby.AeroLobbyPlugin;
import com.coffeesaerosmp.lobby.auth.LobbyAuthManager;
import com.coffeesaerosmp.lobby.gui.LoginGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public class PlayerJoinListener implements Listener {

    private final AeroLobbyPlugin plugin;
    private final LobbyAuthManager auth;

    public PlayerJoinListener(AeroLobbyPlugin plugin, LobbyAuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        event.joinMessage(null); // suppress default join message

        UUID uuid = player.getUniqueId();

        if (isPremiumUUID(uuid)) {
            // Premium: register if first time, then show welcome GUI
            auth.registerPremium(player);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.openInventory(LoginGUI.buildPremiumWelcomeGUI(player));
                }
            }, 10L); // 0.5s delay so the player finishes loading in
        } else {
            // Cracked: show login or register GUI
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                if (auth.isCrackedRegistered(uuid)) {
                    player.openInventory(LoginGUI.buildLoginGUI(player));
                } else {
                    // New cracked player — go straight to register flow
                    player.sendMessage(Component.text("─── Create Account ───", NamedTextColor.GRAY));
                    player.sendMessage(Component.text("Welcome! Choose a display name:", NamedTextColor.WHITE));
                    auth.setPendingInput(uuid, LobbyAuthManager.ChatInputState.AWAITING_DISPLAY_NAME);
                }
            }, 10L);
        }
    }

    /**
     * UUID version 4 = randomly assigned by Mojang (premium).
     * UUID version 3 = derived from "OfflinePlayer:" + name (cracked/offline).
     */
    private boolean isPremiumUUID(UUID uuid) {
        return uuid.version() == 4;
    }
}
