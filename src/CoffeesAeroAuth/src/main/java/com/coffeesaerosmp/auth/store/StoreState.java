package com.coffeesaerosmp.auth.store;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.DatabaseManager;
import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import com.coffeesaerosmp.auth.util.AsyncIo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rank and cosmetic state, keyed by the player's <b>local (world) uuid</b>.
 *
 * <h2>Why a cache at all</h2>
 * The render path runs twice a second for every online player ({@code TabListManager} sends
 * {@code UPDATE_DISPLAY_NAME} on a 10-tick throttle) and again for every chat line, join and leave.
 * A database read anywhere in that path would put MySQL latency on the server thread — the exact
 * shape of the join-path stall that cost up to a second per login before the database moved onto the
 * game host. So: load once, off-thread, render from memory forever after.
 *
 * <h2>Lapse needs no poller</h2>
 * {@link Snapshot#effectiveRank()} compares {@code expiresAt} to the wall clock on every read, so a
 * subscription stops granting anything the instant it expires without any scheduled task, without a
 * database write, and even if the server never restarts. A poller is only needed to *tell* the player,
 * which is a courtesy, not a correctness requirement.
 */
public final class StoreState {

    /**
     * One player's entitlements at load time. Immutable — a stale snapshot renders a stale name, a
     * mutable one renders a torn one.
     *
     * @param rank      the subscribed tier, ignoring expiry
     * @param expiresAt UTC millis; 0 when there is no subscription
     * @param owned     permanently owned cosmetic ids (Beans)
     * @param selection what the player has chosen to wear, already sanitised at load
     */
    public record Snapshot(Rank rank, long expiresAt, Set<String> owned, Selection selection) {

        public static final Snapshot EMPTY =
            new Snapshot(Rank.NONE, 0L, Set.of(), Selection.DEFAULT);

        /** The rank that actually applies right now. {@link Rank#NONE} once the period has ended. */
        public Rank effectiveRank() {
            if (rank == null || rank == Rank.NONE) return Rank.NONE;
            return expiresAt > System.currentTimeMillis() ? rank : Rank.NONE;
        }

        public boolean lapsed() { return rank != Rank.NONE && effectiveRank() == Rank.NONE; }

        /**
         * The selection filtered against what is allowed <i>now</i>. Recomputed per read rather than
         * cached, because the answer changes the moment the subscription expires and this is the only
         * place that notices.
         */
        public Selection liveSelection() {
            return Entitlements.sanitise(selection, effectiveRank(), owned).selection();
        }
    }

    private static final Map<UUID, Snapshot> CACHE = new ConcurrentHashMap<>();

    /**
     * Notified with the local uuid whenever a snapshot is republished.
     *
     * <p>🔴 This is not a convenience. Two of the three name surfaces refresh themselves — the tab list
     * rebuilds every 10 ticks and chat renders per message — but the <b>nametag above the head is a
     * scoreboard team</b>, which only changes when something explicitly rewrites it. Without this hook a
     * player who runs {@code /cosmetics colour gold} sees their tab name change and their nametag stay
     * the old colour until the next unrelated reveal, which reads as a half-working purchase.
     *
     * <p>Held as a listener rather than calling the scoreboard directly so this class keeps no
     * dependency on Minecraft or on {@code NameVisibility}.
     */
    private static volatile java.util.function.Consumer<UUID> changeListener;

    public static void setChangeListener(java.util.function.Consumer<UUID> listener) {
        changeListener = listener;
    }

    private StoreState() {}

    /** Never null. An unknown player is {@link Snapshot#EMPTY}, which renders as unranked. */
    public static Snapshot get(UUID localUuid) {
        if (localUuid == null) return Snapshot.EMPTY;
        Snapshot s = CACHE.get(localUuid);
        return s == null ? Snapshot.EMPTY : s;
    }

    /** True once a load has completed for this player. Used to avoid re-queuing loads. */
    public static boolean isLoaded(UUID localUuid) {
        return localUuid != null && CACHE.containsKey(localUuid);
    }

    public static void forget(UUID localUuid) {
        if (localUuid != null) CACHE.remove(localUuid);
    }

    public static void clearAll() { CACHE.clear(); }

    /**
     * Load a player's store state off the server thread, then publish it.
     *
     * <p>🔴 Always via {@link AsyncIo} — never call the blocking part from a tick, a join handler or a
     * command. On failure the player is published as {@link Snapshot#EMPTY} rather than left absent,
     * so the render path has a definite answer instead of retrying every frame.
     */
    public static void loadAsync(UUID localUuid) {
        loadAsync(localUuid, null);
    }

    /**
     * As {@link #loadAsync(UUID)}, with a callback once the snapshot is published.
     *
     * <p>🔑 The callback exists because the render path cannot wait. A player is revealed the instant
     * they authenticate, which is before any off-thread load could have finished, so the first render
     * is necessarily unranked. The callback is what re-renders them a moment later with their actual
     * rank instead of leaving a paying player looking unranked until something else happens to refresh
     * them.
     *
     * <p>⚠️ {@code onLoaded} runs on the <b>AsyncIo thread</b>. Anything touching the world, a player
     * or the scoreboard must hop back via {@code server.execute(...)} — that is the caller's job, and
     * doing it here would force every caller to have a server reference.
     */
    public static void loadAsync(UUID localUuid, Runnable onLoaded) {
        if (localUuid == null) return;
        AsyncIo.submit(() -> {
            Snapshot s = loadBlocking(localUuid);
            CACHE.put(localUuid, s);
            // The rendered-name cache is keyed on the selection, but a rank change alters what the
            // selection RESOLVES to, so it has to be dropped alongside the snapshot.
            NameRender.forget(localUuid);

            var listener = changeListener;
            if (listener != null) {
                try { listener.accept(localUuid); }
                catch (Exception e) {
                    CoffeesAeroAuth.LOGGER.warn("[Store] change listener failed for {}: {}",
                        localUuid, e.getMessage());
                }
            }
            if (onLoaded != null) {
                try { onLoaded.run(); }
                catch (Exception e) {
                    CoffeesAeroAuth.LOGGER.warn("[Store] post-load callback failed for {}: {}",
                        localUuid, e.getMessage());
                }
            }
        });
    }

    /** Re-read after a purchase or a selection change. Same contract as {@link #loadAsync}. */
    public static void invalidate(UUID localUuid) {
        loadAsync(localUuid);
    }

    // ── the blocking part ─────────────────────────────────────────────────────────

    static Snapshot loadBlocking(UUID localUuid) {
        DatabaseManager db = CoffeesAeroAuth.DB_MANAGER;
        if (db == null || !db.isAvailable()) return Snapshot.EMPTY;

        // The store keys on the MOJANG uuid; the world keys on the local one. No mapping yet means
        // this player has never been seen as premium, so they can own nothing — unranked, not an error.
        UUID mojang = MojangIds.toMojang(localUuid).orElse(null);
        if (mojang == null) return Snapshot.EMPTY;

        Rank rank = Rank.NONE;
        long expires = 0L;
        Set<String> owned = new LinkedHashSet<>();
        Selection sel = Selection.DEFAULT;

        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT rank_id, expires_at FROM subscriptions WHERE mojang_uuid = ?")) {
                ps.setString(1, mojang.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        rank = Rank.parse(rs.getString(1));
                        expires = rs.getLong(2);
                    }
                }
            }
            // revoked_at IS NULL — a chargeback leaves the audit row but stops the entitlement.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cosmetic_id FROM cosmetics_owned " +
                    "WHERE mojang_uuid = ? AND revoked_at IS NULL")) {
                ps.setString(1, mojang.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) owned.add(rs.getString(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name_style, bold, chat_title, join_message FROM cosmetic_selection " +
                    "WHERE mojang_uuid = ?")) {
                ps.setString(1, mojang.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        sel = new Selection(rs.getString(1), rs.getBoolean(2),
                                            rs.getString(3), rs.getString(4));
                    }
                }
            }
        } catch (SQLException e) {
            CoffeesAeroAuth.LOGGER.warn("[Store] load failed for {}: {}", localUuid, e.getMessage());
            return Snapshot.EMPTY;
        }

        return new Snapshot(rank, expires, Set.copyOf(owned), sel);
    }
}
