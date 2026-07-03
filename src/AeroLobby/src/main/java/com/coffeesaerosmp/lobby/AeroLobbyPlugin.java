package com.coffeesaerosmp.lobby;

import com.coffeesaerosmp.lobby.auth.ChatInputHandler;
import com.coffeesaerosmp.lobby.auth.LobbyAuthManager;
import com.coffeesaerosmp.lobby.commands.JoinMainCommand;
import com.coffeesaerosmp.lobby.commands.SpawnCommand;
import com.coffeesaerosmp.lobby.gui.GuiListener;
import com.coffeesaerosmp.lobby.listeners.PlayerJoinListener;
import com.coffeesaerosmp.lobby.listeners.PlayerQuitListener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

public class AeroLobbyPlugin extends JavaPlugin {

    private LobbyAuthManager authManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        authManager = new LobbyAuthManager(this);
        authManager.init();

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, authManager), this);
        getServer().getPluginManager().registerEvents(new GuiListener(this, authManager), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(authManager), this);
        getServer().getPluginManager().registerEvents(new ChatInputHandler(this, authManager), this);

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        getCommand("spawn").setExecutor(new SpawnCommand(this));
        getCommand("joinmain").setExecutor(new JoinMainCommand(this, authManager));

        getLogger().info("AeroLobby enabled");
    }

    @Override
    public void onDisable() {
        if (authManager != null) authManager.close();
    }

    /**
     * Routes this player from the lobby to the main server via the
     * BungeeCord "Connect" plugin message — Velocity handles it natively.
     */
    public void sendAuthComplete(org.bukkit.entity.Player player) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF("main");
            player.sendPluginMessage(this, "BungeeCord", b.toByteArray());
        } catch (Exception e) {
            getLogger().warning("Failed to connect " + player.getName() + " to main: " + e.getMessage());
        }
    }

    public LobbyAuthManager getAuthManager() { return authManager; }
}
