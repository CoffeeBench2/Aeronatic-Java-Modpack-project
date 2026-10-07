package com.coffeesaerosmp.auth.tablist;

/** Tab-list ping number. Pure (no Minecraft classes) so it is unit-testable. */
public final class PingText {
    private PingText() {}

    /** " 42ms", coloured by quality; empty until the first keep-alive has measured anything. */
    public static String of(int ms) {
        if (ms <= 0) return "";
        String c = ms < 80 ? "§a" : ms < 150 ? "§e" : ms < 300 ? "§6" : "§c";
        return " " + c + ms + "ms";
    }
}
