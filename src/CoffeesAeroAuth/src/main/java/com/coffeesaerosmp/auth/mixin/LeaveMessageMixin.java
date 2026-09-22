package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.display.HiddenOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Owns the vanilla "X left the game" line — the twin of {@link JoinMessageMixin}.
 *
 * <p>Before this existed a hidden op could join invisibly and then announce themselves to the whole
 * server by logging out, which defeated {@code /authmod hide} entirely. The public Discord feed was
 * already gated ({@code DiscordBridge.isHidden}); only the in-game line was not.
 *
 * <h3>Why this targets the packet listener and not {@code PlayerList}</h3>
 * The join line lives in {@code PlayerList#placeNewPlayer}, so the obvious symmetry would be
 * {@code PlayerList#remove}. That is WRONG: {@code PlayerList} never broadcasts the leave message.
 * Verified against the NeoForge-patched 1.21.1 classes — {@code "multiplayer.player.left"} appears
 * exactly once in the whole server, inside the private
 * {@code ServerGamePacketListenerImpl#removePlayerFromWorld()}, which broadcasts it BEFORE it calls
 * {@code PlayerList#remove}. Targeting {@code remove} finds no injection point at all.
 *
 * <p>There is no cancellable event for it either, so the call is redirected.
 *
 * <p>🔴 {@code require = 1}, deliberately. A silently-unapplied mixin here means hidden ops ARE
 * announced, with no error anywhere and no way for the person relying on being invisible to know.
 * Silent exposure is worse than a loud boot failure — and that strictness is exactly what caught
 * the wrong target above before it ever shipped.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class LeaveMessageMixin {

    @Shadow
    public ServerPlayer player;

    @Redirect(
        method = "removePlayerFromWorld",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"),
        require = 1)
    private void aeroauth$leaveLine(PlayerList list, Component message, boolean overlay) {
        ServerPlayer leaving = this.player;
        if (leaving == null) {           // nothing to identify — never drop the announcement
            list.broadcastSystemMessage(message, overlay);
            return;
        }
        if (HiddenOps.isHidden(leaving.getUUID())) return;   // swallow the announcement entirely

        try {
            var seg = com.coffeesaerosmp.auth.display.PlayerDisplay.segments(
                com.coffeesaerosmp.auth.display.DisplayAdapter.partsFor(leaving),
                com.coffeesaerosmp.auth.display.PlayerDisplay.Surface.JOIN,
                false);   // broadcast, so there is no viewer and no per-viewer reveal
            // Keep the vanilla translatable so the sentence stays localised and only the NAME
            // argument changes — the same treatment the join line gets, which also closes the
            // cosmetic gap where leaving dropped the player's badge, clan tag and colour.
            list.broadcastSystemMessage(
                Component.translatable("multiplayer.player.left",
                        Component.literal(seg.prefix() + seg.name()))
                    .withStyle(net.minecraft.ChatFormatting.YELLOW), overlay);
            return;
        } catch (Exception e) {
            com.coffeesaerosmp.auth.CoffeesAeroAuth.LOGGER.warn(
                "[Display] leave line fell back to vanilla for {}: {}",
                leaving.getGameProfile().getName(), e.getMessage());
        }
        list.broadcastSystemMessage(message, overlay);   // fallback: never lose the announcement
    }
}
