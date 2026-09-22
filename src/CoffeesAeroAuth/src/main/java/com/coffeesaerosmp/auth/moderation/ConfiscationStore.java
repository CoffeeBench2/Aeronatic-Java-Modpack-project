package com.coffeesaerosmp.auth.moderation;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** MySQL persistence for {@link Confiscation}. Writes are async; the boot read is not. */
public final class ConfiscationStore {

    /** Longest `reason` the column accepts; longer text is clamped rather than lost. */
    private static final int MAX_REASON = 256;

    /** Attempts a failed {@link #delete} makes before escalating to a watchdog alert. */
    private static final int DELETE_ATTEMPTS = 3;

    private static final long DELETE_RETRY_DELAY_MS = 500;

    private ConfiscationStore() {}

    /**
     * Loads every hold into memory. Call once, at server start, AFTER the schema exists.
     *
     * <p>A DB outage leaves the set empty — holds are then not enforced. That is the honest failure
     * direction for the CONFISCATE side: we cannot prove someone is held, so we do not freeze them.
     * The alternative (fail closed) would freeze innocent players during an unrelated outage.
     *
     * <p>The RELEASE side is the opposite risk, and it does not fail open on its own: a DELETE lost
     * to an outage leaves the row in place, and this method will faithfully reload it and re-freeze
     * a player an admin already released — silently, at the next restart. {@link #delete} exists to
     * prevent that by retrying and alerting a human rather than swallowing the failure.
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
                ps.setString(2, TextUtil.clampChars(hold.reason(), MAX_REASON));
                ps.setString(3, hold.actor());
                ps.setLong(4, hold.startedEpoch());
                ps.executeUpdate();
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Confiscate] persist failed for {}: {}",
                    hold.uuid(), e.toString());
            }
        });
    }

    /**
     * Removes a hold row. The caller clears memory FIRST (see {@link Confiscation#release}), so by
     * the time this runs the player is already free this session — this call is only about making
     * sure a restart doesn't reload the stale row and re-freeze them.
     *
     * <p>That makes the failure direction here the opposite of {@link #persist}: silently swallowing
     * a failed DELETE would leave the row in place, and the next {@link #loadInto} would faithfully
     * restore a hold an admin explicitly released. So this retries a few times, and if it still can't
     * land, it raises a HIGH watchdog alert instead of staying quiet — a human needs to know today,
     * not discover it at the next restart.
     *
     * <p>Known narrow gap: if a release lands exactly across a server-shutdown boundary, the task can
     * either still be queued on the executor being drained, or run inline on the caller's thread via
     * {@link AsyncIo}'s reject-then-run-inline fallback — either is fine alone, but the ordering
     * between "memory cleared" and "row deleted" is not strictly guaranteed in that exact window.
     * Not engineered around here; the retry+alert above covers the consequence if it ever bites.
     */
    public static void delete(UUID uuid) {
        AsyncIo.submit(() -> {
            if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
            String lastError = null;
            for (int attempt = 1; attempt <= DELETE_ATTEMPTS; attempt++) {
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM confiscations WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.executeUpdate();
                    return;   // success
                } catch (Exception e) {
                    lastError = e.toString();
                    CoffeesAeroAuth.LOGGER.error("[Confiscate] delete attempt {}/{} failed for {}: {}",
                        attempt, DELETE_ATTEMPTS, uuid, lastError);
                    if (attempt < DELETE_ATTEMPTS) {
                        try {
                            Thread.sleep(DELETE_RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            // All attempts exhausted (or interrupted) — the row is still in the DB. Escalate loudly.
            com.coffeesaerosmp.auth.watchdog.WatchdogManager wd = CoffeesAeroAuth.WATCHDOG;
            if (wd != null) {
                wd.alert(com.coffeesaerosmp.auth.watchdog.WatchdogEvent.of(
                    com.coffeesaerosmp.auth.watchdog.Severity.HIGH,
                    "Confiscation release did not persist",
                    "Re-run /authmod release for this player once the database is back, "
                        + "or they will be re-frozen at the next restart.",
                    "Player uuid", uuid.toString(),
                    "Error", lastError));
            }
        });
    }
}
