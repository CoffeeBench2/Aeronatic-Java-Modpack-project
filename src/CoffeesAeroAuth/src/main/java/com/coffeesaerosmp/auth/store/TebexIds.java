package com.coffeesaerosmp.auth.store;

import java.util.UUID;

/** Tebex's {id} for a Minecraft package: a Mojang uuid, sent with or without dashes. Pure, unit-tested. */
public final class TebexIds {

    private TebexIds() {}

    /** The uuid, or null for anything else (a {username} misconfiguration must fail loudly, never deliver). */
    public static UUID parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.matches("[0-9a-fA-F]{32}")) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        if (!s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
