package com.coffeesaerosmp.auth.db;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProfileStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dataDir;
    private final Path profilesDir;
    private final Path nameIndexFile;

    private final Map<UUID, PlayerProfile> cache     = new ConcurrentHashMap<>();
    /** lower-cased display name → owner UUID */
    private final Map<String, UUID>        nameIndex = new ConcurrentHashMap<>();

    public record GetOrCreateResult(PlayerProfile profile, boolean isNew) {}

    public ProfileStore(Path dataDir) {
        this.dataDir       = dataDir;
        this.profilesDir   = dataDir.resolve("profiles");
        this.nameIndexFile = dataDir.resolve("displaynames.json");
    }

    public void initialize() {
        try {
            Files.createDirectories(profilesDir);
            loadNameIndex();
            CoffeesAeroAuth.LOGGER.info("ProfileStore ready at {}", dataDir);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("ProfileStore init failed", e);
        }
    }

    public void shutdown() {
        cache.values().forEach(this::writeProfile);
        writeNameIndex();
        CoffeesAeroAuth.LOGGER.info("ProfileStore flushed {} profiles", cache.size());
    }

    // ── Profile CRUD ─────────────────────────────────────────────────────────

    /**
     * Loads an existing profile or creates a new one in memory.
     * Does NOT register the display name — caller must call registerDisplayName()
     * (via DisplayNameManager.claimInitialName) for new profiles.
     */
    public GetOrCreateResult getOrCreate(UUID uuid, String username, PlayerProfile.AccountType type) {
        PlayerProfile existing = get(uuid);
        if (existing != null) {
            existing.username = username; // keep username current; display name is separate
            return new GetOrCreateResult(existing, false);
        }
        PlayerProfile fresh = new PlayerProfile(uuid, username, type);
        cache.put(uuid, fresh);
        return new GetOrCreateResult(fresh, true);
    }

    public PlayerProfile get(UUID uuid) {
        PlayerProfile cached = cache.get(uuid);
        if (cached != null) return cached;

        Path f = profileFile(uuid);
        if (!Files.exists(f)) return null;

        try (Reader r = Files.newBufferedReader(f)) {
            PlayerProfile p = GSON.fromJson(r, PlayerProfile.class);
            if (p != null) {
                p.uuid = uuid; // restore transient field
                cache.put(uuid, p);
            }
            return p;
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to read profile {}", uuid, e);
            return null;
        }
    }

    public void save(PlayerProfile profile) {
        cache.put(profile.getUUID(), profile);
        writeProfile(profile);
    }

    private void writeProfile(PlayerProfile p) {
        try (Writer w = Files.newBufferedWriter(profileFile(p.getUUID()))) {
            GSON.toJson(p, w);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to write profile {}", p.getUUID(), e);
        }
    }

    private Path profileFile(UUID uuid) {
        return profilesDir.resolve(uuid + ".json");
    }

    /** Exposed for WatchdogManager's premium name index scan. */
    public Path profilesDir() { return profilesDir; }

    // ── Display Name Index ────────────────────────────────────────────────────

    public boolean isDisplayNameTaken(String name) {
        return nameIndex.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public UUID getDisplayNameOwner(String name) {
        return nameIndex.get(name.toLowerCase(Locale.ROOT));
    }

    public void registerDisplayName(String name, UUID uuid) {
        nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
        writeNameIndex();
    }

    public void releaseDisplayName(String name) {
        if (name == null) return;
        nameIndex.remove(name.toLowerCase(Locale.ROOT));
        writeNameIndex();
    }

    @SuppressWarnings("unchecked")
    private void loadNameIndex() {
        if (!Files.exists(nameIndexFile)) return;
        try (Reader r = Files.newBufferedReader(nameIndexFile)) {
            Map<String, String> raw = GSON.fromJson(r, Map.class);
            if (raw != null) raw.forEach((k, v) -> nameIndex.put(k, UUID.fromString(v)));
            CoffeesAeroAuth.LOGGER.info("Loaded {} display names", nameIndex.size());
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to load display name index", e);
        }
    }

    private void writeNameIndex() {
        Map<String, String> raw = new LinkedHashMap<>();
        nameIndex.forEach((k, v) -> raw.put(k, v.toString()));
        try (Writer w = Files.newBufferedWriter(nameIndexFile)) {
            GSON.toJson(raw, w);
        } catch (IOException e) {
            CoffeesAeroAuth.LOGGER.error("Failed to save display name index", e);
        }
    }
}
