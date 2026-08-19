package com.coffeesaerosmp.auth.mixin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.players.GameProfileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/**
 * Makes {@code /setname} display names resolvable by name-based commands.
 *
 * <h2>The bug this fixes</h2>
 *
 * There are TWO name-resolution paths in Minecraft and they behave differently:
 *
 * <ul>
 *   <li>{@code EntityArgument} ({@code /give}, {@code /tp}) walks the ONLINE player list comparing
 *       {@code getGameProfile().getName()}. {@link com.coffeesaerosmp.auth.auth.NameMask} swaps that
 *       profile, so display names already work there.</li>
 *   <li><b>{@code GameProfileArgument}</b> — which FTB Teams uses for party invites — resolves a
 *       plain name through {@link GameProfileCache} ONLY. It never consults the player list.</li>
 * </ul>
 *
 * {@code usercache.json} is written during login, before auth completes, so it holds the REAL account
 * name. NameMask never touches it. A display name therefore never matches.
 *
 * <h2>🔴 Why the failure is silent instead of an error</h2>
 *
 * On an offline-mode backend ({@code online-mode=false}, which this server is)
 * {@code GameProfileCache.get(String)} does <b>not</b> return empty for an unknown name — vanilla's
 * {@code createUnknownProfile} only returns empty when {@code usesAuthentication()} is true.
 * Otherwise it FABRICATES a profile via {@code UUIDUtil.createOfflineProfile(name)}.
 *
 * <p>So inviting someone by their display name "succeeded" — against a phantom account whose UUID
 * was derived from the display-name string and belongs to nobody. The real player was never invited
 * and saw nothing. That is also why a RETURN-and-check-for-empty injection would be useless here:
 * the return is never empty.
 *
 * <p>Hence HEAD + cancellable: answer from our own store before vanilla can invent anything.
 *
 * <h2>Scope</h2>
 *
 * Deliberately narrow. It only answers when the input matches a {@code displayName} that DIFFERS
 * from the account name — an exact username still falls through to vanilla untouched, so nothing
 * that works today changes behaviour. Fixes every {@code GameProfileArgument} consumer at once
 * (FTB Teams invites, {@code /ban}, {@code /whitelist}, …), which is what NameMask's own
 * documentation already claims to do for "command targeting".
 *
 * <p>{@code getAsync} delegates to this same method, so the background path is covered too — and
 * {@code ProfileStore}'s maps are {@code ConcurrentHashMap}, so being called off-thread is safe.
 */
@Mixin(GameProfileCache.class)
public class GameProfileCacheDisplayNameMixin {

    /** Logged once, so the log proves the mixin applied without spamming every lookup. */
    private static boolean aeroauth$loggedFirstHit = false;

    @Inject(method = "get(Ljava/lang/String;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void aeroauth$resolveDisplayName(String name, CallbackInfoReturnable<Optional<GameProfile>> cir) {
        if (name == null || name.isBlank()) return;
        if (CoffeesAeroAuth.PROFILE_STORE == null) return;      // pre-boot lookups
        try {
            if (!AuthConfig.RESOLVE_DISPLAY_NAMES.get()) return;

            PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.findByAnyName(name);
            if (profile == null || profile.displayName == null) return;

            // Only intervene for a genuine display name. findByAnyName also matches usernames, and
            // those already resolve correctly through vanilla — shadowing them would be pure risk.
            if (!name.equalsIgnoreCase(profile.displayName)) return;
            if (name.equalsIgnoreCase(profile.username)) return;

            UUID id = profile.getUUID();
            if (id == null) return;

            // Carry the DISPLAY name, not the account name: this server hides real names from
            // players, and a consumer that stores or renders what it resolved (FTB Teams shows
            // members in its GUI) would otherwise leak the account name. The UUID is what actually
            // decides team membership, and it is correct either way.
            cir.setReturnValue(Optional.of(new GameProfile(id, profile.displayName)));

            if (!aeroauth$loggedFirstHit) {
                aeroauth$loggedFirstHit = true;
                CoffeesAeroAuth.LOGGER.info(
                    "[NameMask] Display-name resolution is active — '{}' resolved to {} ({}). "
                    + "Name-based commands (FTB Teams invites, /ban, /whitelist) now accept display names.",
                    name, id, profile.username);
            }
        } catch (Throwable t) {
            // A profile lookup must never be able to break login or a command.
            CoffeesAeroAuth.LOGGER.warn("[NameMask] Display-name resolution failed for '{}'", name, t);
        }
    }
}
