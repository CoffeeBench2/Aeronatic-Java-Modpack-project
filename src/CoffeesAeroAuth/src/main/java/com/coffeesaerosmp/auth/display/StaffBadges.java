package com.coffeesaerosmp.auth.display;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Staff badge lookup. Rank comes from CONFIG, not from op level, so a moderator can be badged
 * without being handed command powers — and an op can go unbadged.
 *
 * <p>Pure and immutable: built from three comma-separated config strings and rebuilt when they
 * change, exactly like {@code DISPLAY_RGB_NAMES} is re-read on the tick. No Minecraft imports.</p>
 */
public final class StaffBadges {

    public static final String OWNER_BADGE = "§4[OWNER] ";
    public static final String ADMIN_BADGE = "§c[ADMIN] ";
    public static final String MOD_BADGE   = "§9[MOD] ";

    private final Set<String> owners;
    private final Set<String> admins;
    private final Set<String> mods;

    public StaffBadges(String ownerCsv, String adminCsv, String modCsv) {
        this.owners = parse(ownerCsv);
        this.admins = parse(adminCsv);
        this.mods   = parse(modCsv);
    }

    /** The badge for this account name, or {@code ""}. Highest rank wins. */
    public String badgeFor(String username) {
        if (username == null) return "";
        String key = username.toLowerCase(Locale.ROOT);
        if (owners.contains(key)) return OWNER_BADGE;
        if (admins.contains(key)) return ADMIN_BADGE;
        if (mods.contains(key))   return MOD_BADGE;
        return "";
    }

    /** True if this player may use staff-only cosmetics (e.g. an RGB clan tag). */
    public boolean isStaff(String username) {
        return !badgeFor(username).isEmpty();
    }

    private static Set<String> parse(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
