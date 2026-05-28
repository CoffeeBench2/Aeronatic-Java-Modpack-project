package com.coffeesaerosmp.auth.watchdog;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WatchdogEvent(
    Severity            severity,
    String              title,
    Map<String, String> fields,
    String              actionTaken,
    Instant             timestamp
) {
    /**
     * Convenience factory.
     * kvPairs: alternating "key", "value" strings, then an optional trailing action.
     * Usage: WatchdogEvent.of(CRITICAL, "UUID Switch", "Instant kick", "Player","coffee","IP","1.2.3.4")
     */
    public static WatchdogEvent of(Severity sev, String title, String action, String... kvPairs) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            fields.put(kvPairs[i], kvPairs[i + 1]);
        }
        return new WatchdogEvent(sev, title, Collections.unmodifiableMap(fields), action, Instant.now());
    }
}
