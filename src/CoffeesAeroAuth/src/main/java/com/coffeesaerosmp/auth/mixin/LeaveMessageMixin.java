package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.display.HiddenOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Owns the vanilla "X left the game" line — the twin of {@link JoinMessageMixin}.
 *
 * <p>Before this existed a hidden op could join invisibly and then announce themselves to the whole
 * server by logging out, which defeated {@code /authmod hide} entirely. The public Discord feed was
 * already gated ({@code DiscordBridge.isHidden}); only the in-game line was not.
 *
 * <p>There is no cancellable event — {@code PlayerList#remove} calls {@code broadcastSystemMessage}
 * directly — so the call is redirected, exactly as the join line is.
 *
 * <p>🔴 {@code require = 1}, deliberately, unlike the join mixin's original {@code require = 0}.
 * A silently-unapplied mixin here means hidden ops ARE announced, with no error anywhere and no way
 * for the person relying on being invisible to know. Silent exposure is worse than a loud boot
 * failure. The accepted cost is a refused boot if a NeoForge update moves {@code remove}.
 */
@Mixin(PlayerList.class)
public abstract class LeaveMessageMixin {

    @Redirect(
        method = "remove",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"),
        require = 1)
    private void aeroauth$leaveLine(PlayerList list, Component message, boolean overlay,
                                    ServerPlayer player) {
        if (HiddenOps.isHidden(player.getUUID())) return;   // swallow the announcement entirely

        try {
            var seg = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(player),
                com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.JOIN,
                false);   // broadcast, so there is no viewer and no per-viewer reveal
            // Keep the vanilla translatable so the sentence stays localised and only the NAME
            // argument changes — same treatment the join line already gets, which also closes the
            // cosmetic gap where leaving dropped the player's badge, clan tag and colour.
            list.broadcastSystemMessage(
                Component.translatable("multiplayer.player.left",
                        Component.literal(seg.prefix() + seg.name()))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), overlay);
            return;
        } catch (Exception e) {
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.warn(
                "[Display] leave line fell back to vanilla for {}: {}",
                player.getGameProfile().getName(), e.getMessage());
        }
        list.broadcastSystemMessage(message, overlay);   // fallback: never lose the announcement
    }
}
