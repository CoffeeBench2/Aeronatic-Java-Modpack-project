package com.coffeesaerosmp.auth.db;

import java.util.UUID;

public class PlayerProfile {

    // Stored as string — Gson doesn't handle UUID natively
    public String uuidStr;
    public String username;         // Minecraft username (may change)
    public String displayName;      // Chosen display name, globally unique
    public String bio;
    public String accountType;      // "PREMIUM" or "OFFLINE"
    public String passwordHash;     // null for premium accounts
    public String passwordSalt;     // null for premium accounts
    public long   joinDate;         // epoch millis of first join
    public long   totalPlaytimeSeconds;
    public long   sessionStartEpoch; // set on auth, cleared on leave
    public String skinUrl;           // null = default skin
    public boolean firstJoinComplete; // true after first-join sequence plays

    // Transient: not serialized, computed on load
    public transient UUID uuid;

    public PlayerProfile() {}

    public PlayerProfile(UUID uuid, String username, AccountType type) {
        this.uuidStr      = uuid.toString();
        this.uuid         = uuid;
        this.username     = username;
        this.displayName  = username; // default = MC username; may be overridden by DisplayNameManager
        this.bio          = "";
        this.accountType  = type.name();
        this.joinDate     = System.currentTimeMillis();
        this.totalPlaytimeSeconds = 0;
        this.sessionStartEpoch   = 0;
        this.firstJoinComplete   = false;
    }

    public UUID getUUID() {
        if (uuid == null && uuidStr != null) uuid = UUID.fromString(uuidStr);
        return uuid;
    }

    public AccountType getAccountType() {
        return AccountType.valueOf(accountType);
    }

    public enum AccountType {
        PREMIUM, OFFLINE
    }
}
