package com.coffeesaerosmp.auth.moderation;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** MySQL persistence for {@link Confiscation}. Writes are async; the boot read is not. */
public final class ConfiscationStore {

    private ConfiscationStore() {}

    /**
     * Loads every hold into memory. Call once, at server start, AFTER the schema exists.
     *
     * <p>A DB outage leaves the set empty — holds are then not enforced. That is the honest failure
     * direction: we cannot prove someone is held, so we do not freeze them. The alternative (fail
     * closed) would freeze innocent players during an unrelated outage.
     */
    public static void loadInto() {
        if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) {
            CoffeesAeroAuth.LOGGER.warn("[Confiscate] DB unavailable at boot — no holds loaded.");
            return;
        }
        List<Confiscation.Hold> out = new ArrayList<>();
        try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT uuid, reason, actor, started_epoch FROM confiscations")) {
            while (rs.next()) {
                String raw = rs.getString("uuid");
                try {
                    out.add(new Confiscation.Hold(
                        UUID.fromString(raw),
                        rs.getString("reason"),
                        rs.getString("actor"),
                        rs.getLong("started_epoch")));
                } catch (IllegalArgumentException badUuid) {
                    CoffeesAeroAuth.LOGGER.warn("[Confiscate] skipping unparseable uuid row: {}", raw);
                }
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Confiscate] load failed: {}", e.toString());
            return;   // leave the in-memory set untouched rather than wiping it with a partial read
        }
        Confiscation.loadAll(out);
        CoffeesAeroAuth.LOGGER.info("[Confiscate] Loaded {} active hold(s).", out.size());
    }

    /** Persists a hold. The caller updates memory first, so enforcement is immediate. */
    public static void persist(Confiscation.Hold hold) {
        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO confiscations (uuid, reason, actor, started_epoch) VALUES (?,?,?,?) " +
                     "ON DUPLICATE KEY UPDATE reason=VALUES(reason), actor=VALUES(actor), " +
                     "started_epoch=VALUES(started_epoch)")) {
                ps.setString(1, hold.uuid().toString());
                ps.setString(2, hold.reason());
                ps.setString(3, hold.actor());
                ps.setLong(4, hold.startedEpoch());
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Confiscate] persist failed for {}: {}",
                    hold.uuid(), e.toString());
            }
        });
    }

    /** Removes a hold row. */
    public static void delete(UUID uuid) {
        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM confiscations WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Confiscate] delete failed for {}: {}", uuid, e.toString());
            }
        });
    }
}
