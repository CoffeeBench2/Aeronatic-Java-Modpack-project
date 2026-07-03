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
            cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8");
            cfg.setUsername(user);
            cfg.setPassword(pass);
            cfg.setMaximumPoolSize(10);
            cfg.setMinimumIdle(2);
            cfg.setConnectionTimeout(5_000);
            cfg.setIdleTimeout(300_000);
            cfg.setMaxLifetime(600_000);
            cfg.setConnectionTestQuery("SELECT 1");
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
                "  return_z              DOUBLE                       NOT NULL DEFAULT 0" +
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
