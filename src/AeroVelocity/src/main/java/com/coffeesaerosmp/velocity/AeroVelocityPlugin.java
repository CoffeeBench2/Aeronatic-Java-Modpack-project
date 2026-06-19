package com.coffeesaerosmp.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Composition root for AeroVelocity. Builds the collaborators and wires them together — each class
 * has a single responsibility (detection, caching, Mojang I/O, pre-login policy, downstream tagging).
 */
@Plugin(
    id = "aerovelocity",
    name = "AeroVelocity",
    version = "1.0.0",
    description = "Premium/offline detection and routing for Coffees Aero SMP",
    authors = {"MrCoffeeBench"}
)
public class AeroVelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final PlayerDataStore dataStore;

    @Inject
    public AeroVelocityPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
        this.dataStore = new PlayerDataStore();
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(ForwardingTagger.PLAYER_TYPE);

        String secret = loadForwardingSecret();

        // Detection stack (Dependency Inversion: handler depends on the interface).
        PremiumNameCache  cache    = new PremiumNameCache();
        MojangApiClient   mojang   = new MojangApiClient();
        PlayerTypeDetector detector = new MojangApiDetector(mojang, cache, logger);

        server.getEventManager().register(this, new PreLoginHandler(detector, dataStore, logger));
        server.getEventManager().register(this, new ForwardingTagger(dataStore, logger, secret));

        // Housekeeping: drop stale cache entries hourly.
        server.getScheduler()
            .buildTask(this, cache::purgeExpired)
            .repeat(1, TimeUnit.HOURS)
            .schedule();

        logger.info("AeroVelocity started — premium/offline detection active (forwarding secret {}).",
            secret.isEmpty() ? "DISABLED — set AERO_FORWARDING_SECRET to enable" : "enabled");
    }

    /** Shared secret echoed to the backend so it can reject spoofed player_type messages. */
    private static String loadForwardingSecret() {
        String s = System.getenv("AERO_FORWARDING_SECRET");
        if (s == null || s.isBlank()) s = System.getProperty("aero.forwarding.secret", "");
        return s == null ? "" : s.trim();
    }

    public ProxyServer getServer() { return server; }
    public Logger getLogger() { return logger; }
    public PlayerDataStore getDataStore() { return dataStore; }
}
