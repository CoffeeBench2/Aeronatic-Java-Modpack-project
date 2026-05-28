package com.coffeesaerosmp.auth.util;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads key=value pairs from a .env file in the server's working directory.
 * Used to load secrets that must never be committed to source control.
 */
public final class EnvLoader {

    private EnvLoader() {}

    public static Map<String, String> load() {
        Path envFile = Path.of(System.getProperty("user.dir"), ".env");
        if (!Files.exists(envFile)) return Collections.emptyMap();

        Map<String, String> result = new HashMap<>();
        try {
            for (String raw : Files.readAllLines(envFile)) {
                String line = raw.strip();
                if (line.isBlank() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String val = line.substring(eq + 1).strip();
                // Strip surrounding quotes
                if (val.length() >= 2
                    && ((val.startsWith("\"") && val.endsWith("\""))
                        || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                if (!key.isEmpty()) result.put(key, val);
            }
            CoffeesAeroAuth.LOGGER.info("Loaded {} keys from .env", result.size());
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.warn("Could not read .env: {}", e.getMessage());
        }
        return Collections.unmodifiableMap(result);
    }
}
