package com.coffeesaerosmp.auth.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class AuthConfig {

    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.IntValue     AUTH_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue     SESSION_GRACE_MINUTES;
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

    public static final ModConfigSpec.ConfigValue<String>  RESOURCE_PACK_URL;
    public static final ModConfigSpec.ConfigValue<String>  RESOURCE_PACK_HASH;
    public static final ModConfigSpec.ConfigValue<String>  SERVER_DISPLAY_NAME;

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

    // ── Discord ───────────────────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue DISCORD_ENABLED;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_BOT_TOKEN;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_WATCHDOG_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_PUBLIC_CHANNEL_ID;
    public static final ModConfigSpec.ConfigValue<String>  DISCORD_WEBHOOK_WATCHDOG;
    public static final ModConfigSpec.BooleanValue         DISCORD_PUBLIC_ACHIEVEMENTS;
    public static final ModConfigSpec.BooleanValue         DISCORD_PUBLIC_JOINLEAVE;
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
                     "Return after this window requires /login again. Default 20.")
            .defineInRange("sessionGraceMinutes", 20, 0, 1440);
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
        MAX_MOVEMENT_SPEED         = b.comment("Max movement magnitude (blocks/tick) before flagging.")
            .defineInRange("maxMovementSpeed", 2.0, 0.5, 20.0);
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

        SERVER_SPEC = b.build();
    }
}
