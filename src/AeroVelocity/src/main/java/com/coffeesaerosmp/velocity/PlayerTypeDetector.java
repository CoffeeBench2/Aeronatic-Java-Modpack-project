package com.coffeesaerosmp.velocity;

import java.util.concurrent.CompletableFuture;

/**
 * Decides what kind of account a connecting username represents, BEFORE the crypto handshake.
 *
 * <p>This is the abstraction the login flow depends on (Dependency Inversion): {@link PreLoginHandler}
 * is written against this interface, not against any concrete Mojang implementation, so the detection
 * strategy can be swapped or stubbed without touching the handler. The default implementation is
 * {@link MojangApiDetector}.</p>
 */
public interface PlayerTypeDetector {

    enum Decision {
        /** Name is a paid Mojang account → must be verified online; impostors get kicked. */
        PREMIUM,
        /** Name is not a Mojang account → connect offline (backend decides /login vs /register). */
        OFFLINE,
        /** Could not determine (Mojang down / rate-limited) → deny, never assume offline. */
        UNAVAILABLE
    }

    CompletableFuture<Decision> detect(String username);
}
