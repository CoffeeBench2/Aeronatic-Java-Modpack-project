package com.coffeesaerosmp.lobby.listeners;

import com.coffeesaerosmp.lobby.auth.LobbyAuthManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerQuitListener implements Listener {

    private final LobbyAuthManager auth;

    public PlayerQuitListener(LobbyAuthManager auth) {
        this.auth = auth;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        auth.remove(event.getPlayer().getUniqueId());
        event.quitMessage(null);
    }
}
