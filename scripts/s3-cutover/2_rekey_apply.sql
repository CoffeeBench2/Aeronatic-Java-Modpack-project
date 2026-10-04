-- =====================================================================================================
-- Season 3 cutover, step 2: APPLY the premium re-key. Read the header of 1_precheck.sql first.
-- Every precheck must have printed 0. Every server on this database must be STOPPED. A fresh mysqldump
-- must exist. All of it is one transaction: any error before COMMIT leaves the database untouched.
-- =====================================================================================================

-- Permanent audit + reverse map. Kept after the run: it is the record of who moved where, and the only
-- way to go back without restoring the dump (UPDATE ... SET uuid = old_uuid joined on this table).
CREATE TABLE IF NOT EXISTS uuid_rekey_log (
  old_uuid   CHAR(36)    NOT NULL PRIMARY KEY,
  new_uuid   CHAR(36)    NOT NULL,
  username   VARCHAR(16) NOT NULL,
  rekeyed_at BIGINT      NOT NULL,
  reason     VARCHAR(64) NOT NULL
) DEFAULT CHARSET=latin1 COLLATE=latin1_swedish_ci;   -- = players, so a reverse join needs no conversion

START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS rk;
-- Column types (and COLLATION) are inherited from players by the SELECT. Declaring them here instead takes
-- the server default collation, and every join below then fails with "Illegal mix of collations"
-- (seen on the 2026-10-04 dry run: tables are utf8mb4_general_ci, a stock 8.0 server is 0900_ai_ci).
CREATE TEMPORARY TABLE rk
  SELECT uuid AS old_uuid, mojang_uuid AS new_uuid, username FROM players
  WHERE account_type = 'PREMIUM' AND mojang_uuid IS NOT NULL AND mojang_uuid <> '' AND uuid <> mojang_uuid;
ALTER TABLE rk ADD PRIMARY KEY (old_uuid);

-- children first; players last, because every join below is on the OLD uuid
UPDATE trusted_ips      t JOIN rk ON t.uuid = rk.old_uuid SET t.uuid = rk.new_uuid;
UPDATE name_queue       t JOIN rk ON t.uuid = rk.old_uuid SET t.uuid = rk.new_uuid;
UPDATE player_stats     t JOIN rk ON t.uuid = rk.old_uuid SET t.uuid = rk.new_uuid;
UPDATE player_footprint t JOIN rk ON t.uuid = rk.old_uuid SET t.uuid = rk.new_uuid;
UPDATE confiscations    t JOIN rk ON t.uuid = rk.old_uuid SET t.uuid = rk.new_uuid;
UPDATE infractions      t JOIN rk ON t.uuid = rk.old_uuid SET t.uuid = rk.new_uuid;
UPDATE session_log      t JOIN rk ON t.uuid = rk.old_uuid SET t.uuid = rk.new_uuid;
-- session tokens are bound to the old identity; dropping them costs one /login at most
DELETE t FROM sessions  t JOIN rk ON t.uuid = rk.old_uuid;

INSERT INTO uuid_rekey_log (old_uuid, new_uuid, username, rekeyed_at, reason)
  SELECT old_uuid, new_uuid, username, UNIX_TIMESTAMP() * 1000, 's3-cutover premiumKeepsMojangUuid' FROM rk;

UPDATE players p JOIN rk ON p.uuid = rk.old_uuid SET p.uuid = rk.new_uuid;

-- post-condition, BEFORE commit: nothing linked may remain off its Mojang uuid
SELECT 'left_behind' AS chk, COUNT(*) AS n FROM players
WHERE mojang_uuid IS NOT NULL AND mojang_uuid <> '' AND uuid <> mojang_uuid;
SELECT 'rekeyed' AS chk, COUNT(*) AS n FROM rk;

COMMIT;
DROP TEMPORARY TABLE IF EXISTS rk;
