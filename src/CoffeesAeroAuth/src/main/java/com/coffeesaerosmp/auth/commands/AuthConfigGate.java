package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.commands.CommandSourceStack;

/**
 * Command visibility tied to config. Brigadier re-checks {@code requires} whenever it sends the command tree,
 * and a hidden command is simply "unknown" to the player — no new jar, no restart beyond a config edit
 * (players see the change on their next relog or /reload).
 */
final class AuthConfigGate {

    private AuthConfigGate() {}

    /**
     * Season 3 (owner, 2026-10-04): no survival teleportation except /spawn. {@code /tpa}, {@code /tpaccept},
     * {@code /tpdeny} and {@code /rtp} vanish for players when {@code survivalTeleportCommands = false}.
     * Ops (permission 2) keep them for admin work.
     */
    static boolean teleportAllowed(CommandSourceStack src) {
        if (src.hasPermission(2)) return true;
        try {
            return AuthConfig.SURVIVAL_TELEPORT_COMMANDS.get();
        } catch (Exception e) {
            return true;   // config not loaded yet: behave as before
        }
    }
}
