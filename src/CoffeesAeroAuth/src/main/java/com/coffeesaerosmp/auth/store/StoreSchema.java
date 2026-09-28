package com.coffeesaerosmp.auth.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DDL for the rank and cosmetics tables. Kept out of {@code DatabaseManager} because that class is
 * already 400 lines of schema and this is a separable feature.
 *
 * <h2>🔴 Why every table keys on {@code mojang_uuid}, not on {@code players.uuid}</h2>
 * Measured 2026-09-28: {@code players.uuid} is {@code md5("OfflinePlayer:"+username)} for <b>all 403
 * accounts, premium included</b> — the backend is {@code online-mode=false} and the client connects
 * straight to it, so no Mojang identity is ever forwarded and the server mints the id from the name.
 *
 * <p>Two consequences force this choice:
 * <ul>
 *   <li><b>Tebex delivers by Mojang uuid.</b> An online-mode store hands us the v4 id. Keying
 *       purchases on the v3 name id would mean translating on the way in, and a failed translation
 *       silently drops a paid order.</li>
 *   <li><b>A rename moves {@code players.uuid}.</b> Keying a paid subscription on something that
 *       changes when a player renames would delete the subscription they are still being billed for.
 *       {@code mojang_uuid} never changes.</li>
 * </ul>
 *
 * <p>So the purchase identity is the Mojang uuid, and the render path translates <i>outward</i> via
 * {@link MojangIds}. ⚠️ Do NOT "simplify" this by re-keying {@code players} — the v3 id is what
 * vanilla {@code playerdata/}, FTB Chunks/Teams/Quests, Tombstone and {@code numismatics_bank.dat}
 * all key on <i>on disk</i>. That is a whole-world migration, not a schema change.
 *
 * <p>All timestamps are UTC millis, matching the rest of the schema — the lobby box runs UTC and the
 * SMP runs +05:30, so only rendering is ever localised.
 */
public final class StoreSchema {

    private StoreSchema() {}

    public static void create(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {

            // One row per subscriber. Rented, not owned: `expires_at` is the whole contract, and
            // Entitlements treats an expired row exactly like no row.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS subscriptions (" +
                "  mojang_uuid      CHAR(36)     NOT NULL PRIMARY KEY," +
                "  rank_id          ENUM('DECKHAND','NAVIGATOR','CAPTAIN','ADMIRAL') NOT NULL," +
                "  started_at       BIGINT       NOT NULL," +
                "  expires_at       BIGINT       NOT NULL," +
                "  last_payment_at  BIGINT       NOT NULL DEFAULT 0," +
                "  tebex_package    VARCHAR(64)  NULL," +
                // Kept for support: which name was on the account when it was bought. NOT used as a
                // key -- see the class note.
                "  bought_as_name   VARCHAR(16)  NULL," +
                "  INDEX idx_sub_expiry (expires_at)" +
                ")");

            // Append-only grant log. A revoke (chargeback) sets `revoked_at` rather than deleting the
            // row, because the spec requires a purchase history and a chargeback must be auditable.
            // The UNIQUE key makes re-delivery idempotent: Tebex retries, and a retry must not
            // create a second grant.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cosmetics_owned (" +
                "  id           BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  mojang_uuid  CHAR(36)     NOT NULL," +
                "  cosmetic_id  VARCHAR(64)  NOT NULL," +
                "  granted_at   BIGINT       NOT NULL," +
                "  revoked_at   BIGINT       NULL," +
                "  source       ENUM('BEANS','RANK','STAFF','COMPENSATION') NOT NULL DEFAULT 'BEANS'," +
                "  tebex_txn    VARCHAR(64)  NULL," +
                "  UNIQUE KEY uniq_owned (mojang_uuid, cosmetic_id)," +
                "  INDEX idx_owned_player (mojang_uuid, revoked_at)" +
                ")");

            // What the player currently wears. Separate from ownership so a lapse can re-derive this
            // without touching the permanent grant log.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS cosmetic_selection (" +
                "  mojang_uuid   CHAR(36)     NOT NULL PRIMARY KEY," +
                // 'colour:gold' or 'gradient:sunrise'. NULL = server default.
                "  name_style    VARCHAR(64)  NULL," +
                "  bold          BOOLEAN      NOT NULL DEFAULT FALSE," +
                // 🔴 These two are the APPROVED, renderable values. Free text a player supplies never
                // lands here directly — it goes to the pending_* columns below and is copied across
                // only on approval. A single pair of columns would mean the moment between "player
                // typed it" and "staff rejected it" is a window in which it renders, which for a
                // custom chat title is a window in which anything can be said in everyone's chat.
                "  chat_title            VARCHAR(32)  NULL," +
                "  join_message          VARCHAR(128) NULL," +
                "  pending_chat_title    VARCHAR(32)  NULL," +
                "  pending_join_message  VARCHAR(128) NULL," +
                "  updated_at            BIGINT       NOT NULL" +
                ")");

            // 🔴 The delivery safety net. 176 of 283 premium rows had no `mojang_uuid` when measured,
            // because it only fills on a gate-verified join -- so a purchase can arrive for a player
            // we cannot yet resolve. Such an order must WAIT here, not fail: Tebex considers it
            // delivered once the command runs, so a command that silently no-ops loses a paid order
            // with no trace. Replayed on the player's next join, when the column fills.
            s.executeUpdate(
                "CREATE TABLE IF NOT EXISTS store_pending_grants (" +
                "  id           BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  mojang_uuid  CHAR(36)     NOT NULL," +
                "  kind         ENUM('RANK','COSMETIC') NOT NULL," +
                // For RANK: the rank id. For COSMETIC: the cosmetic id.
                "  payload      VARCHAR(64)  NOT NULL," +
                "  expires_at   BIGINT       NOT NULL DEFAULT 0," +
                "  created_at   BIGINT       NOT NULL," +
                "  applied_at   BIGINT       NULL," +
                "  tebex_txn    VARCHAR(64)  NULL," +
                "  INDEX idx_pending (mojang_uuid, applied_at)" +
                ")");
        }
    }
}
