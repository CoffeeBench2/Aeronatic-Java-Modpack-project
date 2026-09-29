package com.coffeesaerosmp.auth.auth;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

/**
 * Manages the three auth display teams (a player is on exactly one):
 * <ul>
 *   <li><b>aero_login</b> — name-tag hidden, no collision. Used during the pre-login session so
 *       players can't see each other's names until verified.</li>
 *   <li><b>aero_verified</b> — gold airship badge "✈ " (premium, Mojang-verified).</li>
 *   <li><b>aero_guest</b> — gray "◈ " badge (offline/cracked).</li>
 * </ul>
 * The team prefix shows in the tab list and above the head. {@code addPlayerToTeam} moves the
 * player off any previous team automatically, so transitions are a single call.
 */
public final class NameVisibility {

    private static final String LOGIN    = "aero_login";
    private static final String VERIFIED = "aero_verified";
    private static final String GUEST    = "aero_guest";

    private NameVisibility() {}

    /** Pre-login: hide the player's name. */
    public static void hide(ServerPlayer player) {
        put(player, LOGIN);
    }

    /**
     * Verified/logged in: reveal with staff tag + rank badge + clan tag, and place the player on a
     * team whose NAME sorts them by rank.
     *
     * <p>A per-player team was always required because a shared badge team cannot vary its prefix.
     * That pre-existing choice is what makes rank sorting nearly free: the tab list is ordered by
     * scoreboard <b>team name</b>, ascending, with no team counting as {@code ""} — so the sort key
     * simply becomes the team name's first character and there is no team matrix to manage.
     *
     * <p>🔴 <b>Every player gets a team, even with a blank prefix.</b> A teamless player sorts as the
     * empty string, which is before every key, so leaving unranked players teamless would list them
     * ABOVE the Admirals — the exact inversion of what is being sold.
     *
     * <p>🔴 <b>The shared VERIFIED/GUEST teams are no longer used for prefixes.</b> They carried
     * {@code ✈}/{@code ◈} themselves, so falling back to them would re-apply the verified badge that
     * {@code DisplayAdapter} now deliberately withholds from unranked premium players — the badge
     * would come back via a different door. They are still swept in {@link #clear} because worlds
     * created before this change still contain them.
     *
     * @param premium retained for call-site compatibility and deliberately unused now: the offline
     *                marker moved into {@code DisplayAdapter}, which reads the account type from the
     *                profile itself. Kept rather than removed because the parameter will be deleted
     *                outright at the premium-only sunset, when there is no offline case left to mark,
     *                and churning two call sites twice is worse than one documented no-op.
     */
    public static void reveal(ServerPlayer player, boolean premium) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // composePrefix, NOT compose — a team prefix must EXCLUDE the name, because the client
        // appends the scoreboard name after it. Including it would render the name twice.
        String prefix = com.coffeesaerosmp.auth.display.PlayerDisplay.composePrefix(
            com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(player));

        com.coffeesaerosmp.auth.store.Rank rank =
            com.coffeesaerosmp.auth.store.StoreState.get(player.getUUID()).effectiveRank();

        ServerScoreboard sb = server.getScoreboard();
        String wanted = personalTeamName(player, rank);

        // The team name encodes the rank, so a rank change means a DIFFERENT team. Drop the old one
        // or the player keeps a second, stale team and the scoreboard accumulates one per rank they
        // have ever held.
        dropOtherPersonalTeams(sb, player, wanted);

        PlayerTeam team = sb.getPlayerTeam(wanted);
        if (team == null) team = sb.addPlayerTeam(wanted);
        team.setPlayerPrefix(Component.literal(prefix));

        // Nametag colour. The team prefix is one global string, so this is the only way to colour the
        // name above the head — and it is why a gradient collapses to its first stop there.
        net.minecraft.ChatFormatting colour =
            com.coffeesaerosmp.auth.store.NameRender.nameplateColour(player.getUUID());
        team.setColor(colour != null ? colour : net.minecraft.ChatFormatting.RESET);

        sb.addPlayerToTeam(player.getScoreboardName(), team);   // moves off any previous team
    }

    /** On disconnect: drop the player from all auth teams. */
    public static void clear(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerScoreboard sb = server.getScoreboard();
        for (String name : new String[]{LOGIN, VERIFIED, GUEST}) {
            PlayerTeam team = sb.getPlayerTeam(name);
            if (team != null && team.getPlayers().contains(player.getScoreboardName())) {
                sb.removePlayerFromTeam(player.getScoreboardName(), team);
            }
        }
        removePersonalTeam(player);
    }

    /**
     * Deterministic, uuid-based, and <b>rank-sorted</b>: {@code <sortKey><'a'><13 hex>} = 15 chars,
     * inside the 16-character scoreboard team-name limit with one to spare.
     *
     * <p>The leading {@link com.coffeesaerosmp.auth.store.Rank#sortKey()} is the whole mechanism for
     * "Admiral listed first" — the client sorts the tab list by team name ascending, so the key
     * descends with tier. The {@code 'a'} keeps the name from ever being all-digits, which reads badly
     * in {@code /team list} output.
     */
    private static String personalTeamName(ServerPlayer player, com.coffeesaerosmp.auth.store.Rank rank) {
        String uuid13 = player.getUUID().toString().replace("-", "").substring(0, 13);
        return rank.sortKey() + "a" + uuid13;
    }

    /**
     * Remove this player's personal teams under every OTHER sort key.
     *
     * <p>Enumerating the five possible keys is deliberately preferred to scanning every team on the
     * scoreboard: it is five map lookups regardless of how many teams exist, and FTB Teams plus the
     * clan system can put a lot of teams on a busy world.
     */
    private static void dropOtherPersonalTeams(ServerScoreboard sb, ServerPlayer player, String keep) {
        for (com.coffeesaerosmp.auth.store.Rank r : com.coffeesaerosmp.auth.store.Rank.values()) {
            String name = personalTeamName(player, r);
            if (name.equals(keep)) continue;
            PlayerTeam stale = sb.getPlayerTeam(name);
            if (stale != null) sb.removePlayerTeam(stale);
        }
        // Teams minted before rank sorting existed used the "ap_" prefix.
        PlayerTeam legacy = sb.getPlayerTeam("ap_" + player.getUUID().toString().replace("-", "").substring(0, 13));
        if (legacy != null) sb.removePlayerTeam(legacy);
    }

    private static void removePersonalTeam(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerScoreboard sb = server.getScoreboard();
        // No "keep" name — sweep every rank key and the legacy one.
        dropOtherPersonalTeams(sb, player, "");
    }

    private static void put(ServerPlayer player, String teamName) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerScoreboard sb = server.getScoreboard();
        sb.addPlayerToTeam(player.getScoreboardName(), ensure(sb, teamName));
    }

    private static PlayerTeam ensure(ServerScoreboard sb, String name) {
        PlayerTeam team = sb.getPlayerTeam(name);
        if (team == null) {
            team = sb.addPlayerTeam(name);
            if (name.equals(LOGIN)) {
                team.setNameTagVisibility(Team.Visibility.NEVER);
                team.setCollisionRule(Team.CollisionRule.NEVER);
            } else if (name.equals(VERIFIED)) {
                team.setPlayerPrefix(Component.literal("§6✈ "));   // gold airship = verified
            } else {
                team.setPlayerPrefix(Component.literal("§8◈ "));   // gray = guest
            }
        }
        return team;
    }
}
