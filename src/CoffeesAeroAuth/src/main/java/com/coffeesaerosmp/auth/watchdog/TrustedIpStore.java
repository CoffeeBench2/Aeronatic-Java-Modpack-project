package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Persisted store of up to N known IPs per offline player UUID. */
public class TrustedIpStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, List<String>>>(){}.getType();

    private final Path dataFile;
    private final Map<UUID, List<String>> store = new ConcurrentHashMap<>();

    public TrustedIpStore(Path dataDir) {
        this.dataFile = dataDir.resolve("trusted_ips.json");
    }

    public void initialize() {
        if (!Files.exists(dataFile)) return;
        try (Reader r = Files.newBufferedReader(dataFile)) {
            Map<String, List<String>> raw = GSON.fromJson(r, MAP_TYPE);
            if (raw != null) {
                raw.forEach((uuidStr, ips) -> {
                    try { store.put(UUID.fromString(uuidStr), new ArrayList<>(ips)); }
                    catch (IllegalArgumentException ignored) {}
                });
            }
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to load trusted IPs", e);
        }
    }

    public boolean isKnownIp(UUID uuid, String ip) {
        List<String> ips = store.get(uuid);
        return ips != null && ips.contains(ip);
    }

    /** Adds IP to the trusted list, evicting the oldest if over the cap. */
    public void addTrustedIp(UUID uuid, String ip) {
        store.compute(uuid, (k, list) -> {
            if (list == null) list = new ArrayList<>();
            if (!list.contains(ip)) {
                list.add(ip);
                int max = AuthConfig.TRUSTED_IP_MAX_COUNT.get();
                while (list.size() > max) list.remove(0);
            }
            return list;
        });
        save();
    }

    public List<String> getTrustedIps(UUID uuid) {
        return Collections.unmodifiableList(store.getOrDefault(uuid, List.of()));
    }

    public void clearIps(UUID uuid) {
        store.remove(uuid);
        save();
    }

    private void save() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        store.forEach((uuid, ips) -> raw.put(uuid.toString(), ips));
        try (Writer w = Files.newBufferedWriter(dataFile)) {
            GSON.toJson(raw, w);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to save trusted IPs", e);
        }
    }
}
