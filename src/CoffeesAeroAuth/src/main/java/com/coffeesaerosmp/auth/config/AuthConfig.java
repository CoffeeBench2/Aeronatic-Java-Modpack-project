package com.coffeesaerosmp.auth.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class AuthConfig {

    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.IntValue     AUTH_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue     SESSION_GRACE_MINUTES;
    public static final ModConfigSpec.IntValue     LOBBY_BYPASS_MINUTES;
    public static final ModConfigSpec.IntValue     STARTUP_BONUS_SPURS;
    public static final ModConfigSpec.BooleanValue KICK_ON_NAME_CONFLICT;
    public static final ModConfigSpec.IntValue     MAX_FAILED_ATTEMPTS;
    public static final ModConfigSpec.BooleanValue BYPASS_AUTH_FOR_OPS;

    // ── Velocity proxy bridge ──────────────────────────────────────────────────
    public static final ModConfigSpec.ConfigValue<String>  VELOCITY_SHARED_SECRET;
    public static final ModConfigSpec.IntValue     TYPE_RESOLVE_TIMEOUT_SECONDS;
    public static final ModConfigSpec.BooleanValue TRUST_FORWARDED_UUID;

    // ── Name approval / private room ──────────────────────────────────────────
    public static final ModConfigSpec.IntValue     AUTO_APPROVE_MINUTES;
    public static final ModConfigSpec.ConfigValue<String>  BANNED_WORDS;

    // ── Shared public lobby (floating island near origin) ─────────────────────
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_X;
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_Y;
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_Z;
    public static final ModConfigSpec.IntValue      LOBBY_FLOOR_Y;
    public static final ModConfigSpec.IntValue      LOBBY_FALL_CATCH_DROP;
    public static final ModConfigSpec.IntValue      LOBBY_FORCELOAD_RADIUS_CHUNKS;
    public static final ModConfigSpec.BooleanValue  LOBBY_PREPLACED_BUILD;
    public static final ModConfigSpec.IntValue      OVERWORLD_SPAWN_X;
    public static final ModConfigSpec.IntValue      OVERWORLD_SPAWN_Y;
    public static final ModConfigSpec.IntValue      OVERWORLD_SPAWN_Z;
    public static final ModConfigSpec.IntValue      SPAWN_FORCELOAD_RADIUS_CHUNKS;

    public static final ModConfigSpec.ConfigValue<String>  RESOURCE_PACK_URL;
    public static final ModConfigSpec.ConfigValue<String>  RESOURCE_PACK_HASH;
    public static final ModConfigSpec.ConfigValue<String>  SERVER_DISPLAY_NAME;
    public static final ModConfigSpec.ConfigValue<String>  DISPLAY_RGB_NAMES;
    public static final ModConfigSpec.IntValue             WELCOME_INTERVAL_HOURS;
    public static final ModConfigSpec.BooleanValue         MASK_ADVANCEMENT_NAMES;

    // ── Watchdog ──────────────────────────────────────────────────────────────
    public static final ModConfigSpec.IntValue     LOGIN_STORM_FAILURES;
    public static final ModConfigSpec.IntValue     LOGIN_STORM_ACCOUNTS;
    public static final ModConfigSpec.IntValue     LOGIN_STORM_WINDOW_SECONDS;
    public static final ModConfigSpec.IntValue     LOGIN_STORM_BAN_MINUTES;
    public static final ModConfigSpec.IntValue     PRE_AUTH_PACKET_THRESHOLD;
    public static final ModConfigSpec.IntValue     PRE_AUTH_BAN_MINUTES;
    public static final ModConfigSpec.IntValue     CMD_VELOCITY_THROTTLE;
    public static final ModConfigSpec.IntValue     CMD_VELOCITY_ALERT;
    public static final ModConfigSpec.DoubleValue  MAX_MOVEMENT_SPEED;
    public static final ModConfigSpec.DoubleValue  MAX_UNAUTH_RATIO;
    public static final ModConfigSpec.IntValue     ADMIN_CMD_LIMIT;
    public static final ModConfigSpec.IntValue     ADMIN_CMD_WINDOW_SECONDS;
    public static final ModConfigSpec.IntValue     TRUSTED_IP_MAX_COUNT;
    public static final ModConfigSpec.ConfigValue<String>  QUIET_HOURS_START;
    public static final ModConfigSpec.ConfigValue<String>  QUIET_HOURS_END;

    // ── Rail protection ─────────────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue LOCK_END_DIMENSION;
    public static final ModConfigSpec.BooleanValue RAIL_AUTOCLAIM_ENABLED;
    public static final ModConfigSpec.IntValue     RAIL_AUTOCLAIM_RADIUS;
    public static final ModConfigSpec.BooleanValue RAIL_AUTOCLAIM_AUTOGRANT;
    public static final ModConfigSpec.IntValue     RAIL_AUTOCLAIM_MAX;
    public static final ModConfigSpec.ConfigValue<String>  RAIL_PROTECTED_BLOCKS;

    // ── PvP combat guard + /back ──────────────────────────────────────────────
    public static final ModConfigSpec.IntValue     COMBAT_TAG_SECONDS;
    public static final ModConfigSpec.IntValue     BACK_WINDOW_SECONDS;
    public static final ModConfigSpec.IntValue     BACK_COOLDOWN_SECONDS;
    public static final ModConfigSpec.BooleanValue COMBAT_LOG_PUNISH;

    // ── /tpa (our own — replaces FTB Essentials' selector-based one) ──────────
    public static final ModConfigSpec.IntValue     TPA_TIMEOUT_SECONDS;

    // ── /spawn (in-world world-spawn teleport) ────────────────────────────────
    public static final ModConfigSpec.IntValue     SPAWN_TP_COOLDOWN_MINUTES;
    public static final ModConfigSpec.IntValue     HOME_TP_COOLDOWN_MINUTES;

    // ── /daily streak reward ──────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue DAILY_REWARD_ENABLED;
    public static final ModConfigSpec.IntValue     DAILY_REWARD_INTERVAL_HOURS;

    // ── /rtp (our own — replaces FTB Essentials' sync-chunk-gen one) ──────────
    public static final ModConfigSpec.BooleanValue RTP_ENABLED;
    public static final ModConfigSpec.IntValue     RTP_COOLDOWN_MINUTES;
    public static final ModConfigSpec.IntValue     RTP_MIN_DISTANCE;
    public static final ModConfigSpec.IntValue     RTP_MAX_DISTANCE;
    public static final ModConfigSpec.IntValue     RTP_MIN_WAIT_SECONDS;
    public static final ModConfigSpec.IntValue     RTP_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue     RTP_PREGEN_RADIUS;

    // ── Gate reconnect grace ──────────────────────────────────────────────────
    public static final ModConfigSpec.IntValue     PREMIUM_RECONNECT_GRACE_MINUTES;

    // ── Discord ───────────────────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue DISCORD_ENABLED;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_BOT_TOKEN;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_WATCHDOG_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_PUBLIC_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_WEBHOOK_WATCHDOG;
    public static final ModConfigSpec.BooleanValue         DISCORD_PUBLIC_ACHIEVEMENTS;
    public static final ModConfigSpec.BooleanValue         DISCORD_PUBLIC_JOINLEAVE;
    public static final ModConfigSpec.BooleanValue         DISCORD_PUBLIC_CHAT;
    public static final ModConfigSpec.BooleanValue         DISCORD_PUBLIC_DEATHS;
    public static final ModConfigSpec.BooleanValue         DISCORD_BOT_PRESENCE;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_WEBHOOK_PUBLIC;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_DIGEST_TIME;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_TO_MC_ROLE_ID;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_MILESTONE_HOURS;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_ADMIN_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_ADMIN_ROLE_ID;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_SMP_LAUNCH_DATE;

    // ── Obsidian ──────────────────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue OBSIDIAN_ENABLED;
    public static final ModConfigSpec.ConfigValue<String>  OBSIDIAN_URL;
    /** Fallback if OBSIDIAN_API_KEY is absent from .env. Prefer .env — do not hardcode here. */
    public static final ModConfigSpec.ConfigValue<String>  OBSIDIAN_API_KEY;
    public static final ModConfigSpec.ConfigValue<String>  OBSIDIAN_VAULT_PATH;
    public static final ModConfigSpec.BooleanValue OBSIDIAN_SYNC_ON_STOP;
    public static final ModConfigSpec.BooleanValue OBSIDIAN_SYNC_PLAYER_UPDATES;
    public static final ModConfigSpec.BooleanValue OBSIDIAN_SYNC_WATCHDOG;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Authentication").push("auth");
        AUTH_TIMEOUT_SECONDS = b
            .comment("Seconds before an unauthenticated player is kicked. 0 = never kick.")
            .defineInRange("authTimeoutSeconds", 60, 0, 600);
        SESSION_GRACE_MINUTES = b
            .comment("Minutes after logout an offline player can reconnect (same IP) without re-logging in.",
                     "Return after this window requires /login again. Default 20.",
                     "SECURITY dial — keep this SHORT. It is deliberately separate from",
                     "lobbyBypassMinutes below, which only decides lobby routing.")
            .defineInRange("sessionGraceMinutes", 20, 0, 1440);
        LOBBY_BYPASS_MINUTES = b
            .comment("Minutes a returning player may skip the lobby and resume where they logged off.",
                     "Away for LESS than this -> dropped straight back at their logout position.",
                     "Away for this long or MORE -> routed through the shared lobby, and they leave",
                     "via /spawn (which restores the lobby stash and resumes their saved position).",
                     "Applies to PREMIUM players; offline players are additionally gated by",
                     "sessionGraceMinutes because they must re-type /login in the lobby.",
                     "0 = always route through the lobby, every join. Default 120 (2 hours).")
            .defineInRange("lobbyBypassMinutes", 120, 0, 10080);
        STARTUP_BONUS_SPURS = b
            .comment("Starter Numismatics currency (spurs) granted once on a player's first /spawn. 0 = disabled.")
            .defineInRange("startupBonusSpurs", 200, 0, 100000);
        KICK_ON_NAME_CONFLICT = b
            .comment("Kick offline players whose Minecraft username matches a verified player's display name.")
            .define("kickOnNameConflict", true);
        MAX_FAILED_ATTEMPTS = b
            .comment("Wrong password attempts allowed before kicking. 0 = unlimited.")
            .defineInRange("maxFailedAttempts", 5, 0, 20);
        BYPASS_AUTH_FOR_OPS = b
            .comment("Let server operators (level 4) skip auth — useful during initial setup only.")
            .define("bypassAuthForOps", false);
        VELOCITY_SHARED_SECRET = b
            .comment("Shared secret that AeroVelocity must echo in its aerosmp:player_type message (set AERO_FORWARDING_SECRET on the proxy to match).",
                     "If blank, premium/cracked signals are trusted without verification — LOCAL TESTING ONLY. Set this on any public server.")
            .define("velocityForwardingSecret", "");
        TYPE_RESOLVE_TIMEOUT_SECONDS = b
            .comment("Seconds to wait for the proxy's premium/cracked signal before assuming offline (e.g. a direct, non-proxied connection). 0 = wait forever.")
            .defineInRange("typeResolveTimeoutSeconds", 8, 0, 120);
        TRUST_FORWARDED_UUID = b
            .comment("Read premium/offline directly from the forwarded UUID version (v4=premium, v3=offline) instead of waiting for the aerosmp:player_type plugin message.",
                     "Enable ONLY once Velocity modern forwarding (NeoVelocity) is verified AND the backend game port is firewalled to the proxy IP — otherwise a direct connection could present a v4 UUID and skip auth.",
                     "false = use the plugin-message route (the proven fallback). This is the safe default until NeoVelocity is confirmed on the live stack.")
            .define("trustForwardedUuid", false);
        AUTO_APPROVE_MINUTES = b
            .comment("Minutes before an unreviewed display name is auto-approved. 0 = never auto-approve.")
            .defineInRange("autoApproveMinutes", 10, 0, 1440);
        BANNED_WORDS = b
            .comment("Comma-separated words that trigger auto-rejection of display names.")
            .define("bannedWords", "admin,moderator,staff,owner");
        b.pop();

        b.comment("Resource Pack").push("resourcepack");
        RESOURCE_PACK_URL = b
            .comment("Direct-download URL of the server resource pack. Leave empty to disable.")
            .define("url", "");
        RESOURCE_PACK_HASH = b
            .comment("SHA-1 hex hash of the resource pack file.")
            .define("hash", "");
        b.pop();

        b.comment("Display").push("display");
        SERVER_DISPLAY_NAME = b
            .comment("Server name shown in welcome messages and title screens.")
            .define("serverDisplayName", "Coffees Aero SMP");
        DISPLAY_RGB_NAMES = b
            .comment("Comma-separated usernames whose name renders as an animated RGB rainbow in chat + tab.",
                     "(A future /authmod namecolor command will manage this with more styles.)")
            .define("rgbNames", "MrCoffeeBench");
        WELCOME_INTERVAL_HOURS = b
            .comment("Hours between cosmetic welcome shows per player (title + welcome chat + skin tip).",
                     "Persisted in welcome_shown.json so relogs/restarts don't replay it. 0 = every join",
                     "(the old always-spam behaviour). First-join sequences always show regardless.")
            .defineInRange("welcomeIntervalHours", 10, 0, 720);
        b.pop();

        b.comment("Watchdog — Security Monitoring").push("watchdog");
        LOGIN_STORM_FAILURES       = b.comment("Failed logins from one subnet to trigger storm detection.")
            .defineInRange("loginStormFailures", 10, 3, 100);
        LOGIN_STORM_ACCOUNTS       = b.comment("Minimum distinct accounts involved to confirm a storm.")
            .defineInRange("loginStormAccounts", 3, 2, 20);
        LOGIN_STORM_WINDOW_SECONDS = b.comment("Time window (seconds) for login storm counting.")
            .defineInRange("loginStormWindowSeconds", 60, 10, 600);
        LOGIN_STORM_BAN_MINUTES    = b.comment("Subnet ban duration (minutes) after storm detection.")
            .defineInRange("loginStormBanMinutes", 5, 1, 1440);
        PRE_AUTH_PACKET_THRESHOLD  = b.comment("Blocked interactions per second before IP ban.")
            .defineInRange("preAuthPacketThreshold", 50, 10, 500);
        PRE_AUTH_BAN_MINUTES       = b.comment("IP ban duration (minutes) on pre-auth flood.")
            .defineInRange("preAuthBanMinutes", 5, 1, 60);
        CMD_VELOCITY_THROTTLE      = b.comment("Commands/second before silent throttle.")
            .defineInRange("commandVelocityThrottle", 5, 2, 50);
        CMD_VELOCITY_ALERT         = b.comment("Commands/second before alert + cancel.")
            .defineInRange("commandVelocityAlert", 20, 5, 200);
        MAX_MOVEMENT_SPEED         = b.comment("Max movement magnitude (blocks/tick) before flagging. Spectator/creative-fly/elytra/riptide/passengers are exempt since 1.6.19. Keep generous — Aeronautics airships move players FAST (existing configs: raise 2.0 -> 8.0 by hand).")
            .defineInRange("maxMovementSpeed", 8.0, 0.5, 40.0);
        MAX_UNAUTH_RATIO           = b.comment("Fraction of max slots that can be unauthenticated before blocking joins (0.0–1.0).")
            .defineInRange("maxUnauthRatio", 0.4, 0.1, 1.0);
        ADMIN_CMD_LIMIT            = b.comment("Max /authmod commands allowed in the window before locking.")
            .defineInRange("adminCommandLimit", 5, 2, 50);
        ADMIN_CMD_WINDOW_SECONDS   = b.comment("Rolling window (seconds) for admin command rate limiting.")
            .defineInRange("adminCommandWindowSeconds", 120, 30, 600);
        TRUSTED_IP_MAX_COUNT       = b.comment("Max trusted IPs stored per offline player UUID.")
            .defineInRange("trustedIpMaxCount", 3, 1, 10);
        QUIET_HOURS_START          = b.comment("Quiet hours start (HH:mm UTC). Admin commands trigger owner alert.")
            .define("quietHoursStart", "02:00");
        QUIET_HOURS_END            = b.comment("Quiet hours end (HH:mm UTC).")
            .define("quietHoursEnd", "08:00");
        b.pop();

        b.comment("Rail Protection — auto-claim chunks where players build Create rail (anti-grief).")
            .push("railprotection");
        RAIL_AUTOCLAIM_ENABLED = b
            .comment("When a player places a Create rail block, auto-claim that chunk to their FTB Chunks",
                     "team so others can't break/grief it. Requires FTB Chunks. No-op if it's absent.")
            .define("railAutoClaimEnabled", true);
        RAIL_AUTOCLAIM_RADIUS = b
            .comment("Extra chunk radius claimed around each placed rail block (0 = only that chunk,",
                     "1 = 3x3, 2 = 5x5). Bigger = protects more surrounding landscape/track per placement.")
            .defineInRange("railAutoClaimRadius", 0, 0, 4);
        RAIL_AUTOCLAIM_AUTOGRANT = b
            .comment("If a player is out of claim allowance, grant extra so rail is ALWAYS protected.",
                     "false = respect their normal claim limit (safer against land-grab via track spam).")
            .define("railAutoClaimAutoGrant", false);
        RAIL_AUTOCLAIM_MAX = b
            .comment("Hard cap on chunks auto-claimed per player via rail (only matters when autoGrant=true).")
            .defineInRange("railAutoClaimMaxChunks", 256, 16, 4096);
        RAIL_PROTECTED_BLOCKS = b
            .comment("Comma-separated block IDs that trigger rail auto-claim when placed.")
            .define("railProtectedBlocks",
                "create:track,create:fake_track,create:track_station,create:track_signal,"
                + "create:track_observer,create:content_observer,create:small_bogey,create:large_bogey,"
                + "create:metal_girder,create:metal_girder_encased_shaft");
        b.pop();

        b.comment("World Gates — dimension access control.").push("worldgates");
        LOCK_END_DIMENSION = b
            .comment("Block players from entering the End by ANY route (portals, waystones, /tpa, grave",
                     "recalls...). Ops (permission 2+) bypass. Hot-reloadable: flip to false and save to",
                     "open the End without a restart.")
            .define("lockEndDimension", true);
        b.pop();

        b.comment("Discord Integration").push("discord");
        DISCORD_ENABLED             = b.comment("Enable Discord integration (webhooks + bot).")
            .define("enabled", false);
        DISCORD_BOT_TOKEN           = b.comment("Discord bot token for Gateway (incoming messages). Leave empty to disable bot.")
            .define("botToken", "");
        DISCORD_WATCHDOG_CHANNEL_ID = b.comment("Channel ID for the watchdog/admin Discord channel.")
            .define("watchdogChannelId", "");
        DISCORD_PUBLIC_CHANNEL_ID   = b.comment("Channel ID for the public Discord chat bridge.")
            .define("publicChannelId", "");
        DISCORD_WEBHOOK_WATCHDOG    = b.comment("Webhook URL for the watchdog/admin channel: security alerts AND join/leave/achievement events.")
            .define("webhookWatchdog", "");
        DISCORD_PUBLIC_ACHIEVEMENTS = b.comment("Post achievement embeds to the PUBLIC webhook (display names only). false = watchdog channel.")
            .define("publicAchievements", true);
        DISCORD_PUBLIC_JOINLEAVE = b.comment("Also post joins/leaves to the PUBLIC webhook (display names only). Watchdog channel keeps its own copies either way.")
            .define("publicJoinLeave", true);
        DISCORD_PUBLIC_CHAT = b.comment("Mirror in-game player chat to the PUBLIC webhook. OFF by default (2026-07-12: achievements + joins/leaves are enough); Discord→MC direction is unaffected.")
            .define("publicChatMirror", false);
        DISCORD_PUBLIC_DEATHS = b.comment("Post player death messages to the PUBLIC webhook.")
            .define("publicDeaths", true);
        DISCORD_BOT_PRESENCE = b.comment("Set bot status to the live player count ('N pilots aboard'). The 1.6.8 4002 gateway loop is fixed in 1.6.10 (single-threaded sender + rate-limited presence); if 4002s ever recur, presence auto-disables for the run and logs the offending frame.")
            .define("botPresence", true);
        DISCORD_WEBHOOK_PUBLIC      = b.comment("Webhook URL for public events (chat bridge, deaths, playtime milestones). Join/leave/achievements moved to the watchdog channel.")
            .define("webhookPublic", "");
        DISCORD_DIGEST_TIME         = b.comment("Time (HH:mm UTC) to send the daily security digest.")
            .define("digestTime", "00:00");
        DISCORD_TO_MC_ROLE_ID       = b.comment("Discord role ID allowed to send messages to MC chat. Empty = everyone.")
            .define("discordToMcRoleId", "");
        DISCORD_MILESTONE_HOURS     = b.comment("Comma-separated playtime milestones (hours) for Discord announcements.")
            .define("milestoneHours", "1,5,10,50,100");
        DISCORD_ADMIN_CHANNEL_ID    = b.comment("Channel ID for the admin console bridge (console mirror + commands from Discord).")
            .define("adminConsoleChannelId", "");
        DISCORD_SMP_LAUNCH_DATE     = b.comment("ISO date (yyyy-MM-dd, UTC) the SMP went live on Apex — /uptime reports time since this date (restart-proof, NOT since-last-boot).")
            .define("smpLaunchDate", "2026-06-27");
        DISCORD_ADMIN_ROLE_ID       = b.comment("Role ID allowed to use moderation buttons (approve/reject) and run console commands from Discord. Blank = no gating (LOCAL TESTING ONLY).")
            .define("adminRoleId", "");
        b.pop();

        b.comment("Obsidian Integration — requires the Obsidian Local REST API community plugin").push("obsidian");
        OBSIDIAN_ENABLED       = b.comment("Enable Obsidian vault sync.")
            .define("enabled", false);
        OBSIDIAN_URL           = b.comment("Base URL of the Obsidian Local REST API. Overridden by OBSIDIAN_URL in .env.")
            .define("url", "http://localhost:27123");
        OBSIDIAN_API_KEY       = b.comment("API key fallback — prefer setting OBSIDIAN_API_KEY in .env instead of here.")
            .define("apiKey", "");
        OBSIDIAN_VAULT_PATH    = b.comment("Root folder inside your Obsidian vault to write server notes into.")
            .define("vaultPath", "AeroSMP");
        OBSIDIAN_SYNC_ON_STOP  = b.comment("Write devlog and full session file when the server stops.")
            .define("syncOnStop", true);
        OBSIDIAN_SYNC_PLAYER_UPDATES = b.comment("Update player .md files on login, logout, and achievements.")
            .define("syncPlayerUpdates", true);
        OBSIDIAN_SYNC_WATCHDOG = b.comment("Append watchdog alert lines to the monthly alerts file.")
            .define("syncWatchdogAlerts", true);
        b.pop();

        b.comment("PvP combat guard + /back death-return").push("combat");
        COMBAT_TAG_SECONDS    = b.comment("Seconds a player stays combat-tagged after PvP damage (refreshed per hit).")
            .defineInRange("combatTagSeconds", 30, 5, 600);
        BACK_WINDOW_SECONDS   = b.comment("/back only works within this many seconds of dying.")
            .defineInRange("backWindowSeconds", 300, 30, 3600);
        BACK_COOLDOWN_SECONDS = b.comment("After a successful /back, it locks for this many seconds.")
            .defineInRange("backCooldownSeconds", 600, 0, 86400);
        COMBAT_LOG_PUNISH     = b.comment("Kill players who disconnect while combat-tagged (items drop at the fight).")
            .define("combatLogPunish", true);
        b.pop();

        b.comment("Our own /tpa — replaces FTB Essentials' (disabled via ftbessentials.snbt)").push("tpa");
        TPA_TIMEOUT_SECONDS = b.comment("A /tpa request expires if not accepted/denied within this many seconds.")
            .defineInRange("tpaTimeoutSeconds", 60, 10, 600);
        b.pop();

        b.push("spawn");
        SPAWN_TP_COOLDOWN_MINUTES = b.comment(
                "Minutes between in-world /spawn teleports per player. Ops are exempt, and CombatGuard",
                "already blocks /spawn while combat-tagged. In-memory (resets on server restart). 0 = no cooldown.")
            .defineInRange("spawnTeleportCooldownMinutes", 180, 0, 1440);
        b.pop();

        b.push("dailyReward");
        DAILY_REWARD_ENABLED = b.comment("Enable the /daily streak reward (items + XP; no currency, so it never touches the economy).")
            .define("dailyRewardEnabled", true);
        DAILY_REWARD_INTERVAL_HOURS = b.comment(
                "Hours a player must wait between /daily claims. The streak breaks if they wait longer",
                "than 2x this value. Default 20 lets someone claim at roughly the same time each day.")
            .defineInRange("dailyRewardIntervalHours", 20, 1, 168);
        b.pop();

        b.comment("Our own /rtp — replaces FTB Essentials' (disabled via ftbessentials.snbt), whose",
                  "synchronous destination chunk-gen froze the server 20-40s per use on this worldgen stack.")
            .push("rtp");
        RTP_ENABLED = b.comment("Enable /rtp.")
            .define("rtpEnabled", true);
        RTP_COOLDOWN_MINUTES = b.comment("Minutes between /rtp uses per player (persisted across restarts/relogs). Ops exempt. 0 = no cooldown.",
                "(Was rtpCooldownHours=24 in 1.6.26-28; user set 15 min on 2026-07-18 — the async ring pregen made rtp cheap enough.)")
            .defineInRange("rtpCooldownMinutes", 15, 0, 43200);
        RTP_MIN_DISTANCE = b.comment("Minimum distance (blocks) from world spawn for the random target.")
            .defineInRange("rtpMinDistance", 1500, 100, 1_000_000);
        RTP_MAX_DISTANCE = b.comment("Maximum distance (blocks) from world spawn. Keep modest — greater distance = more ungenerated terrain to build. (FTBE's old 25000 was the freeze.)")
            .defineInRange("rtpMaxDistance", 10000, 500, 1_000_000);
        RTP_MIN_WAIT_SECONDS = b.comment("Minimum seconds the player waits (watching the progress bar) even if chunks finish early.")
            .defineInRange("rtpMinWaitSeconds", 10, 0, 120);
        RTP_TIMEOUT_SECONDS = b.comment("Abort (and refund the cooldown) if the destination isn't generated within this many seconds.",
                "MEASURED live 2026-07-18 (no c2me): ~3.4s per virgin chunk -> 225 chunks can need 10+ minutes.",
                "EDIT THE LIVE TOML: a pre-existing file keeps its old value (90 was far too short).")
            .defineInRange("rtpTimeoutSeconds", 600, 20, 1200);
        RTP_PREGEN_RADIUS = b.comment(
                "Chunk radius fully generated BEFORE the teleport (7 = 15x15 chunks). Must roughly cover the",
                "server view distance: arriving inside a small generated island made the surrounding view-distance",
                "worldgen burst stall the main thread via c2me sync-loads (2026-07-17: 36s freeze AFTER a 5x5-only",
                "pregen teleport). Bigger = longer /rtp wait but a smooth arrival.")
            .defineInRange("rtpPregenRadius", 7, 2, 16);
        b.pop();

        b.comment("Transfer-gate reconnect grace").push("gate");
        PREMIUM_RECONNECT_GRACE_MINUTES = b.comment(
                "Minutes a gate-verified PREMIUM player may reconnect DIRECTLY (spent/missing cookie) from the",
                "SAME IP and still resolve premium. Covers launcher auto-reconnects after kicks/freezes, where",
                "the single-use cookie is already consumed and the player was being demoted to the offline flow.",
                "0 = disabled (old behaviour: any rejected cookie resolves OFFLINE).")
            .defineInRange("premiumReconnectGraceMinutes", 10, 0, 1440);
        b.pop();

        b.comment("Shared public login lobby (single forceloaded floating island near origin).")
         .push("sharedLobby");
        LOBBY_SPAWN_X = b
            .comment("Lobby spawn pad X (players teleport here; frozen players ring around it).")
            .defineInRange("lobbySpawnX", 7.5, -30000000.0, 30000000.0);
        LOBBY_SPAWN_Y = b
            .comment("Lobby spawn pad Y.")
            .defineInRange("lobbySpawnY", 101.0, -64.0, 320.0);
        LOBBY_SPAWN_Z = b
            .comment("Lobby spawn pad Z.")
            .defineInRange("lobbySpawnZ", 5.5, -30000000.0, 30000000.0);
        LOBBY_FLOOR_Y = b
            .comment("Reference island floor Y for the fall-catch.")
            .defineInRange("lobbyFloorY", 100, -64, 320);
        LOBBY_FALL_CATCH_DROP = b
            .comment("Blocks below the floor before a player who fell off the island is returned to spawn.")
            .defineInRange("lobbyFallCatchDrop", 15, 3, 200);
        LOBBY_FORCELOAD_RADIUS_CHUNKS = b
            .comment("Chunks (square radius) around the lobby anchor to permanently force-load. Cover the whole build.")
            .defineInRange("lobbyForceloadRadiusChunks", 8, 1, 32);
        LOBBY_PREPLACED_BUILD = b
            .comment("The lobby build is pre-placed in the auth_lobby dimension (e.g. a map dropped into its",
                     "region folder) rather than a bundled template. When true, the mod places NO template or",
                     "platform and never touches the map — it only force-loads the region and spawns players in.")
            .define("lobbyPreplacedBuild", true);
        OVERWORLD_SPAWN_X = b
            .comment("Overworld spawn X — where /spawn and the lobby exit paper send players.")
            .defineInRange("overworldSpawnX", 0, -30000000, 30000000);
        OVERWORLD_SPAWN_Y = b
            .comment("Overworld spawn Y.")
            .defineInRange("overworldSpawnY", 112, -64, 320);
        OVERWORLD_SPAWN_Z = b
            .comment("Overworld spawn Z.")
            .defineInRange("overworldSpawnZ", -1, -30000000, 30000000);
        SPAWN_FORCELOAD_RADIUS_CHUNKS = b
            .comment("Chunks (square radius) around the overworld spawn to permanently force-load so joins/spawns are instant.")
            .defineInRange("spawnForceloadRadiusChunks", 7, 0, 16);
        b.pop();

        b.push("home");
        HOME_TP_COOLDOWN_MINUTES = b
            .comment("Cooldown in minutes for /home (teleport to your bed/respawn point). 0 = no cooldown. Ops exempt.")
            .defineInRange("homeTeleportCooldownMinutes", 5, 0, 1440);
        b.pop();

        b.push("advancements");
        MASK_ADVANCEMENT_NAMES = b
            .comment("Announce advancements with the player's display name instead of their account username.",
                     "When true, vanilla's announceAdvancements chat broadcast is disabled at startup and replaced",
                     "by a display-name version. The earner's own toast popup is unaffected.")
            .define("maskAdvancementNames", true);
        b.pop();

        SERVER_SPEC = b.build();
    }
}
