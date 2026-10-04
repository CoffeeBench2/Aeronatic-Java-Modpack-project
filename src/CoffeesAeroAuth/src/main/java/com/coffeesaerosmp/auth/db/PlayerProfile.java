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

    // Last position in the MAIN world (never the lobby) — restored on /spawn so a returning player
    // resumes where they logged off instead of being dumped at world spawn. null dim = never entered
    // the world yet → first /spawn goes to the world spawn point.
    public String  returnDim;            // dimension id, e.g. "minecraft:overworld"; null = none
    public double  returnX, returnY, returnZ;

    public transient UUID uuid;

    // Identity — READ-ONLY copies of players.mojang_uuid / link_source / identity_hold, loaded with the
    // row so the join gate (admin/IdentityGate) needs no extra query. 🔴 Transient and deliberately
    // absent from upsertPlayer: save() writes the whole row from a cached copy, and a stale cached
    // link written back would undo a bind, or erase a hold, made by the other process. Only the
    // guarded statements in AccountTransfer / IdentityCommands ever write these columns.
    public transient String mojangLink;
    public transient String linkSource;
    public transient String identityHold;
    /**
     * players.season_start_playtime — total_playtime frozen at this season's rollover (S3 cutover SQL sets
     * it). READ-ONLY here for the same reason as the identity fields: save() must never write a stale
     * cached copy over the value the rollover stamped. Season playtime = playtime − this.
     */
    public transient long seasonStartPlaytime;

    public PlayerProfile() {
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
    }

    public UUID getUUID() {
        if (uuid == null && uuidStr != null) uuid = UUID.fromString(uuidStr);
        return uuid;
    }

    public AccountType getAccountType() {
        return AccountType.valueOf(accountType);
    }

    /**
     * Whether this account is Mojang-verified — <b>the authoritative premium test</b>.
     *
     * <h3>🔴 Do not test the uuid version instead</h3>
     * There used to be a {@code UUIDUtil.isPremiumUUID(uuid)} that returned {@code uuid.version() == 4}.
     * It could never be true on this server: the backend is {@code online-mode=false} and the client
     * connects to it directly, so every uuid — premium included — is the v3
     * {@code md5("OfflinePlayer:" + name)}. All 403 rows are v3. Every caller of it was silently taking
     * the offline branch, and the one in {@code PlayerAuthEvents} meant a documented exemption had never
     * applied once. It was deleted in favour of this.
     *
     * <p>Null-safe and tolerant of a bad stored value, unlike {@link #getAccountType()}, which throws
     * from {@code valueOf}. This is read on the join path and while rendering names, so it must not be
     * able to fail on one malformed row.
     */
    public boolean isPremium() {
        return accountType != null && AccountType.PREMIUM.name().equalsIgnoreCase(accountType.trim());
    }

    public enum AccountType {
        PREMIUM, OFFLINE
    }
}
