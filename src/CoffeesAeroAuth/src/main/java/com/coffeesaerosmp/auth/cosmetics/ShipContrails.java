package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contrails fitted to a specific ship, like a paint job (owner 2026-10-13: "apply our contrails to our ship"). The
 * ship's AeroClaims owner fits a contrail they own with {@code /shipcontrail}; the ship then streams it whenever it
 * flies, whoever is aboard (even nobody). Ships without one fall back to the rider rule: the contrail worn by a player
 * aboard.
 *
 * <p>Stored per Sable ship id in {@code ship_contrails.json} in the auth data dir (inside the world, like the ships
 * themselves). Writes are off-thread and atomic (temp file + move).
 */
public final class ShipContrails {

    private ShipContrails() {}

    /** A fitted contrail and who fitted it (the claim owner at the time). */
    public record Fit(String contrail, UUID owner, long at) {}

    private static final Map<UUID, Fit> FITS = new ConcurrentHashMap<>();
    private static volatile Path file;

    public static void initialize(Path dataDir) {
        file = dataDir.resolve("ship_contrails.json");
        FITS.clear();
        if (!Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var e : o.entrySet()) {
                JsonObject v = e.getValue().getAsJsonObject();
                FITS.put(UUID.fromString(e.getKey()), new Fit(v.get("contrail").getAsString(),
                    UUID.fromString(v.get("owner").getAsString()), v.has("at") ? v.get("at").getAsLong() : 0));
            }
            CoffeesAeroAuth.LOGGER.info("[ShipContrails] Loaded {} fitted contrails.", FITS.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[ShipContrails] ship_contrails.json load failed: {}", e.getMessage());
        }
    }

    public static Fit get(UUID ship) { return ship == null ? null : FITS.get(ship); }

    /** Everything fitted (a copy). */
    public static Map<UUID, Fit> all() { return Map.copyOf(FITS); }

    public static void fit(UUID ship, String contrail, UUID owner) {
        FITS.put(ship, new Fit(contrail, owner, System.currentTimeMillis()));
        save();
        CosmeticSync.markDirty();
    }

    public static boolean clear(UUID ship) {
        boolean had = FITS.remove(ship) != null;
        if (had) { save(); CosmeticSync.markDirty(); }
        return had;
    }

    private static void save() {
        Path f = file;
        if (f == null) return;
        JsonObject o = new JsonObject();
        for (var e : FITS.entrySet()) {
            JsonObject v = new JsonObject();
            v.addProperty("contrail", e.getValue().contrail());
            v.addProperty("owner", e.getValue().owner().toString());
            v.addProperty("at", e.getValue().at());
            o.add(e.getKey().toString(), v);
        }
        String json = o.toString();
        AsyncIo.submit(() -> {
            try {
                Files.createDirectories(f.getParent());
                Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                Files.writeString(tmp, json);
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.warn("[ShipContrails] save failed: {}", e.getMessage());
            }
        });
    }
}
