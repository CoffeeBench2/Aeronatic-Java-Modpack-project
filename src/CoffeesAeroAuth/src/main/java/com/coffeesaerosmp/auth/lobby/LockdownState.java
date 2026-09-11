package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static com.coffeesaerosmp.auth.util.Repeating.guard;

/**
 * The SMP "closed for business" switch, shared between the two servers through MySQL.
 *
 * <h2>What it is for</h2>
 * When a restart is announced, the SMP raises this flag. The lobby then refuses to hand anybody over
 * — arrivals are told <i>"Survival is still under process"</i> and simply stay in the lobby — until an
 * admin lowers it again from the SMP. That is deliberately stricter than {@link SmpLiveness}: a Server
 * List Ping goes green the moment the port binds, which is minutes before a 40 GB world is actually
 * ready for players. A flag someone has to clear cannot go green early.
 *
 * <h2>Why MySQL</h2>
 * The lobby and the SMP are separate processes on separate hosts. The database is the only thing they
 * both already talk to, so it is the only honest channel for this. No new port, no new protocol.
 *
 * <h2>🔴 Never on the join path</h2>
 * {@link #isLocked()} is a volatile read of a value refreshed on a background thread. It must stay
 * that way. Reading MySQL while a player is connecting is the rule this project has broken before and
 * paid for in login stalls; the low latency since the DB moved onto the game host is headroom, not
 * licence.
 *
 * <h2>🔴 What happens when the database is unreachable</h2>
 * This is a decision to refuse people based on a value we might not be able to read, which is exactly
 * the trap in [[absence-based-decisions-need-a-live-source]]: a missing record is not the same as a
 * record that says "no".
 * <ul>
 *   <li><b>Never read it successfully yet</b> → treat as UNLOCKED. Refusing the entire playerbase on
 *       the strength of no information at all is indefensible; the worst case if we are wrong is that
 *       someone lands on a server that is still starting, which is the status quo we already live
 *       with.</li>
 *   <li><b>Read it before, DB now down</b> → keep the last known value, because that is real
 *       information, but only for {@link #STALE_LIMIT_MS}. After that it has decayed into a guess and
 *       we fall open rather than strand the server behind a flag nobody can reach to clear.</li>
 * </ul>
 * Both paths are logged, because "the lobby quietly stopped enforcing the lock" must never be silent.
 */
public final class LockdownState {

    /** Flag name in {@code server_flags}. */
    private static final String FLAG = "smp_lockdown";

    private static final long POLL_SECONDS   = 10;
    /** How long a last-known value stays trustworthy once the DB stops answering. */
    private static final long STALE_LIMIT_MS = 5 * 60_000L;

    private static volatile boolean locked;
    private static volatile String  reason = "";
    /** 0 = never successfully read. */
    private static volatile long    lastGoodReadMs;
    private static volatile boolean staleWarned;

    private static ScheduledExecutorService poller;

    /**
     * Set on the LOBBY so the unlock can be announced to the people waiting for it.
     *
     * <p>🔴 Volatile, and every use trampolines through {@code server.execute(...)}. The refresh runs
     * on a background poller thread, and touching the player list or sending chat from off-thread is
     * how you corrupt connection state rather than how you send a message.
     */
    private static volatile net.minecraft.server.MinecraftServer server;

    public static void attach(net.minecraft.server.MinecraftServer s) { server = s; }

    private LockdownState() {}

    // ── Read side (lobby) ─────────────────────────────────────────────────────

    /**
     * @return true when the SMP is closed and the lobby must not hand anyone over. Volatile read —
     *         safe to call on the join path, never touches the database.
     */
    public static boolean isLocked() {
        if (!locked) return false;

        if (lastGoodReadMs == 0L) return false;      // never read it; do not refuse on no information

        long age = System.currentTimeMillis() - lastGoodReadMs;
        if (age > STALE_LIMIT_MS) {
            if (!staleWarned) {
                staleWarned = true;
                CoffeesAeroAuth.LOGGER.warn("[Lockdown] Flag is {} ms stale (DB unreachable?) — releasing "
                    + "the lock rather than stranding players behind a value nobody can clear.", age);
            }
            return false;
        }
        return true;
    }

    /** Admin-facing reason text, or empty. */
    public static String reason() { return reason; }

    /** True only when we have actually managed to read the flag at least once. */
    public static boolean isBacked() { return lastGoodReadMs != 0L; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Starts the background refresh. Safe to call twice. */
    public static synchronized void start() {
        if (poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AeroAuth-Lockdown");
            t.setDaemon(true);        // must never hold the JVM open past shutdown
            return t;
        });
        poller.scheduleWithFixedDelay(
                guard("lockdown-poll", LockdownState::refresh), 0, POLL_SECONDS, TimeUnit.SECONDS);
        CoffeesAeroAuth.LOGGER.info("[Lockdown] Watching the shared SMP lock every {}s.", POLL_SECONDS);
    }

    public static synchronized void stop() {
        if (poller != null) { poller.shutdownNow(); poller = null; }
    }

    // ── Write side (SMP) ──────────────────────────────────────────────────────

    /**
     * Raises or lowers the flag. Runs the write off-thread, so a command or the restart countdown
     * never blocks the server thread on a remote database.
     *
     * @param who free text for the audit trail ("restart countdown", an admin's name, …)
     */
    public static void set(boolean lock, String who, String why) {
        final long now = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            var db = CoffeesAeroAuth.DB_MANAGER;
            if (db == null || !db.isAvailable()) {
                CoffeesAeroAuth.LOGGER.error("[Lockdown] Cannot {} the lock — database unavailable. "
                    + "The lobby will keep using its last known value.", lock ? "SET" : "CLEAR");
                return;
            }
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO server_flags (name, value, set_by, updated_at) VALUES (?,?,?,?) "
                   + "ON DUPLICATE KEY UPDATE value=VALUES(value), set_by=VALUES(set_by), "
                   + "updated_at=VALUES(updated_at)")) {
                ps.setString(1, FLAG);
                ps.setString(2, (lock ? "1" : "0") + (why == null || why.isBlank() ? "" : "|" + why));
                ps.setString(3, who == null ? "" : who);
                ps.setLong(4, now);
                ps.executeUpdate();
                // Reflect locally at once so the SMP's own /authmod lockdown status is truthful
                // without waiting for the next poll.
                locked = lock;
                reason = why == null ? "" : why;
                lastGoodReadMs = now;
                staleWarned = false;
                CoffeesAeroAuth.LOGGER.info("[Lockdown] SMP lock {} by {} ({}).",
                    lock ? "RAISED" : "CLEARED", who, why == null || why.isBlank() ? "no reason given" : why);
            } catch (Exception e) {
                CoffeesAeroAuth.LOGGER.error("[Lockdown] Failed to {} the lock: {}",
                    lock ? "set" : "clear", e.toString());
            }
        }, "AeroAuth-LockdownWrite");
        t.setDaemon(true);
        t.start();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Announces in the LOBBY that survival has reopened, when an admin runs
     * {@code /authmod lockdown off} on the SMP.
     *
     * <p>The two servers share only the database, so the lobby learns about the unlock on its next
     * poll — up to {@link #POLL_SECONDS} later. That delay is why this exists at all: without it the
     * door silently unlocks and everyone keeps waiting, because nothing tells them to try again.
     *
     * <p>Deliberately does NOT transfer anybody. Auto-readmit is a separate, off-by-default feature
     * ({@code autoReadmit}) precisely because sending a full lobby through at once hands the SMP every
     * reconnect in one tick — the thundering herd a graceful restart exists to avoid. This just says
     * "you can go now" and lets people leave at their own pace.
     */
    private static void announceUnlocked() {
        if (!LobbyHandoff.isLobbyRole()) return;      // the SMP has nobody waiting to enter itself
        final net.minecraft.server.MinecraftServer s = server;
        if (s == null) return;
        try {
            s.execute(() -> {
                var players = s.getPlayerList().getPlayers();
                if (players.isEmpty()) return;
                for (net.minecraft.server.level.ServerPlayer p : new java.util.ArrayList<>(players)) {
                    try {
                        p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            com.coffeesaerosmp.auth.util.TextUtil.PREFIX
                            + "§a✔ Survival is open again!"));
                        p.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            com.coffeesaerosmp.auth.util.TextUtil.PREFIX
                            + "§7Use your §aJoin Survival§7 paper to head over."));
                        com.coffeesaerosmp.auth.util.Sounds.success(p);
                    } catch (Exception ignored) {
                        // One player with a dead connection must not stop everyone else being told.
                    }
                }
                CoffeesAeroAuth.LOGGER.info("[Lockdown] Announced the reopening to {} player(s) in the lobby.",
                    players.size());
            });
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Lockdown] Could not announce the reopening: {}", e.toString());
        }
    }

    private static void refresh() {
        var db = CoffeesAeroAuth.DB_MANAGER;
        if (db == null || !db.isAvailable()) return;      // leave the last known value alone
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT value FROM server_flags WHERE name = ?")) {
            ps.setString(1, FLAG);
            try (ResultSet rs = ps.executeQuery()) {
                boolean nowLocked = false;
                String nowReason = "";
                if (rs.next()) {
                    String v = rs.getString(1);
                    if (v != null && !v.isBlank()) {
                        int bar = v.indexOf('|');
                        nowLocked = "1".equals(bar < 0 ? v : v.substring(0, bar));
                        nowReason = bar < 0 ? "" : v.substring(bar + 1);
                    }
                }
                // No row at all is a legitimate answer meaning "not locked" — the flag has simply
                // never been set. That is a successful read, so it counts as backed.
                if (nowLocked != locked) {
                    CoffeesAeroAuth.LOGGER.info("[Lockdown] SMP lock is now {}.",
                        nowLocked ? "ON — the lobby will hold players" : "OFF — transfers allowed");
                    // Tell the people who are actually waiting. Only on the unlock, and only on the
                    // LOBBY: the SMP has nobody standing around waiting to be let into itself, and a
                    // player being REFUSED already gets told why by LobbyHandoff, so announcing the
                    // lock going ON would just repeat that to everyone.
                    if (!nowLocked) announceUnlocked();
                }
                locked = nowLocked;
                reason = nowReason;
                lastGoodReadMs = System.currentTimeMillis();
                staleWarned = false;
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Lockdown] Refresh failed ({}) — keeping the last known value.",
                e.toString());
        }
    }
}
