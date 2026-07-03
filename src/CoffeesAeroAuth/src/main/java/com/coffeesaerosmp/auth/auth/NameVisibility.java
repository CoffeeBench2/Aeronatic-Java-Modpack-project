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

    /** Verified/logged in: reveal with the proper badge. */
    public static void reveal(ServerPlayer player, boolean premium) {
        put(player, premium ? VERIFIED : GUEST);
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
