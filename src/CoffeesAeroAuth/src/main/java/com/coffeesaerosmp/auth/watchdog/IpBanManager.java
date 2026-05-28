package com.coffeesaerosmp.auth.watchdog;

import com.coffeesaerosmp.auth.util.NetUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory temporary ban store for IPs and /24 subnets. Never persisted — clears on server restart. */
public class IpBanManager {

    private final Map<String, Long> bans = new ConcurrentHashMap<>(); // key → expiry epoch ms

    public void ban(String ip, long durationMs) {
        bans.put(ip, System.currentTimeMillis() + durationMs);
    }

    public void banSubnet(String subnetPrefix, long durationMs) {
        bans.put("subnet:" + subnetPrefix, System.currentTimeMillis() + durationMs);
    }

    public boolean isBanned(String ip) {
        long now = System.currentTimeMillis();
        Long expiry = bans.get(ip);
        if (expiry != null) {
            if (expiry > now) return true;
            bans.remove(ip);
        }
        Long subnetExpiry = bans.get("subnet:" + NetUtil.subnetOf(ip));
        if (subnetExpiry != null) {
            if (subnetExpiry > now) return true;
            bans.remove("subnet:" + NetUtil.subnetOf(ip));
        }
        return false;
    }

    public void clearBan(String ip) {
        bans.remove(ip);
        bans.remove("subnet:" + NetUtil.subnetOf(ip));
    }

    public String getBanExpiry(String ip) {
        Long expiry = bans.get(ip);
        if (expiry == null) expiry = bans.get("subnet:" + NetUtil.subnetOf(ip));
        if (expiry == null || expiry <= System.currentTimeMillis()) return null;
        long remaining = (expiry - System.currentTimeMillis()) / 1000;
        return remaining + "s remaining";
    }
}
