package com.coffeesaerosmp.core.net;

import com.coffeesaerosmp.core.config.AeroConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Resolves the address the Join button connects to. The gate address is NOT stored in any shipped
 * config or as a plain string in this jar: it's baked in encrypted and only decrypted in memory at
 * connect time. (A determined player can always recover the address at connect — this hides it from
 * config browsing and casual jar inspection, which is the practical ceiling.)
 *
 * <p>The shipped client config sets {@code serverIp = "managed"}; any OTHER value is treated as an
 * explicit admin override (Admin Settings screen) and used verbatim.</p>
 */
public final class ServerLocator {

    private ServerLocator() {}

    /** Config sentinel meaning "use the baked-in managed address". */
    public static final String MANAGED = "managed";

    // Base64 of XOR(address, KEY). Key halves live apart so the address never appears contiguously.
    private static final String DATA = "6uby47b45vru5qH04eHp4a0=";
    private static final byte[] K1 = {(byte) 0xD3, (byte) 0xD4, (byte) 0xDC, (byte) 0xD7};
    private static final byte[] K2 = {(byte) 0x98, (byte) 0xCE};

    /** The address to connect to: the admin override if set, otherwise the managed gate address. */
    public static String resolve() {
        String configured = AeroConfig.SERVER_IP.get();
        if (configured != null && !configured.isBlank() && !MANAGED.equalsIgnoreCase(configured.trim())) {
            return configured.trim();
        }
        return decrypt();
    }

    /** Label safe to SHOW in UI (never the raw managed address). */
    public static String displayLabel() {
        String configured = AeroConfig.SERVER_IP.get();
        if (configured != null && !configured.isBlank() && !MANAGED.equalsIgnoreCase(configured.trim())) {
            return configured.trim();
        }
        return "Coffees Aero SMP (managed)";
    }

    private static String decrypt() {
        byte[] data = Base64.getDecoder().decode(DATA);
        byte[] key = new byte[K1.length + K2.length];
        System.arraycopy(K1, 0, key, 0, K1.length);
        System.arraycopy(K2, 0, key, K1.length, K2.length);
        for (int i = 0; i < data.length; i++) data[i] ^= key[i % key.length];
        return new String(data, StandardCharsets.UTF_8);
    }
}
