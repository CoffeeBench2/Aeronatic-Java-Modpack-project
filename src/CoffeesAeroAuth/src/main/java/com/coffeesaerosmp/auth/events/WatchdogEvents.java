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

        // In-game announcement with the DISPLAY name. Vanilla's announceAdvancements broadcast (which
        // uses the account username) is disabled at startup when maskAdvancementNames is on, so this
        // replaces it. The earner's own toast popup is unaffected. Name comes straight from the profile
        // (same source Discord uses), so it never depends on getName()/getDisplayName() masking timing.
        if (com.coffeesaerosmp.auth.config.AuthConfig.MASK_ADVANCEMENT_NAMES.get() && player.getServer() != null) {
            var d = display.get();
            PlayerProfile prof = CoffeesAeroAuth.AUTH_MANAGER != null
                ? CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID()) : null;
            String name = prof != null && prof.displayName != null && !prof.displayName.isBlank()
                ? prof.displayName : player.getGameProfile().getName();
            // Rebuild the advancement name the way VANILLA does: wrapped in square brackets, tinted
            // by the frame type, and carrying a SHOW_TEXT hover of "<title>\n<description>".
            // Before 2026-07-27 this was only `getTitle().copy().withStyle(colour)` — plain coloured
            // text with no brackets and no hover, so players couldn't read what an advancement was
            // for. Vanilla's own broadcast is disabled (it prints the account username instead of the
            // display name), so whatever we build here is the ONLY chat announcement players see.
            net.minecraft.network.chat.Component hoverText = net.minecraft.network.chat.Component.empty()
                .append(d.getTitle())
                .append(net.minecraft.network.chat.CommonComponents.NEW_LINE)
                .append(d.getDescription());
            net.minecraft.network.chat.Component titleComp =
                net.minecraft.network.chat.ComponentUtils.wrapInSquareBrackets(d.getTitle().copy())
                    .withStyle(d.getType().getChatColor())
                    .withStyle(s -> s.withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, hoverText)));
            net.minecraft.network.chat.Component announce = net.minecraft.network.chat.Component.translatable(
                "chat.type.advancement." + d.getType().getSerializedName(),
                net.minecraft.network.chat.Component.literal(name), titleComp);
            player.getServer().getPlayerList().broadcastSystemMessage(announce, false);
        }

        if (CoffeesAeroAuth.DISCORD_BRIDGE != null) {
            CoffeesAeroAuth.DISCORD_BRIDGE.onAdvancement(player, title,
                display.get().getDescription().getString(),
                display.get().getType().name());
        }
        if (CoffeesAeroAuth.OBSIDIAN_EXPORTER != null) {
            CoffeesAeroAuth.OBSIDIAN_EXPORTER.onAdvancement(player, title);
        }
    }
}
