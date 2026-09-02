package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.display.HiddenOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Owns the vanilla "X joined the game" line — both hiding it for ops who have hidden themselves,
 * and rebuilding it so the player's name carries their badge, staff tag, clan tag and colour.
 *
 * <p>There is no cancellable event for it — {@code PlayerList#placeNewPlayer} calls
 * {@code broadcastSystemMessage} directly — so the call is redirected. Doing it HERE rather than in
 * {@code Player.getDisplayName()} is deliberate: three other call sites read that method and
 * prepending badges to it breaks vote-reward sound selection and two chat sentences.</p>
 *
 * <p>{@code require = 0}: if the target ever moves, joins are announced the vanilla way rather than
 * the server failing to boot. Failure is therefore SILENT — verify it applied, never assume.</p>
 */
@Mixin(PlayerList.class)
public abstract class JoinMessageMixin {

    @Redirect(
        method = "placeNewPlayer",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"),
        require = 0)
    private void aeroauth$joinLine(PlayerList list, Component message, boolean overlay,
                                   net.minecraft.network.Connection connection,
                                   ServerPlayer player,
                                   net.minecraft.server.network.CommonListenerCookie cookie) {
        if (HiddenOps.isHidden(player.getUUID())) return;   // swallow the announcement entirely

        try {
            var seg = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(player),
                com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.JOIN,
                false);   // broadcast, so there is no viewer and no per-viewer reveal
            // Keep the vanilla translatable so the sentence stays localised; only the NAME argument
            // changes. Rebuilding the whole string would hardcode English.
            list.broadcastSystemMessage(
                Component.translatable("multiplayer.player.joined",
                    Component.literal(seg.prefix() + seg.name())), overlay);
            return;
        } catch (Exception e) {
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.warn(
                "[Display] join line fell back to vanilla for {}: {}",
                player.getGameProfile().getName(), e.getMessage());
        }
        list.broadcastSystemMessage(message, overlay);   // fallback: never lose the announcement
    }
}
