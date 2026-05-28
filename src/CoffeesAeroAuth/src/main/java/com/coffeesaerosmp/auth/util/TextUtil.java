package com.coffeesaerosmp.auth.util;

import net.minecraft.network.chat.Component;

public final class TextUtil {

    public static final String PREFIX         = "§6[AeroAuth]§r ";
    public static final String VERIFIED_BADGE = "§6[✦ §eVerified§6]§r ";
    public static final String OFFLINE_BADGE  = "§8[◈ §7Offline§8]§r ";

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
