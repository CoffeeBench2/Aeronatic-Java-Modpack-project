package com.coffeesaerosmp.auth.util;

import net.minecraft.server.level.ServerPlayer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class NetUtil {

    private NetUtil() {}

    public static String getPlayerIP(ServerPlayer player) {
        SocketAddress addr = player.connection.connection.getRemoteAddress();
        if (addr instanceof InetSocketAddress inet) {
            return inet.getAddress().getHostAddress();
        }
        return addr.toString();
    }

    /** Returns the /24 subnet prefix (e.g. "192.168.1" from "192.168.1.42"). */
    public static String subnetOf(String ip) {
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
