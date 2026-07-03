package com.coffeesaerosmp.auth.util;

import net.minecraft.network.chat.Component;

public final class TextUtil {

    // Coffees Aero SMP house style — amber/gold airship theme. Restyle here to retheme everything.
    public static final String PREFIX         = "§6✈ §eAeroSMP §8» §r";
    // Chat badges: small icon only (no "[Verified]"/"[Guest]" words) — gold for premium, grey for offline.
    public static final String VERIFIED_BADGE = "§6✦ ";
    public static final String OFFLINE_BADGE  = "§7◈ ";

    private TextUtil() {}

    public static Component prefixed(String msg) {
        return Component.literal(PREFIX + msg);
    }

    public static Component error(String msg) {
        return Component.literal(PREFIX + "§c" + msg);
    }

    public static Component success(String msg) {
        return Component.literal(PREFIX + "§a" + msg);
    }

    public static Component info(String msg) {
        return Component.literal(PREFIX + "§7" + msg);
    }
}
