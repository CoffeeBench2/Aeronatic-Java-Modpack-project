-- =====================================================================================================
-- Season 3 cutover, step 4: start the SEASON clock. The Season 3 level uses season playtime =
-- total_playtime − season_start_playtime, so this freezes everyone's lifetime playtime as the zero point.
-- Lifetime total_playtime itself is never touched (sidebar "Playtime", Discord milestones keep it).
--
-- Run with every server on the DB STOPPED (playtime is banked by the running servers), after step 0.
-- Re-running it would reset everyone's season clock to now — run it ONCE, at the cutover.
-- =====================================================================================================

UPDATE players SET season_start_playtime = total_playtime;
SELECT 'season_clock_started' AS chk, COUNT(*) AS n FROM players WHERE season_start_playtime = total_playtime;
