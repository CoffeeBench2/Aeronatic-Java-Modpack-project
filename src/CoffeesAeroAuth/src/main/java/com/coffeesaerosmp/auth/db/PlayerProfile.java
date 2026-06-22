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
    public boolean startupBonusGiven; // true after the one-time starter currency is granted on first /spawn

    // Transient: not serialized, computed on load
    public int     nameChangesUsed;  // lifetime counter — capped at 1; 0 = change still available

    // Room / approval system (offline players only)
    public boolean nameApproved;         // true = name permanently approved, skip room on next join
    public boolean nameApprovalPending;  // true = name submitted, awaiting admin decision
    public String  pendingDisplayName;   // proposed name while in approval queue
    public int     nameRejectionCount;   // rejections this account lifetime (not reset on reconnect)
    public int     roomSlot;             // assigned room slot index (-1 = unassigned)
    public long    roomCreatedAt;        // epoch ms when room was first built

    public transient UUID uuid;

    public PlayerProfile() {
        this.roomSlot = -1;
    }

    public PlayerProfile(UUID uuid, String username, AccountType type) {
        this.uuidStr      = uuid.toString();
        this.uuid         = uuid;
        this.username     = username;
        this.displayName  = username;
        this.bio          = "";
        this.accountType  = type.name();
        this.joinDate     = System.currentTimeMillis();
        this.totalPlaytimeSeconds = 0;
        this.sessionStartEpoch   = 0;
        this.firstJoinComplete   = false;
        this.startupBonusGiven    = false;
        this.nameApproved         = (type == AccountType.PREMIUM); // premium players auto-approved
        this.nameApprovalPending  = false;
        this.nameRejectionCount   = 0;
        this.roomSlot             = -1;
        this.roomCreatedAt        = 0;
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
