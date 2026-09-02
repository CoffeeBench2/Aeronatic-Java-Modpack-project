package com.coffeesaerosmp.auth.display;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.clan.ClanTags;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import net.minecraft.server.level.ServerPlayer;

/**
 * Bridges Minecraft objects to {@link PlayerDisplay}'s plain-string {@link PlayerDisplay.Parts}.
 * Deliberately the ONLY class in this package that imports {@code net.minecraft} — everything
 * decision-shaped lives in the pure core so it can be unit-tested without a server.
 */
public final class DisplayAdapter {

    private static volatile StaffBadges badges = new StaffBadges("", "", "");

    private DisplayAdapter() {}

    /** Re-read the staff lists from config. Call on the same cadence as the RGB-name refresh. */
    public static void refreshStaff() {
        badges = new StaffBadges(AuthConfig.STAFF_OWNER.get(),
                                 AuthConfig.STAFF_ADMIN.get(),
                                 AuthConfig.STAFF_MOD.get());
    }

    public static StaffBadges staff() { return badges; }

    /** Builds the render parts for a player. Never throws — a display bug must not break login. */
    public static PlayerDisplay.Parts partsFor(ServerPlayer player) {
        String username  = player.getGameProfile().getName();
        String display   = username;
        String badge     = "";
        // The name MUST carry its own colour. Legacy § codes persist within a literal, so without
        // one the name inherits the last code emitted by the decoration — §7 gray after a clan tag,
        // or §8 near-black for a guest with no tag. Today's code already does this: TabListManager
        // used (premium ? "§6✈ §f" : "§8◈ §7") and ChatEvents prepends nameColor.
        String nameColor = "§f";
        try {
            PlayerProfile p = CoffeesAeroAuth.PROFILE_STORE != null
                ? CoffeesAeroAuth.PROFILE_STORE.get(player.getUUID()) : null;
            if (p != null) {
                if (p.username != null) username = p.username;
                display = p.displayName != null ? p.displayName : username;
                boolean premium = p.getAccountType() == PlayerProfile.AccountType.PREMIUM;
                badge     = premium ? "§6✈ " : "§8◈ ";
                nameColor = premium ? "§f"   : "§7";
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Display] profile lookup failed for {}: {}",
                player.getGameProfile().getName(), e.getMessage());
        }

        String clan = "";
        try {
            String tag = ClanTags.tagFor(player);
            if (tag != null) clan = "§7[" + ClanTags.colorFor(player) + tag + "§7] ";
        } catch (Exception ignored) {
            // FTB Teams not ready — render untagged rather than break the caller.
        }

        String realName = username.equals(display) ? null : username;
        return new PlayerDisplay.Parts(badge, badges.badgeFor(username), clan,
                                       nameColor + display, realName);
    }
}
