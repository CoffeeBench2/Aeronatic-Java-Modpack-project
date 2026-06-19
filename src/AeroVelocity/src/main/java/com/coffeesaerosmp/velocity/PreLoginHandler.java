package com.coffeesaerosmp.velocity;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Single responsibility: wire a {@link PlayerTypeDetector.Decision} to Velocity's connection mode.
 *
 * <p>Depends on the {@link PlayerTypeDetector} abstraction, not the Mojang implementation.</p>
 *
 * <ul>
 *   <li>{@link PlayerTypeDetector.Decision#PREMIUM} → {@code forceOnlineMode()}. Mojang verifies the
 *       session; a cracked client using a premium name fails the handshake and is kicked. There is no
 *       "try online then fall back offline" mode in Velocity, and that is the secure behaviour we want.</li>
 *   <li>{@link PlayerTypeDetector.Decision#OFFLINE} → {@code forceOfflineMode()}. The backend decides
 *       {@code /login} (returning) vs {@code /register} (new) from its credential store.</li>
 *   <li>{@link PlayerTypeDetector.Decision#UNAVAILABLE} → {@code denied(...)} with a try-again message.
 *       We never assume offline for an indeterminate name — that would let an outage unlock impersonation.</li>
 * </ul>
 */
public class PreLoginHandler {

    private final PlayerTypeDetector detector;
    private final PlayerDataStore dataStore;
    private final Logger logger;

    public PreLoginHandler(PlayerTypeDetector detector, PlayerDataStore dataStore, Logger logger) {
        this.detector = detector;
        this.dataStore = dataStore;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPreLogin(PreLoginEvent event, Continuation continuation) {
        String username = event.getUsername();

        detector.detect(username).thenAccept(decision -> {
            switch (decision) {
                case PREMIUM -> {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
                    logger.info("[AeroVelocity] {} → premium name, forcing Mojang verification.", username);
                }
                case OFFLINE -> {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
                    dataStore.setType(offlineUUID(username), PlayerDataStore.PlayerType.CRACKED);
                    logger.info("[AeroVelocity] {} → offline name, allowing offline mode.", username);
                }
                case UNAVAILABLE -> {
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(authUnavailableMessage()));
                    logger.warn("[AeroVelocity] {} → denied (auth provider unavailable).", username);
                }
            }
            continuation.resume();
        }).exceptionally(e -> {
            // Defensive: any unexpected failure denies rather than silently allowing offline.
            logger.error("[AeroVelocity] Detection failed for {} — denying login.", username, e);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(authUnavailableMessage()));
            continuation.resume();
            return null;
        });
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        dataStore.remove(event.getPlayer().getUniqueId());
    }

    private static Component authUnavailableMessage() {
        return Component.text()
            .append(Component.text("Authentication temporarily unavailable\n\n",
                NamedTextColor.RED, TextDecoration.BOLD))
            .append(Component.text("We couldn't reach the login service to verify your account.\n",
                NamedTextColor.WHITE))
            .append(Component.text("Please try again in a minute.",
                NamedTextColor.YELLOW))
            .build();
    }

    private static UUID offlineUUID(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
