package com.coffeesaerosmp.core.net;

import com.coffeesaerosmp.core.config.AeroConfig;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Resolves the address the Join button connects to. The gate address is NOT stored in any shipped
 * config or as a plain string in this jar: it's baked in encrypted and only decrypted in memory at
 * connect time. (A determined player can always recover the address at connect — this hides it from
 * config browsing and casual jar inspection, which is the practical ceiling. It is obfuscation, not
 * access control; the real gate is the auth flow.)
 *
 * <p>The shipped client config sets {@code serverIp = "managed"}; any OTHER value is treated as an
 * explicit admin override (Admin Settings screen) and used verbatim.</p>
 *
 * <p><b>Hostname first, IP as fallback (1.3.17).</b> Baking a raw IP meant an Oracle address change
 * would brick every installed client until a client release propagated through the updater — slow,
 * and the updater is the thing least likely to be working if people can't get in. Baking ONLY a
 * hostname trades that for a different single point of failure: DuckDNS is free with no SLA, and a
 * DNS outage would lock everyone out of a perfectly healthy server. So both are baked, the hostname
 * is preferred, and the IP is used when the hostname does not resolve. Each failure mode is covered
 * by the other.</p>
 */
public final class ServerLocator {

    private ServerLocator() {}

    /** Config sentinel meaning "use the baked-in managed address". */
    public static final String MANAGED = "managed";

    // Base64 of XOR(address, KEY). Key halves live apart so an address never appears contiguously.
    /** Preferred: the DNS name. Survives the backing IP changing. */
    private static final String DATA_HOST = "sLu6sf2rsrGuuOujo/q4ovult7qv+fe8tO7u4q345g==";
    /** Fallback: the literal address. Survives DNS being unreachable. */
    private static final String DATA_IP   = "6uby47b45vru5qH04eHp4a0=";
    private static final byte[] K1 = {(byte) 0xD3, (byte) 0xD4, (byte) 0xDC, (byte) 0xD7};
    private static final byte[] K2 = {(byte) 0x98, (byte) 0xCE};

    /** Cached per session — one DNS probe per launch is plenty, and it keeps the click instant. */
    private static volatile String resolved = null;

    /** The address to connect to: the admin override if set, otherwise the managed gate address. */
    public static String resolve() {
        String configured = AeroConfig.SERVER_IP.get();
        if (configured != null && !configured.isBlank() && !MANAGED.equalsIgnoreCase(configured.trim())) {
            return configured.trim();
        }
        String cached = resolved;
        if (cached != null) return cached;

        String host = decrypt(DATA_HOST);
        String ip = decrypt(DATA_IP);
        String chosen = resolves(host) ? host : ip;
        resolved = chosen;
        return chosen;
    }

    /**
     * Does the hostname actually resolve right now? A failed lookup is the DuckDNS-is-down case, and
     * the answer is to fall back rather than hand Minecraft a name it will fail to connect to and
     * show the player a bare "Unknown host".
     */
    private static boolean resolves(String hostAndPort) {
        try {
            String host = hostAndPort;
            int colon = host.lastIndexOf(':');
            if (colon > 0) host = host.substring(0, colon);
            InetAddress.getByName(host);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Label safe to SHOW in UI (never the raw managed address). */
    public static String displayLabel() {
        String configured = AeroConfig.SERVER_IP.get();
        if (configured != null && !configured.isBlank() && !MANAGED.equalsIgnoreCase(configured.trim())) {
            return configured.trim();
        }
        return "Coffees Aero SMP (managed)";
    }

    private static String decrypt(String data) {
        byte[] bytes = Base64.getDecoder().decode(data);
        byte[] key = new byte[K1.length + K2.length];
        System.arraycopy(K1, 0, key, 0, K1.length);
        System.arraycopy(K2, 0, key, K1.length, K2.length);
        for (int i = 0; i < bytes.length; i++) bytes[i] ^= key[i % key.length];
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
