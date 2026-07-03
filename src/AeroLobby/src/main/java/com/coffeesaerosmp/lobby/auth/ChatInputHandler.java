package com.coffeesaerosmp.lobby.auth;

import com.coffeesaerosmp.lobby.AeroLobbyPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public class ChatInputHandler implements Listener {

    private final AeroLobbyPlugin plugin;
    private final LobbyAuthManager auth;

    public ChatInputHandler(AeroLobbyPlugin plugin, LobbyAuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!auth.hasPendingInput(player.getUniqueId())) return;

        event.setCancelled(true);
        String input = event.getMessage().trim();
        LobbyAuthManager.ChatInputState state = auth.getPendingInput(player.getUniqueId());

        switch (state) {
            case AWAITING_DISPLAY_NAME -> handleDisplayName(player, input);
            case AWAITING_NEW_PASSWORD -> handleNewPassword(player, input);
            case AWAITING_LOGIN_PASSWORD -> handleLoginPassword(player, input);
        }
    }

    private void handleDisplayName(Player player, String name) {
        if (name.length() < 3 || name.length() > 16) {
            player.sendMessage(Component.text("Display name must be 3–16 characters. Try again:", NamedTextColor.RED));
            return;
        }
        if (!name.matches("[a-zA-Z0-9_]+")) {
            player.sendMessage(Component.text("Only letters, numbers, and underscores allowed. Try again:", NamedTextColor.RED));
            return;
        }
        if (auth.isDisplayNameTaken(name)) {
            player.sendMessage(Component.text("\"" + name + "\" is already taken. Choose another:", NamedTextColor.RED));
            return;
        }

        auth.setPendingDisplayName(player.getUniqueId(), name);
        auth.clearPendingInput(player.getUniqueId());
        auth.setPendingInput(player.getUniqueId(), LobbyAuthManager.ChatInputState.AWAITING_NEW_PASSWORD);

        player.sendMessage(Component.text("Display name: ", NamedTextColor.GRAY)
            .append(Component.text(name, NamedTextColor.AQUA)));
        player.sendMessage(Component.text("Now choose a password:", NamedTextColor.WHITE));
    }

    private void handleNewPassword(Player player, String password) {
        if (password.length() < 6) {
            player.sendMessage(Component.text("Password must be at least 6 characters. Try again:", NamedTextColor.RED));
            return;
        }

        String displayName = auth.getPendingDisplayName(player.getUniqueId());
        auth.clearPendingInput(player.getUniqueId());
        auth.clearPendingDisplayName(player.getUniqueId());

        boolean registered = auth.registerCracked(player, displayName, password);
        if (registered) {
            player.sendMessage(Component.text("Account created! Welcome, ", NamedTextColor.GREEN)
                .append(Component.text(displayName, NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.GREEN)));

            // Run sync so plugin messaging works on main thread
            plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.sendAuthComplete(player));
        } else {
            player.sendMessage(Component.text("Registration failed — display name was just taken. Type a new one:", NamedTextColor.RED));
            auth.setPendingInput(player.getUniqueId(), LobbyAuthManager.ChatInputState.AWAITING_DISPLAY_NAME);
        }
    }

    private void handleLoginPassword(Player player, String password) {
        auth.clearPendingInput(player.getUniqueId());
        boolean ok = auth.loginCracked(player, password);
        if (ok) {
            player.sendMessage(Component.text("Logged in! Connecting...", NamedTextColor.GREEN));
            plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.sendAuthComplete(player));
        } else {
            player.sendMessage(Component.text("Wrong password. Try again:", NamedTextColor.RED));
            auth.setPendingInput(player.getUniqueId(), LobbyAuthManager.ChatInputState.AWAITING_LOGIN_PASSWORD);
        }
    }
}
