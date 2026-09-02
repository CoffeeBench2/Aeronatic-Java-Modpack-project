package com.coffeesaerosmp.auth.world;

import java.util.ArrayList;
import java.util.List;

/**
 * A count of Sable sub-levels ("ships") that are LIVE in memory, versus the ones players have
 * actually claimed. Pure — no Minecraft and no Sable types — so it is unit-testable. The bridge that
 * fills it in lives in {@link SableShips}.
 *
 * <h2>Why this exists</h2>
 * The 2026-09-02 spark profile put {@code sable$tickPlotContainer} at <b>11.6%</b> of the server
 * thread. That method is {@code SubLevelContainer.tick()}, which walks its {@code allSubLevels} list
 * every tick — so the cost is a straight function of HOW MANY sub-levels exist, not of how many
 * anyone is flying. If the live count is far above the claimed count, the server is paying to tick
 * abandoned hulls, and that is a deletion problem rather than a hardware one.
 *
 * <p>🔑 <b>This measures, it never deletes.</b> Sub-level removal is Sable's
 * {@code ServerSubLevelContainer.removeSubLevel}, which destroys blocks. A counter must not be one
 * typo away from that ([[sable-owns-sublevels-not-aeroclaims]]).
 *
 * @param levels  one entry per loaded dimension that has a sub-level container
 * @param claimed how many sub-levels AeroClaims has a claim record for, or -1 when unknown
 */
public record ShipCensus(List<LevelCensus> levels, int claimed) {

    /**
     * @param dimension    the level's resource key, e.g. {@code minecraft:overworld}
     * @param live         sub-levels present in the container
     * @param forceLoaded  of those, ones pinned loaded by a ticket (they tick with nobody nearby)
     * @param untracked    of those, ones no player is currently tracking
     * @param named        of those, ones with a name that is not Sable's default {@code "ship"}
     */
    public record LevelCensus(String dimension, int live, int forceLoaded, int untracked, int named) {}

    /** Sable's default name for a freshly assembled sub-level; not evidence anyone cared about it. */
    public static final String DEFAULT_NAME = "ship";

    public int totalLive()        { return levels.stream().mapToInt(LevelCensus::live).sum(); }
    public int totalForceLoaded() { return levels.stream().mapToInt(LevelCensus::forceLoaded).sum(); }
    public int totalUntracked()   { return levels.stream().mapToInt(LevelCensus::untracked).sum(); }
    public int totalNamed()       { return levels.stream().mapToInt(LevelCensus::named).sum(); }

    /**
     * Ships that are live but that nobody claimed. Negative results are clamped to 0 — a player can
     * claim a ship that is currently unloaded, which legitimately makes {@code claimed > live}.
     */
    public int unclaimed() {
        if (claimed < 0) return -1;
        return Math.max(0, totalLive() - claimed);
    }

    /** Chat-ready report lines, already colour-coded. */
    public List<String> report() {
        List<String> out = new ArrayList<>();
        out.add("§6Sable sub-level census");

        if (levels.isEmpty()) {
            out.add("§7No sub-level containers found. Either Sable is absent, or its API moved —");
            out.add("§7check the server log for a §f[Ships]§7 warning.");
            return out;
        }

        for (LevelCensus l : levels) {
            if (l.live() == 0) continue;
            out.add("§7" + l.dimension() + ": §f" + l.live() + "§7 live"
                + " §8(§7" + l.forceLoaded() + " force-loaded, "
                + l.untracked() + " untracked, "
                + l.named() + " named§8)");
        }

        out.add("§7Total live: §f" + totalLive()
            + "  §7claimed: §f" + (claimed < 0 ? "unknown" : String.valueOf(claimed)));

        out.add(verdict());
        return out;
    }

    /**
     * The one line worth reading. Keyed on the RATIO rather than a raw count, because a healthy
     * server with many active builders legitimately ticks a lot of ships.
     */
    public String verdict() {
        int live = totalLive();
        if (live == 0) return "§7Nothing is being ticked — sub-levels are not your cost right now.";
        if (claimed < 0) return "§7Claim data unavailable, so live-vs-claimed can't be judged.";

        int ghosts = unclaimed();
        if (ghosts == 0) return "§aEvery live sub-level is claimed. Ticking cost here is legitimate.";

        int percent = (int) Math.round(100.0 * ghosts / live);
        if (percent >= 50) {
            return "§c" + ghosts + " of " + live + " live sub-levels (" + percent
                + "%) are unclaimed. That is abandoned hulls being ticked every tick.";
        }
        return "§e" + ghosts + " of " + live + " live sub-levels (" + percent + "%) are unclaimed.";
    }

    /** True when the given name means "nobody ever named this". Blank and Sable's default both count. */
    public static boolean isDefaultName(String name) {
        return name == null || name.isBlank() || DEFAULT_NAME.equalsIgnoreCase(name.trim());
    }
}
