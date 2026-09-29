package com.coffeesaerosmp.auth.moderation;

import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.CommandEvent;

import java.util.Locale;

/**
 * The Minecraft-facing half of confiscation: command blocking and the on-join notice.
 *
 * <p>Separate from {@link Confiscation} because that class is deliberately pure — it imports no
 * Minecraft at all so it can be unit-tested without a running game.
 */
public final class ConfiscationEvents {

    private ConfiscationEvents() {}

    /**
     * A confiscated player runs nothing outside the configured allow-list.
     *
     * <p>Chat is deliberately NOT blocked — the entire point is that they can answer the admin
     * holding them.
     *
     * <p>🔑 The namespace strip matters: without it a held player types {@code /ftbessentials:back}
     * and walks straight through the block. Copied from {@code CombatGuard.onCommand}, which
     * learned this the same way.
     */
    public static void onCommand(CommandEvent event) {
        CommandSourceStack src = event.getParseResults().getContext().getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) return;
        if (!Confiscation.isHeld(player.getUUID())) return;

        String input = event.getParseResults().getReader().getString().trim();
        if (input.startsWith("/")) input = input.substring(1);
        int sp = input.indexOf(' ');
        String root = (sp < 0 ? input : input.substring(0, sp)).toLowerCase(Locale.ROOT);
        int colon = root.indexOf(':');
        if (colon >= 0) root = root.substring(colon + 1);

        if (AuthConfig.CONFISCATE_ALLOWED_COMMANDS.get().contains(root)) return;

        event.setCanceled(true);
        player.sendSystemMessage(Component.literal(
            "§cYou are confiscated — commands are disabled. Speak to an admin in chat."));
    }

    /** Tells a held player why they cannot move, the moment they join. */
    public static void onPlayerLoggedIn(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Confiscation.Hold held = Confiscation.get(player.getUUID());
        if (held == null) return;
        player.sendSystemMessage(Component.literal(
            "§c§lYou are CONFISCATED. §7You cannot move or interact until an admin releases you."
            + (held.reason() == null || held.reason().isBlank()
                ? "" : "\n§7Reason: §f" + held.reason())
            + "\n§7Held by: §f" + held.actor()
            + "\n§7You can still talk in chat."));
    }
}
