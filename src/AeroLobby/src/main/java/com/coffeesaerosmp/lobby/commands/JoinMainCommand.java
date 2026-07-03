package com.coffeesaerosmp.lobby.commands;

import com.coffeesaerosmp.lobby.AeroLobbyPlugin;
import com.coffeesaerosmp.lobby.auth.LobbyAuthManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class JoinMainCommand implements CommandExecutor {

    private final AeroLobbyPlugin plugin;
    private final LobbyAuthManager auth;

    public JoinMainCommand(AeroLobbyPlugin plugin, LobbyAuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (!auth.isAuthenticated(player.getUniqueId())) {
            player.sendMessage(Component.text("You need to log in first.", NamedTextColor.RED));
            return true;
        }

        player.sendMessage(Component.text("Connecting to the server...", NamedTextColor.GRAY));
        plugin.sendAuthComplete(player);
        return true;
    }
}
