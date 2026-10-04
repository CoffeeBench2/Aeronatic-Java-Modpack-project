-- =====================================================================================================
-- Season 3 cutover, step 0: make sure the auth 1.13 tables/column exist BEFORE the re-key, so the re-key
-- can move them too. Idempotent; safe to run any number of times. Same DDL as MailStore.createSchema and
-- DatabaseManager (season_start_playtime) — keep them in step.
--
-- MySQL has no ADD COLUMN IF NOT EXISTS (that's MariaDB), and the hosted DB user may lack CREATE ROUTINE,
-- so the column is added through a prepared statement chosen from information_schema — no procedure.
-- =====================================================================================================

CREATE TABLE IF NOT EXISTS mail (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  uuid        CHAR(36)      NOT NULL,
  sender      VARCHAR(32)   NOT NULL,
  subject     VARCHAR(64)   NOT NULL,
  body        VARCHAR(1024) NOT NULL DEFAULT '',
  items       MEDIUMTEXT    NULL,
  spurs       INT           NOT NULL DEFAULT 0,
  created_at  BIGINT        NOT NULL,
  expires_at  BIGINT        NOT NULL DEFAULT 0,
  read_at     BIGINT        NULL,
  claimed_at  BIGINT        NULL,
  dedupe_key  VARCHAR(128)  NULL,
  UNIQUE KEY uniq_mail_dedupe (dedupe_key),
  INDEX idx_mail_inbox (uuid, claimed_at, created_at)
);

CREATE TABLE IF NOT EXISTS level_progress (
  uuid           CHAR(36) NOT NULL,
  season         INT      NOT NULL,
  level_rewarded INT      NOT NULL DEFAULT 1,
  PRIMARY KEY (uuid, season)
);

SET @have := (SELECT COUNT(*) FROM information_schema.COLUMNS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'players' AND COLUMN_NAME = 'season_start_playtime');
SET @ddl := IF(@have = 0,
  'ALTER TABLE players ADD COLUMN season_start_playtime BIGINT NOT NULL DEFAULT 0',
  'DO 0');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SELECT 'schema_ready' AS chk,
  (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('mail','level_progress')) AS tables_2,
  (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'players' AND COLUMN_NAME = 'season_start_playtime') AS column_1;
