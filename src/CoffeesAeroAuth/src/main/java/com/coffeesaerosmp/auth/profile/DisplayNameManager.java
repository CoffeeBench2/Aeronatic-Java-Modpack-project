package com.coffeesaerosmp.auth.profile;

import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;

import java.util.UUID;

public class DisplayNameManager {

    /** Hard packet cap: ClientboundPlayerInfoUpdatePacket writes the profile name with writeUtf(16),
     *  so any GameProfile name longer than 16 chars fails encoding and kicks every packet recipient. */
    public static final int MAX_LENGTH = 16;

    private final ProfileStore store;

    public DisplayNameManager(ProfileStore store) {
        this.store = store;
    }

    /**
     * Called for brand-new profiles. Attempts to claim displayName (= username by default).
     *
     * Rules:
     *  - If the name is free, claim it.
     *  - If taken by a verified (premium) player, return that profile so the caller can kick.
     *  - If taken by another offline player, auto-suffix and claim the suffixed name.
     *
     * Returns the conflicting premium profile if a kick is warranted, null otherwise.
     */
    public PlayerProfile claimInitialName(PlayerProfile newProfile) {
        String name = newProfile.displayName;
        UUID owner  = store.getDisplayNameOwner(name);

        if (owner == null) {
            store.registerDisplayName(name, newProfile.getUUID());
            store.save(newProfile);
            return null;
        }

        if (owner.equals(newProfile.getUUID())) {
            // Already ours — shouldn't happen for new profiles, but safe
            return null;
        }

        PlayerProfile ownerProfile = store.get(owner);
        if (ownerProfile != null && ownerProfile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
            return ownerProfile; // signal: premium conflict, kick if configured
        }

        // Owner here is an OFFLINE player. Premium precedence: if the NEW profile is verified (premium),
        // it reclaims the name and the offline squatter is bounced back to /setname on their next join.
        // (claimInitialName is only invoked for new premium joins today, but branch defensively.)
        if (newProfile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
            store.releaseDisplayName(name);
            if (ownerProfile != null) {
                ownerProfile.displayName         = ownerProfile.username; // drop the contested name (keeps index rebuild correct)
                ownerProfile.nameApproved        = false;                 // force the lobby naming flow again
                ownerProfile.nameApprovalPending = false;
                ownerProfile.pendingDisplayName  = null;
                store.save(ownerProfile);
            }
            store.registerDisplayName(name, newProfile.getUUID());
            store.save(newProfile);
            return null;
        }

        // Offline-vs-offline conflict: auto-suffix until we find a free name.
        String resolved = findFreeName(name, newProfile.getUUID());
        newProfile.displayName = resolved;
        store.registerDisplayName(resolved, newProfile.getUUID());
        store.save(newProfile);
        return null;
    }

    /**
     * Attempts to set a custom display name for an existing player.
     * Returns null on success, or a user-facing error message on failure.
     */
    public String trySetDisplayName(PlayerProfile requester, String newName) {
        if (!isValidName(newName)) {
            return "Display name must be 3-16 characters (letters, numbers, underscores only).";
        }

        UUID owner = store.getDisplayNameOwner(newName);
        if (owner != null && !owner.equals(requester.getUUID())) {
            PlayerProfile ownerProfile = store.get(owner);
            if (ownerProfile != null && ownerProfile.getAccountType() == PlayerProfile.AccountType.PREMIUM) {
                return "That name is reserved by a verified player.";
            }
            return "That display name is already taken.";
        }

        store.releaseDisplayName(requester.displayName);
        requester.displayName = newName;
        store.registerDisplayName(newName, requester.getUUID());
        store.save(requester);
        return null;
    }

    private String findFreeName(String base, UUID uuid) {
        for (int i = 2; i <= 99; i++) {
            String candidate = base + "_" + i;
            if (candidate.length() > MAX_LENGTH) break;
            if (!store.isDisplayNameTaken(candidate)) return candidate;
        }
        // Fallback: append 4-hex UUID fragment
        String suffix = Integer.toHexString(Math.abs(uuid.hashCode()) & 0xFFFF);
        String candidate = base.substring(0, Math.min(base.length(), MAX_LENGTH - 5)) + "_" + suffix;
        return candidate.substring(0, Math.min(candidate.length(), MAX_LENGTH));
    }

    /**
     * Heals a legacy over-long display name (saved back when validation allowed 20 chars) down to
     * the 16-char packet cap, re-registering the shortened name in the index. Returns the profile's
     * (possibly unchanged) display name. Server thread only — writes through the store.
     */
    public String ensureFits(PlayerProfile profile) {
        String name = profile.displayName;
        if (name == null || name.length() <= MAX_LENGTH) return name;
        String base = name.substring(0, MAX_LENGTH);
        UUID owner = store.getDisplayNameOwner(base);
        String resolved = (owner == null || owner.equals(profile.getUUID()))
            ? base
            : findFreeName(base, profile.getUUID());
        store.releaseDisplayName(name);
        profile.displayName = resolved;
        store.registerDisplayName(resolved, profile.getUUID());
        store.save(profile);
        return resolved;
    }

    public static boolean isValidName(String name) {
        return name != null && name.matches("[a-zA-Z0-9_]{3," + MAX_LENGTH + "}");
    }
}
