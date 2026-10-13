package com.coffeesaerosmp.auth.cosmetics;

/** Dotted version compare for the AeroCore handshake. Pure (no Minecraft), so it is unit-tested. */
public final class CoreVersions {

    private CoreVersions() {}

    /**
     * Is {@code have} >= {@code need}? Missing parts count as 0 and any non-digit suffix is ignored
     * ("1.4.0-cf" = 1.4.0). A blank or unparseable {@code have} is never enough; a blank {@code need} always is.
     */
    public static boolean atLeast(String have, String need) {
        int[] h = parse(have), n = parse(need);
        if (h == null) return false;
        if (n == null) return true;
        for (int i = 0; i < Math.max(h.length, n.length); i++) {
            int a = i < h.length ? h[i] : 0, b = i < n.length ? n[i] : 0;
            if (a != b) return a > b;
        }
        return true;
    }

    private static int[] parse(String v) {
        if (v == null || v.isBlank()) return null;
        String core = v.trim().split("[-+ ]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("\\D.*$", "");
            if (digits.isEmpty()) return null;
            try { out[i] = Integer.parseInt(digits); } catch (NumberFormatException e) { return null; }
        }
        return out;
    }
}
