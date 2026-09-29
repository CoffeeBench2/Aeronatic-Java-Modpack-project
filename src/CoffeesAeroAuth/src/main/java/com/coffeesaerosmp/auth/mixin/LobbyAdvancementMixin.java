package com.coffeesaerosmp.auth.mixin;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops advancements being granted at all on the standalone lobby.
 *
 * <h2>Why a mixin and not a config or a gamerule</h2>
 * There are three separate things that announce an advancement and only two of them have switches:
 * <ol>
 *   <li>vanilla's chat broadcast — the {@code announceAdvancements} gamerule (already false here);</li>
 *   <li>this mod's replacement broadcast — {@code maskAdvancementNames} (turned off 2026-09-08);</li>
 *   <li>the <b>client-side toast popup</b>, which has no server switch whatsoever.</li>
 * </ol>
 * The toast is driven by the GRANT, so the only way to silence it is to not grant. Cancelling here
 * covers all three at once and additionally stops the lobby writing per-player advancement JSON for
 * a room nobody stays in.
 *
 * <h2>Scope — LOBBY role only</h2>
 * 🔴 On the SMP this must never fire: advancements there are real progress, they drive the Discord
 * feed and the Obsidian export, and the sidebar counts them. The role check is the entire safety
 * mechanism, so it is evaluated first and fails CLOSED — {@code isLobbyRole()} returns false on any
 * config problem, which means "behave like the SMP", i.e. grant normally.
 *
 * <h2>Why returning false is safe</h2>
 * {@code award} returns whether the criterion was newly completed. Returning false is exactly what
 * vanilla returns for a criterion that did not progress, so callers already handle it — it is not a
 * novel state. Nothing is written, no listeners fire, and no packet is sent to the client.
 *
 * <p>⚠️ Advancement data is stored PER WORLD ({@code world/advancements/&lt;uuid&gt;.json}), never in the
 * shared MySQL, so this cannot affect a player's progress on the SMP. It is also not retroactive:
 * anything already granted on the lobby stays in that file until it is deleted by hand.
 */
@Mixin(PlayerAdvancements.class)
public abstract class LobbyAdvancementMixin {

    @Inject(method = "award", at = @At("HEAD"), cancellable = true)
    private void coffees_aero_auth$noLobbyAdvancements(AdvancementHolder advancement, String criterion,
                                                       CallbackInfoReturnable<Boolean> cir) {
        if (com.coffeesaerosmp.auth.lobby.LobbyHandoff.isLobbyRole()) {
            cir.setReturnValue(false);
        }
    }
}
