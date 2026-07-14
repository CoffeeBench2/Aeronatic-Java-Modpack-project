package com.coffeesaerosmp.auth.util;

import net.minecraft.server.level.ServerPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class NetUtil {

    private NetUtil() {}

    public static String getPlayerIP(ServerPlayer player) {
        SocketAddress addr = player.connection.getRemoteAddress();
        if (addr instanceof InetSocketAddress inet) {
            return inet.getAddress().getHostAddress();
        }
        return addr.toString();
    }

    /** Returns the /24 subnet prefix (e.g. "192.168.1" from "192.168.1.42").
     *  IPv6: the /64 prefix (first 4 hextets) — a residential customer's whole allocation,
     *  so subnet bans catch address rotation within it. */
    public static String subnetOf(String ip) {
        if (ip.indexOf(':') >= 0) {
            String[] g = ip.split(":");
            if (g.length > 4) return g[0] + ":" + g[1] + ":" + g[2] + ":" + g[3];
            return ip;
        }
        int lastDot = ip.lastIndexOf('.');
        return lastDot > 0 ? ip.substring(0, lastDot) : ip;
    }

    /** Partially masks an IP for player-facing display: 203.0.x.x */
    public static String maskIp(String ip) {
        String[] p = ip.split("\\.");
        if (p.length == 4) return p[0] + "." + p[1] + ".x.x";
        return "x.x.x.x";
    }
}
