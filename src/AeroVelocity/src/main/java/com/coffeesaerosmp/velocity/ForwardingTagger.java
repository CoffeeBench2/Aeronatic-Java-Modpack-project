package com.coffeesaerosmp.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Single responsibility: attach the resolved player type to the connection and forward it downstream.
 *
 * <p>After {@link PreLoginHandler} picks online/offline mode, the player's final UUID is known at
 * {@link PostLoginEvent} (v4 = Mojang/premium, v3 = offline). That type is recorded in the
 * {@link PlayerDataStore} and pushed to the {@code main} backend over the {@code aerosmp:player_type}
 * plugin channel on {@link ServerConnectedEvent}.</p>
 *
 * <p>This plugin-message route is intentionally retained as the proven fallback for telling the
 * backend premium-vs-offline when modern forwarding is not carrying the real UUID. Once NeoVelocity
 * modern forwarding is verified on the live stack, the backend can instead read the forwarded UUID
 * version directly; until then this tag is the source of truth.</p>
 */
public class ForwardingTagger {

    public static final com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier PLAYER_TYPE =
        com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier.from("aerosmp:player_type");

    private final PlayerDataStore dataStore;
    private final Logger logger;
    private final String sharedSecret;

    public ForwardingTagger(PlayerDataStore dataStore, Logger logger, String sharedSecret) {
        this.dataStore = dataStore;
        this.logger = logger;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (uuid.version() == 4) {
            dataStore.setType(uuid, PlayerDataStore.PlayerType.PREMIUM);
            logger.info("[AeroVelocity] {} confirmed premium (UUID v4).", player.getUsername());
        } else {
            dataStore.setType(uuid, PlayerDataStore.PlayerType.CRACKED);
            logger.info("[AeroVelocity] {} confirmed offline (UUID v{}).", player.getUsername(), uuid.version());
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        RegisteredServer target = event.getServer();
        if (!target.getServerInfo().getName().equals("main")) return;

        Player player = event.getPlayer();
        String type = dataStore.isPremium(player.getUniqueId()) ? "premium" : "cracked";
        String message = sharedSecret.isEmpty() ? type : type + ":" + sharedSecret;
        target.sendPluginMessage(PLAYER_TYPE, message.getBytes(StandardCharsets.UTF_8));
        logger.info("[AeroVelocity] {} → main tagged as {}.", player.getUsername(), type);
    }
}
