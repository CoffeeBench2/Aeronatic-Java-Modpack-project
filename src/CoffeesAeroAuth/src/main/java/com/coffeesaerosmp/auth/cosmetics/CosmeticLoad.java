package com.coffeesaerosmp.auth.cosmetics;

/**
 * Should cosmetics run right now? They are the first thing to switch off when the server struggles: lag is the
 * server's #1 problem (Obaa planning/lag-audit-2026-10-11.md), and a stall is exactly when a backlog of queued
 * particle packets would land all at once. Pure, so the hysteresis is unit-tested.
 *
 * <ul>
 *   <li>Pause when TPS (ticks per WALL second, from LagMonitor) drops below {@code pauseTps}, or the window holds
 *       a freeze ({@code worstGapMs >= 2000}).</li>
 *   <li>Resume only after TPS has stayed at or above {@code resumeTps} for {@code holdMs} — so a server hovering
 *       around the threshold does not flap cosmetics on and off.</li>
 * </ul>
 */
public final class CosmeticLoad {

    private boolean paused;
    private long goodSinceMs = -1;
    private boolean forced;      // ops override (/aerostore cosmetics pause) and tests

    /** Feed one reading (about once a second). Returns whether cosmetics are paused afterwards. */
    public boolean update(double tps, double worstGapMs, long nowMs, double pauseTps, double resumeTps, long holdMs) {
        boolean bad = tps < pauseTps || worstGapMs >= 2000;
        if (bad) {
            paused = true;
            goodSinceMs = -1;
        } else if (paused) {
            if (tps >= resumeTps) {
                if (goodSinceMs < 0) goodSinceMs = nowMs;
                if (nowMs - goodSinceMs >= holdMs) { paused = false; goodSinceMs = -1; }
            } else {
                goodSinceMs = -1;          // between pause and resume: keep waiting
            }
        }
        return paused();
    }

    public boolean paused() {
        return forced || paused;
    }

    public void force(boolean pause) {
        forced = pause;
    }
}
