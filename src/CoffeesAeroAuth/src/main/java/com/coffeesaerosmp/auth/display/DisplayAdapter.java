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
                nameColor = premium ? "§f" : "§7";

                // ── the badge slot now belongs to the purchased rank ─────────────────────
                // 🔑 An unranked PREMIUM player gets NO badge, and that is deliberate rather than an
                // omission. The old verified badge was "§6✈ " and the spec assigns ✈ to Navigator, so
                // keeping it would mean the paid tier-2 badge and "you own the game" render
                // identically — the tier would be worth nothing the moment anyone noticed.
                //
                // The OFFLINE marker survives until the premium-only sunset, because until then it is
                // still load-bearing information and an offline account can hold no rank anyway: the
                // store keys on players.mojang_uuid, which is NULL for every offline row.
                com.coffeesaerosmp.auth.store.Rank rank =
                    com.coffeesaerosmp.auth.store.StoreState.get(player.getUUID()).effectiveRank();
                if (rank != com.coffeesaerosmp.auth.store.Rank.NONE) {
                    badge = rank.badge();
                } else if (!premium) {
                    badge = "§8◈ ";
                }

                // A selected solid colour replaces the account-type default. Gradients are NOT
                // resolved here: this is a plain string, and a gradient needs a per-character
                // Component (see NameRender) — the gradient path overrides this downstream.
                String solid = com.coffeesaerosmp.auth.store.NameRender.nameplateColourCode(player.getUUID());
                if (solid != null) nameColor = solid;
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
