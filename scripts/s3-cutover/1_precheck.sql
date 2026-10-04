-- =====================================================================================================
-- Season 3 cutover: re-key every LINKED premium player from md5("OfflinePlayer:"+name) to their Mojang uuid
-- (CoffeesAeroAuth premiumKeepsMojangUuid). Owner decision 2026-10-04: S2 closes when S3 opens.
--
-- 🔴 RUN ONLY WITH EVERY SERVER THAT USES THIS DATABASE STOPPED (S2 SMP, lobby, S3). Each process caches
--    every profile at boot and save() upserts the whole row by uuid, so a running server would write the
--    OLD uuid straight back as a duplicate row.
-- 🔴 After this runs, the S2 SMP must NEVER start against this database again: its world is name-keyed
--    and it would mint fresh empty rows for every premium player.
-- 🔴 mysqldump the database first. That dump is the rollback.
--
-- Unlinked premium rows (no mojang_uuid) are NOT touched. They are folded in automatically on that
-- player's first gate login (AccountTransfer.unlinkedPremiumAlias -> RenameHealer, one reconnect).
-- Offline rows are never touched.
--
-- Tables: must match AccountTransfer (players, trusted_ips, name_queue, sessions + PER_PLAYER_TABLES +
-- HISTORY_TABLES). Store tables are already keyed by Mojang uuid.
-- =====================================================================================================

-- ---------- PRECHECKS: every count below must be 0, or STOP -------------------------------------------

-- (a) one Mojang account linked to two profiles: re-keying both would collide on the primary key
SELECT 'dup_mojang' AS chk, COUNT(*) AS n FROM (
  SELECT mojang_uuid FROM players WHERE mojang_uuid IS NOT NULL AND mojang_uuid <> ''
  GROUP BY mojang_uuid HAVING COUNT(*) > 1) d;

-- (b) a row already sits at some linked row's Mojang uuid
SELECT 'occupied' AS chk, COUNT(*) AS n
FROM players a JOIN players b ON b.uuid = a.mojang_uuid AND b.uuid <> a.uuid;

-- (c) a linked row that is not PREMIUM (should not exist; the gate only links premium logins)
SELECT 'linked_not_premium' AS chk, COUNT(*) AS n
FROM players WHERE mojang_uuid IS NOT NULL AND mojang_uuid <> '' AND account_type <> 'PREMIUM';

-- what will move
SELECT 'to_rekey' AS chk, COUNT(*) AS n FROM players
WHERE account_type = 'PREMIUM' AND mojang_uuid IS NOT NULL AND mojang_uuid <> '' AND uuid <> mojang_uuid;
