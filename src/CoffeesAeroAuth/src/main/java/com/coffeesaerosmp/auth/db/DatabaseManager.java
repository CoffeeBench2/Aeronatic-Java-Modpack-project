package com.coffeesaerosmp.auth.db;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class DatabaseManager {

    public enum State { UP, FALLBACK }

    @FunctionalInterface
    public interface DbOperation {
        void execute(Connection conn) throws SQLException;
    }

    private volatile HikariDataSource pool;
    private final AtomicBoolean available = new AtomicBoolean(false);
    private final ConcurrentLinkedDeque<Consumer<Connection>> writeQueue = new ConcurrentLinkedDeque<>();
    private ScheduledExecutorService scheduler;

    /**
     * Attempts to connect using DB_* credentials from the provided env map.
     * Returns false (fallback mode) if DB_USER is not set or connection fails.
     * Never throws — server must start regardless.
     */
    public boolean initialize(Map<String, String> env) {
        String host = env.getOrDefault("DB_HOST", "localhost");
        String port = env.getOrDefault("DB_PORT", "3306");
        String db   = env.getOrDefault("DB_NAME", "coffeesaero");
        String user = env.getOrDefault("DB_USER", "");
        String pass = env.getOrDefault("DB_PASSWORD", "");

        if (user.isEmpty()) {
            CoffeesAeroAuth.LOGGER.warn("[DB] DB_USER not set in .env — using flat-file fallback.");
            return false;
        }

        try {
            HikariConfig cfg = new HikariConfig();
            // ── Tuned for a 234ms cross-continent link (DB Helsinki / game node Singapore) ──
            // useLocalSessionState is the important one. HikariCP calls Connection.isReadOnly()
            // while setting up EVERY new connection, and Connector/J answers it by round-tripping
            // "SELECT @@session.transaction_read_only" to the server. At 234ms that query, stacked
            // on top of the TCP+auth handshake, blew past the 5s network timeout Hikari applies
            // during setup — the "Communications link failure / Read timed out after 5,004ms" on
            // 2026-08-08. With local session state the driver answers from its own cache and the
            // round-trip disappears entirely, which fixes the cause rather than widening the window.
            // elideSetAutoCommits removes the matching redundant autocommit round-trip.
            // socketTimeout is a LAST-RESORT ceiling, deliberately far above any healthy query.
            cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                + "&useLocalSessionState=true&elideSetAutoCommits=true"
                // NO socketTimeout. DELIBERATE — 2026-08-09, after it broke boot twice.
                // socketTimeout is a per-read deadline applied to EVERY query, and it cannot tell
                // "SELECT 1 on a dead link" from "stream 108 profile rows across 234ms". The bulk
                // load in loadAllFromDatabase legitimately takes ~42s on this link (measured), so a
                // 30s limit made it fragile and a 10s limit killed it outright: "Loaded 0 profiles".
                // Slow queries are bounded per-call with Statement.setQueryTimeout instead, which
                // can be short exactly where it should be (the health probe) without capping the
                // one query that is genuinely allowed to take a minute.
                + "&connectTimeout=10000"
                + "&cachePrepStmts=true&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048"
                + "&useServerPrepStmts=true&rewriteBatchedStatements=true");
            cfg.setUsername(user);
            cfg.setPassword(pass);
            cfg.setMaximumPoolSize(10);
            cfg.setMinimumIdle(2);
            // Cybrancee's MySQL 8.0.46 has wait_timeout = interactive_timeout = 300s, and the DB
            // lives in Helsinki while the game node is in Singapore (~234ms RTT), so idle
            // connections get reaped aggressively. The old values had maxLifetime=600s — DOUBLE the
            // server's 300s limit — so MySQL killed connections that Hikari still believed were
            // alive, producing "No operations allowed after connection closed." on next borrow.
            // HikariCP's rule: maxLifetime must be comfortably SHORTER than any DB-imposed limit.
            // RESTORED to the values that were stable through 1.7.14. The 1.7.15/1.7.19 passes
            // moved these twice — first too high, then too low — and the second attempt made things
            // worse, not better: shorter maxLifetime and keepalive mean MORE connection churn, and
            // on a link that is already dropping, every new connection is another handshake that
            // can fail. Do not re-tune these without a measured reason.
            cfg.setConnectionTimeout(10_000);   // MAIN-THREAD exposure: a cache-miss profile read
                                               // waits this long for a pool connection.
            cfg.setIdleTimeout(120_000);        // must be < maxLifetime
            cfg.setMaxLifetime(240_000);        // 60s of margin under the server's 300s wait_timeout
            cfg.setKeepaliveTime(60_000);       // ping idle conns so they never reach 300s idle
            cfg.setValidationTimeout(5_000);
            // NOTE: deliberately NOT setting connectionTestQuery. MySQL Connector/J 8.3.0 is JDBC4,
            // so Hikari uses the lighter Connection.isValid() ping instead of a full round-trip
            // "SELECT 1" — which matters at 234ms RTT. Hikari explicitly recommends omitting it.
            cfg.setPoolName("AeroAuth-MySQL");
            cfg.setInitializationFailTimeout(-1); // never fail pool creation on startup

            pool = new HikariDataSource(cfg);
            try (Connection c = pool.getConnection()) {
                available.set(true);
                CoffeesAeroAuth.LOGGER.info("[DB] MySQL connected at {}:{}/{}", host, port, db);
                return true;
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[DB] MySQL unavailable on startup ({}) — flat-file fallback active.", e.getMessage());
            available.set(false);
            return false;
        }
    }

    /** Starts background health-check / reconnect loop. Call after initialize(). */
    public void startHealthCheck() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AeroAuth-DB-Health");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::checkHealth, 30, 30, TimeUnit.SECONDS);
    }

    private void checkHealth() {
        if (pool == null) return;
        boolean wasUp = available.get();
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            // Bounded well under the 30s check interval. Without this the probe inherited
            // socketTimeout, so a dead link produced a check that was still running when the next
            // one fired — overlapping probes against a socket already known to be broken.
            s.setQueryTimeout(5);
            s.execute("SELECT 1");
            if (!wasUp) {
                available.set(true);
                CoffeesAeroAuth.LOGGER.info("[DB] MySQL reconnected. Flushing {} queued writes.", writeQueue.size());
                flushWriteQueue();
            }
        } catch (Exception e) {
            if (wasUp) {
                available.set(false);
                CoffeesAeroAuth.LOGGER.warn("[DB] MySQL connection lost — falling back to flat files.");
            }
        }
    }

    private void flushWriteQueue() {
        int count = 0;
        Consumer<Connection> op;
        while ((op = writeQueue.poll()) != null) {
            try (Connection c = pool.getConnection()) {
                op.accept(c);
                count++;
            } catch (Exception e) {
                writeQueue.offerFirst(op);
                CoffeesAeroAuth.LOGGER.warn("[DB] Queue flush interrupted at op {}.", count, e);
                available.set(false);
                return;
            }
        }
        if (count > 0) CoffeesAeroAuth.LOGGER.info("[DB] Flushed {} queued writes.", count);
    }

    public Connection getConnection() throws SQLException {
        if (pool == null) throw new SQLException("DB pool not initialized");
        return pool.getConnection();
    }

    public boolean isAvailable() { return available.get() && pool != null; }
    public State getState()      { return isAvailable() ? State.UP : State.FALLBACK; }

    /** Queues an operation to be replayed when the DB reconnects. */
    public void queueWrite(DbOperation op) {
        writeQueue.offer(conn -> {
            try { op.execute(conn); }
            catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    // ── Schema creation ───────────────────────────────────────────────────────

    public void createSchema() {
        if (!isAvailable()) return;
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS players (" +
                "  uuid                  CHAR(36)                     NOT NULL PRIMARY KEY," +
                "  username              VARCHAR(16)                  NOT NULL," +
                "  display_name          VARCHAR(20)                  NOT NULL," +
                "  account_type          ENUM('PREMIUM','OFFLINE')    NOT NULL," +
                "  password_hash         VARCHAR(255)                 NULL," +
                "  password_salt         VARCHAR(64)                  NULL," +
                "  name_approved         BOOLEAN                      NOT NULL DEFAULT FALSE," +
                "  first_join            BIGINT                       NOT NULL DEFAULT 0," +
                "  last_seen             BIGINT                       NOT NULL DEFAULT 0," +
                "  total_playtime        BIGINT                       NOT NULL DEFAULT 0," +
                "  bio                   TEXT                         NULL," +
                "  skin_url              TEXT                         NULL," +
                "  cape_enabled          BOOLEAN                      NOT NULL DEFAULT FALSE," +
                "  name_approval_pending BOOLEAN                      NOT NULL DEFAULT FALSE," +
                "  pending_display_name  VARCHAR(20)                  NULL," +
                "  name_rejection_count  INT                          NOT NULL DEFAULT 0," +
                "  name_changes_used     INT                          NOT NULL DEFAULT 0," +
                "  room_slot             INT                          NOT NULL DEFAULT -1," +
                "  room_created_at       BIGINT                       NOT NULL DEFAULT 0," +
                "  first_join_complete   BOOLEAN                      NOT NULL DEFAULT FALSE," +
                "  startup_bonus_given   BOOLEAN                      NOT NULL DEFAULT FALSE," +
                "  first_ip              VARCHAR(45)                  NULL," +
                "  skin_changes_used     INT                          NOT NULL DEFAULT 0," +
                "  return_dim            VARCHAR(64)                  NULL," +
                "  return_x              DOUBLE                       NOT NULL DEFAULT 0," +
                "  return_y              DOUBLE                       NOT NULL DEFAULT 0," +
                "  return_z              DOUBLE                       NOT NULL DEFAULT 0," +
                "  discord_id            VARCHAR(32)                  NULL" +
                ")");
            // Forward-compat: add columns missing on pre-existing tables (MySQL lacks ADD COLUMN IF NOT EXISTS).
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN startup_bonus_given BOOLEAN NOT NULL DEFAULT FALSE"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN first_ip VARCHAR(45) NULL"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN cape_enabled BOOLEAN NOT NULL DEFAULT FALSE"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN skin_changes_used INT NOT NULL DEFAULT 0"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            // Return-position (resume-where-you-logged-off on /spawn) — added on pre-existing tables.
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN return_dim VARCHAR(64) NULL"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN return_x DOUBLE NOT NULL DEFAULT 0"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN return_y DOUBLE NOT NULL DEFAULT 0"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN return_z DOUBLE NOT NULL DEFAULT 0"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            // Discord↔MC account linking (1.6.10).
            try { s.executeUpdate("ALTER TABLE players ADD COLUMN discord_id VARCHAR(32) NULL"); }
            catch (SQLException dupCol) { /* column already present — fine */ }
            // Widen skin_url on pre-existing tables (was VARCHAR(512) — too small for a skin+cape textures blob).
            try { s.executeUpdate("ALTER TABLE players MODIFY COLUMN skin_url TEXT NULL"); }
            catch (SQLException ignored) { /* already TEXT — fine */ }
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS trusted_ips (" +
                "  id          BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  uuid        CHAR(36)    NOT NULL," +
                "  ip_address  VARCHAR(45) NOT NULL," +
                "  added_at    BIGINT      NOT NULL," +
                "  UNIQUE KEY unique_ip (uuid, ip_address)" +
                ")");
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS sessions (" +
                "  session_id  VARCHAR(32)  NOT NULL PRIMARY KEY," +
                "  uuid        CHAR(36)     NOT NULL," +
                "  ip_address  VARCHAR(45)  NOT NULL," +
                "  created_at  BIGINT       NOT NULL," +
                "  expires_at  BIGINT       NOT NULL" +
                ")");
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS name_queue (" +
                "  uuid             CHAR(36)    NOT NULL PRIMARY KEY," +
                "  requested_name   VARCHAR(20) NOT NULL," +
                "  rejection_count  INT         NOT NULL DEFAULT 0," +
                "  queued_at        BIGINT      NOT NULL," +
                "  status           ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING'" +
                ")");
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ip_bans (" +
                "  ip_address  VARCHAR(45)  NOT NULL PRIMARY KEY," +
                "  reason      VARCHAR(255) NULL," +
                "  banned_at   BIGINT       NOT NULL," +
                "  expires_at  BIGINT       NOT NULL" +
                ")");
            CoffeesAeroAuth.LOGGER.info("[DB] Schema verified.");
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.error("[DB] Schema creation failed", e);
        }
    }

    public void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
        if (pool != null && !pool.isClosed()) pool.close();
        CoffeesAeroAuth.LOGGER.info("[DB] Connection pool closed.");
    }
}
