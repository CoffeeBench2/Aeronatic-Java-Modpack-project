package com.coffeesaerosmp.auth.discord;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discord ↔ Minecraft account linking (1.6.10).
 *
 * <p>Verification flow: an authenticated player runs {@code /discord link} in-game → gets a
 * one-time 6-character code (5-minute TTL, one active code per player) → runs {@code /link <code>}
 * in the Discord server → the interaction's Discord user id is stored on the profile
 * ({@code discordId}). Linked players get @mentioned on their public achievement / playtime-
 * milestone posts; unlinked players are never mentioned.</p>
 */
public final class LinkManager {

    private static final long   CODE_TTL_MS = 5 * 60_000;
    // No 0/O/1/I — players retype these across two apps.
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private record Pending(UUID uuid, String mcName, long expiresAt) {}

    private static final Map<String, Pending> CODES = new ConcurrentHashMap<>();

    private LinkManager() {}

    /** Issues a fresh code for the player (replacing any previous one). */
    public static String createCode(UUID uuid, String mcName) {
        long now = System.currentTimeMillis();
        CODES.entrySet().removeIf(e -> e.getValue().expiresAt() < now || e.getValue().uuid().equals(uuid));
        String code;
        do {
            StringBuilder sb = new StringBuilder(6);
            for (int i = 0; i < 6; i++) sb.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
            code = sb.toString();
        } while (CODES.containsKey(code));
        CODES.put(code, new Pending(uuid, mcName, now + CODE_TTL_MS));
        return code;
    }

    /**
     * Completes a link from the Discord side. Server thread only (writes the profile).
     * Returns a user-facing result message for the ephemeral interaction response.
     */
    public static String completeLink(String code, String discordUserId) {
        if (code == null || discordUserId == null || discordUserId.isBlank())
            return "❌ Invalid link request.";
        Pending p = CODES.remove(code.trim().toUpperCase(java.util.Locale.ROOT));
        if (p == null || p.expiresAt() < System.currentTimeMillis())
            return "❌ That code is invalid or expired. Run `/discord link` in-game for a fresh one.";
        if (CoffeesAeroAuth.PROFILE_STORE == null) return "❌ Server profile store unavailable — try again.";
        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.get(p.uuid());
        if (profile == null) return "❌ Minecraft profile not found — rejoin the server and retry.";
        profile.discordId = discordUserId;
        CoffeesAeroAuth.PROFILE_STORE.save(profile);
        CoffeesAeroAuth.LOGGER.info("[Link] {} linked to Discord user {}.", p.mcName(), discordUserId);
        if (CoffeesAeroAuth.WATCHDOG != null)
            CoffeesAeroAuth.WATCHDOG.recordModAction("DISCORD_LINK player=" + p.mcName() + " discord=" + discordUserId);
        return "✅ Linked to **" + p.mcName() + "**! You'll now be tagged on your achievements and milestones.";
    }

    /** Clears the link. Server thread only. Returns true if a link existed. */
    public static boolean unlink(UUID uuid) {
        if (CoffeesAeroAuth.PROFILE_STORE == null) return false;
        PlayerProfile profile = CoffeesAeroAuth.PROFILE_STORE.get(uuid);
        if (profile == null || profile.discordId == null || profile.discordId.isBlank()) return false;
        CoffeesAeroAuth.LOGGER.info("[Link] {} unlinked from Discord user {}.", profile.displayName, profile.discordId);
        profile.discordId = null;
        CoffeesAeroAuth.PROFILE_STORE.save(profile);
        return true;
    }
}
