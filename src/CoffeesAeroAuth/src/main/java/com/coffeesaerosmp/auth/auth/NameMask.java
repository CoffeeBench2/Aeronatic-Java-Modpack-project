package com.coffeesaerosmp.auth.auth;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.mixin.PlayerGameProfileAccessor;
import com.coffeesaerosmp.auth.profile.DisplayNameManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Shows the approved {@code /setname} display name EVERYWHERE the real account name would appear —
 * tab list, nametag, death/leave messages, command targeting — by swapping the ServerPlayer's
 * GameProfile for one named after the display name once auth completes. The real account name is
 * never rendered to players; it stays in the DB ({@code username}) for identification, is shown
 * inline to admins by {@link com.coffeesaerosmp.auth.tablist.TabListManager}'s overlay, and is
 * available via {@code /authmod info}.
 *
 * <p>The swap keeps the UUID (world data, stats, auth, skins are all UUID-keyed) and copies profile
 * properties (skin textures). The rename happens in the private auth lobby, so other players never
 * see a mid-world identity flicker; the player-info rebroadcast updates tab entries everywhere, and
 * CoffeesAeroSkins' client handler re-arms skins on the recreated entries automatically.</p>
 */
public final class NameMask {

    private NameMask() {}

    /** The display name to render for this player, or {@code null} if none applies (no profile, or
     *  the profile name already matches). Used by the getDisplayName mixin for pre-auth messages. */
    public static String maskedNameFor(ServerPlayer player) {
        try {
            if (CoffeesAeroAuth.AUTH_MANAGER == null) return null;
            PlayerProfile p = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
            if (p == null || p.displayName == null || p.displayName.isBlank()) return null;
            return p.displayName.equals(player.getGameProfile().getName()) ? null : p.displayName;
        } catch (Exception e) {
            return null;
        }
    }

    /** Swap the player's GameProfile name to their display name (no-op when they already match).
     *  Call at auth-complete, BEFORE {@link NameVisibility#reveal} so the badge team attaches to the
     *  new scoreboard name. Server thread only. */
    public static void apply(ServerPlayer player) {
        String display = maskedNameFor(player);
        if (display == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // ClientboundPlayerInfoUpdatePacket caps the profile name at 16 chars — a longer name fails
        // encoding on EVERY connection the broadcast reaches (mass kick). Heal legacy 17-20 char
        // names (validation allowed 20 before 1.6.10) down to the cap before swapping the profile.
        if (display.length() > DisplayNameManager.MAX_LENGTH) {
            PlayerProfile p = CoffeesAeroAuth.AUTH_MANAGER.getStore().get(player.getUUID());
            String healed = CoffeesAeroAuth.AUTH_MANAGER.getDisplayNames().ensureFits(p);
            CoffeesAeroAuth.LOGGER.warn(
                "[NameMask] Display name '{}' exceeds the 16-char player-info packet limit — shortened to '{}'.",
                display, healed);
            display = healed;
            if (display == null || display.equals(player.getGameProfile().getName())) return;
        }

        // Team membership is keyed by scoreboard name — drop entries under the REAL name first.
        NameVisibility.clear(player);

        GameProfile old = player.getGameProfile();
        GameProfile masked = new GameProfile(old.getId(), display);
        masked.getProperties().putAll(old.getProperties());   // keep skin textures etc.
        ((PlayerGameProfileAccessor) player).aeroauth$setGameProfile(masked);

        // Rebroadcast the player-info entry so every client re-reads the (renamed) profile.
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        server.getPlayerList().broadcastAll(
            ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));

        CoffeesAeroAuth.LOGGER.info("[NameMask] {} now displays as '{}' (real name stays in DB).",
            old.getName(), display);
    }
}
