package com.coffeesaerosmp.lobby.commands;

import com.coffeesaerosmp.lobby.AeroLobbyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Teleports the player to the Vista TV viewing spot in the lobby.
 * The admin places a Vista TV block in-game at the configured coordinates
 * showing a 360° recording of the main world spawn. This command sends
 * the player to stand in front of it.
 */
public class SpawnCommand implements CommandExecutor {

    private final AeroLobbyPlugin plugin;

    public SpawnCommand(AeroLobbyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        FileConfiguration config = plugin.getConfig();
        double x = config.getDouble("spawn-view.x", 0.5);
        double y = config.getDouble("spawn-view.y", 64.0);
        double z = config.getDouble("spawn-view.z", 0.5);
        float yaw = (float) config.getDouble("spawn-view.yaw", 0.0);
        float pitch = (float) config.getDouble("spawn-view.pitch", 15.0);

        Location viewSpot = new Location(player.getWorld(), x, y, z, yaw, pitch);
        player.teleport(viewSpot);

        // Title sequence — cinematic feel
        player.showTitle(Title.title(
            Component.text("Coffees Aero SMP", NamedTextColor.AQUA)
                .decoration(TextDecoration.BOLD, true),
            Component.text("Spawn Preview", NamedTextColor.GRAY),
            Title.Times.times(
                Duration.ofMillis(500),
                Duration.ofSeconds(3),
                Duration.ofMillis(1000)
            )
        ));

        player.sendMessage(Component.empty());
        player.sendMessage(
            Component.text("You're viewing the server spawn via ", NamedTextColor.GRAY)
                .append(Component.text("Vista TV", NamedTextColor.AQUA)
                    .decoration(TextDecoration.BOLD, true))
                .append(Component.text(".", NamedTextColor.GRAY))
        );
        player.sendMessage(
            Component.text("Ready to explore? ", NamedTextColor.GRAY)
                .append(Component.text("[Join the Server]", NamedTextColor.GREEN)
                    .decoration(TextDecoration.BOLD, true)
                    .clickEvent(ClickEvent.runCommand("/joinmain")))
        );
        player.sendMessage(Component.empty());

        return true;
    }
}
