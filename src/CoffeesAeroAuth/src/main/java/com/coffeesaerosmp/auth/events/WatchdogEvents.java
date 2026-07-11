package com.coffeesaerosmp.auth.events;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;

public class WatchdogEvents {

    /** Command velocity check + admin command tracking. Cancels if throttled. */
    public static void onCommand(CommandEvent event) {
        if (CoffeesAeroAuth.WATCHDOG == null) return;
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) return;

        String cmd = event.getParseResults().getReader().getString();

        // Admin command: track rate + quiet hours
        if (cmd.startsWith("authmod") && player.hasPermissions(2)) {
            if (CoffeesAeroAuth.WATCHDOG.isAdminLocked(player.getUUID())) {
                event.setCanceled(true);
                player.sendSystemMessage(TextUtil.error("Admin commands are temporarily locked by the watchdog."));
                return;
            }
            CoffeesAeroAuth.WATCHDOG.recordAdminCommand(player, cmd);
        }

        // Velocity check for all commands
        if (!CoffeesAeroAuth.WATCHDOG.checkCommandVelocity(player)) {
            event.setCanceled(true);
        }
    }

    /** Movement speed check for authenticated players. */
    public static void onLivingTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (CoffeesAeroAuth.WATCHDOG == null) return;
        if (CoffeesAeroAuth.AUTH_MANAGER == null) return;
        if (!CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(player.getUUID())) return;
        CoffeesAeroAuth.WATCHDOG.checkMovement(player);
    }

    /** Death event → Discord public channel. */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (CoffeesAeroAuth.DISCORD_BRIDGE == null) return;
        String deathMsg = event.getSource().getLocalizedDeathMessage(player).getString();
        CoffeesAeroAuth.DISCORD_BRIDGE.onPlayerDeath(player, deathMsg);
    }

    /** Advancement earned → Discord public channel + Obsidian player file. */
    public static void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Only real, chat-announced advancements. Technical/recipe advancements (no display, or
        // announceChat=false) also fire this event — a fresh join re-grants batches of them, which
        // spammed ~10 "achievements" per join into Discord.
        var display = event.getAdvancement().value().display();
        if (display.isEmpty() || !display.get().shouldAnnounceChat()) return;
        String title = display.get().getTitle().getString();
        if (CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            CoffeesAeroAuth.DISCORD_BRIDGE.onAdvancement(player, title);
        }
        if (CoffeesAeroAuth.OBSIDIAN_EXPORTER != null) {
            CoffeesAeroAuth.OBSIDIAN_EXPORTER.onAdvancement(player, title);
        }
    }
}
