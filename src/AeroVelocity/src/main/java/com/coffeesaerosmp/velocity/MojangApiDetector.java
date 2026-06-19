package com.coffeesaerosmp.velocity;

import org.slf4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Default {@link PlayerTypeDetector}: a cache in front of the Mojang API.
 *
 * <p>Collaborators are injected (Dependency Inversion), so the cache and HTTP client can be
 * replaced independently. Policy:</p>
 * <ol>
 *   <li>Return a fresh cached decision if present.</li>
 *   <li>Otherwise query Mojang; cache and return definitive results.</li>
 *   <li>On {@code UNAVAILABLE}, fall back to a stale-but-definitive cached value if we have one,
 *       else surface {@code UNAVAILABLE} so the handler denies the login. We NEVER downgrade a
 *       premium-capable name to OFFLINE on an outage — that is the impersonation hole.</li>
 * </ol>
 */
public class MojangApiDetector implements PlayerTypeDetector {

    private final MojangApiClient client;
    private final PremiumNameCache cache;
    private final Logger logger;

    public MojangApiDetector(MojangApiClient client, PremiumNameCache cache, Logger logger) {
        this.client = client;
        this.cache = cache;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Decision> detect(String username) {
        Optional<MojangApiClient.Result> cached = cache.get(username);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(toDecision(cached.get()));
        }

        return client.lookup(username).thenApply(result -> {
            if (result.status() == MojangApiClient.Status.UNAVAILABLE) {
                // Outage: reuse a definitive cached value if one exists, otherwise deny.
                Optional<MojangApiClient.Result> stale = cache.get(username);
                if (stale.isPresent()) {
                    logger.warn("[AeroVelocity] Mojang unavailable for {} — serving cached {}.",
                        username, stale.get().status());
                    return toDecision(stale.get());
                }
                logger.warn("[AeroVelocity] Mojang unavailable for {} and no cache — denying login.", username);
                return Decision.UNAVAILABLE;
            }
            cache.put(username, result);
            return toDecision(result);
        });
    }

    private static Decision toDecision(MojangApiClient.Result r) {
        return switch (r.status()) {
            case PREMIUM     -> Decision.PREMIUM;
            case NOT_PREMIUM -> Decision.OFFLINE;
            case UNAVAILABLE -> Decision.UNAVAILABLE;
        };
    }
}
