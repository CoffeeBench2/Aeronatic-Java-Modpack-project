-- =====================================================================================================
-- Season 3 cutover: UNDO 2_rekey_apply.sql using uuid_rekey_log. Same rules: every server on this
-- database STOPPED, fresh dump first. Prefer restoring the pre-cutover dump if nothing happened since;
-- use this only when the database has moved on (new players, playtime) and the dump would lose it.
--
-- Only rows still at their logged new_uuid are moved back. A player who was later healed or renamed by the
-- mod is left alone (it is not this script's change any more).
-- =====================================================================================================

START TRANSACTION;

DROP TEMPORARY TABLE IF EXISTS rb;
CREATE TEMPORARY TABLE rb
  SELECT l.old_uuid, l.new_uuid FROM uuid_rekey_log l JOIN players p ON p.uuid = l.new_uuid
  WHERE l.reason = 's3-cutover premiumKeepsMojangUuid';
ALTER TABLE rb ADD PRIMARY KEY (new_uuid);

UPDATE trusted_ips      t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
UPDATE name_queue       t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
UPDATE player_stats     t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
UPDATE player_footprint t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
UPDATE confiscations    t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
UPDATE infractions      t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
UPDATE session_log      t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
UPDATE mail             t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
UPDATE level_progress   t JOIN rb ON t.uuid = rb.new_uuid SET t.uuid = rb.old_uuid;
DELETE t FROM sessions  t JOIN rb ON t.uuid = rb.new_uuid;
UPDATE players          p JOIN rb ON p.uuid = rb.new_uuid SET p.uuid = rb.old_uuid;

DELETE l FROM uuid_rekey_log l JOIN rb ON l.old_uuid = rb.old_uuid;
SELECT 'rolled_back' AS chk, COUNT(*) AS n FROM rb;

COMMIT;
DROP TEMPORARY TABLE IF EXISTS rb;
