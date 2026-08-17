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
    /**
     * Epoch millis the profile was last saved — in practice the player's last logout, since a save
     * always runs on leave. The {@code last_seen} column was written on every save but was NEVER
     * mapped back into Java until 2026-07-27, so nothing could read it. It is now the clock behind
     * the lobby-bypass rule (premium players have no session token, so SessionTokenManager cannot
     * answer "how long were they away?"). 0 = never recorded → treated as "away a long time".
     */
    public long   lastSeen;
    public String firstIp;          // IP recorded on the very first login (set once, never overwritten)
    public long   totalPlaytimeSeconds;
    /**
     * Lifetime playtime frozen at the last season rollover (see SeasonMigration). Read-only here —
     * the mod never writes it outside the migration. Subtract it from {@link #totalPlaytimeSeconds}
     * to get playtime for the CURRENT season; 0 for anyone who first joined this season.
     */
    public long   season1PlaytimeSeconds;
    public long   sessionStartEpoch; // set on auth, cleared on leave
    public String skinUrl;           // base64 "textures" value; null = default skin. Offline skins are cape-stripped.
    public boolean capeEnabled;      // true = allowed a cape (premium only). Offline players never get capes.
    public int    skinChangesUsed;   // lifetime /skin <name> uses (offline players) — capped at MAX_SKIN_CHANGES.
    public boolean firstJoinComplete; // true after first-join sequence plays
    public boolean startupBonusGiven; // true after the one-time starter currency is granted on first /spawn
    public String  discordId;         // linked Discord user id (snowflake); null/blank = not linked

    // Transient: not serialized, computed on load
    public int     nameChangesUsed;  // lifetime counter — capped at 1; 0 = change still available

    // Room / approval system (offline players only)
    public boolean nameApproved;         // true = name permanently approved, skip room on next join
    public boolean nameApprovalPending;  // true = name submitted, awaiting admin decision
    public String  pendingDisplayName;   // proposed name while in approval queue
    public int     nameRejectionCount;   // rejections this account lifetime (not reset on reconnect)
    public int     roomSlot;             // assigned room slot index (-1 = unassigned)
    public long    roomCreatedAt;        // epoch ms when room was first built

    // Last position in the MAIN world (never the lobby) — restored on /spawn so a returning player
    // resumes where they logged off instead of being dumped at world spawn. null dim = never entered
    // the world yet → first /spawn goes to the world spawn point.
    public String  returnDim;            // dimension id, e.g. "minecraft:overworld"; null = none
    public double  returnX, returnY, returnZ;

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
        this.capeEnabled          = (type == AccountType.PREMIUM); // capes are premium-only
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
