package com.coffeesaerosmp.auth.tracking;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Records each player's AeroClaims footprint: claim slots used and free, summed across dimensions.
 *
 * <h2>Why slots and not "ships owned"</h2>
 * The original design called for ships owned and chunks claimed. AeroClaims exposes neither. Its
 * only public per-player accessors are three {@code (ServerLevel, UUID) -> int} statics —
 * {@code getUsedSlots}, {@code getFreeSlots}, {@code getMigratedSlots}. {@code Claim} does carry an
 * owner and a {@code shipId}, but the maps holding them are private to {@code AeroClaimSavedData},
 * so there is no owner → ships listing to read.
 *
 * <p>Recording what the API can actually answer beats a {@code ships_owned} column that would have
 * sat at zero forever and read as "this player owns no ships".
 *
 * <h2>Reflection</h2>
 * AeroClaims is not on the compile classpath. Resolved once; if the class or methods are missing the
 * sampler goes quiet after a single warning rather than writing zeros, because a zero here is
 * indistinguishable from "genuinely has no claims".
 */
public final class FootprintSampler {

    private static Method getUsedSlots;
    private static Method getFreeSlots;
    private static boolean resolved;
    private static boolean warned;

    private FootprintSampler() {}

    public static void sampleAll(MinecraftServer server) {
        if (server == null || !resolve()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID uuid = p.getUUID();
            int used = 0, free = 0;
            boolean any = false;
            // Claims are per-level saved data, and a player may hold them in more than one
            // dimension, so sum rather than reading only the level they happen to be standing in.
            for (ServerLevel level : server.getAllLevels()) {
                try {
                    Object u = getUsedSlots.invoke(null, level, uuid);
                    Object f = getFreeSlots.invoke(null, level, uuid);
                    if (u instanceof Integer ui) { used += ui; any = true; }
                    if (f instanceof Integer fi) { free += fi; any = true; }
                } catch (Exception ignored) {
                    // One bad level must not void the whole player's figure.
                }
            }
            if (!any) continue;                     // nothing readable — write nothing, not zero

            int finalUsed = used, finalFree = free;
            long now = System.currentTimeMillis();
            AsyncIo.submit(() -> {
                if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO player_footprint (uuid, claim_slots_used, claim_slots_free, " +
                         "sampled_epoch) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE " +
                         "claim_slots_used=VALUES(claim_slots_used), " +
                         "claim_slots_free=VALUES(claim_slots_free), " +
                         "sampled_epoch=VALUES(sampled_epoch)")) {
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, finalUsed);
                    ps.setInt(3, finalFree);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                } catch (Exception e) {
                    CoffeesAeroAuth.LOGGER.error("[Tracking] footprint write failed for {}: {}",
                        uuid, e.toString());
                }
            });
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            try {
                Class<?> mgr = Class.forName("com.mapter.aeroclaims.claim.AeroClaimManager");
                getUsedSlots = mgr.getMethod("getUsedSlots", ServerLevel.class, UUID.class);
                getFreeSlots = mgr.getMethod("getFreeSlots", ServerLevel.class, UUID.class);
            } catch (Throwable t) {
                getUsedSlots = null;
            }
        }
        if (getUsedSlots == null && !warned) {
            warned = true;
            CoffeesAeroAuth.LOGGER.warn("[Tracking] AeroClaims not found (or its API moved) — "
                + "claim footprint will not be recorded. Nothing else is affected.");
        }
        return getUsedSlots != null;
    }
}
