package com.coffeesaerosmp.auth.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class AuthConfig {

    public static final ModConfigSpec SERVER_SPEC;

    public static final ModConfigSpec.ConfigValue<String> DISCORD_INVITE_URL;
    public static final ModConfigSpec.BooleanValue        STORE_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> STORE_URL;
    public static final ModConfigSpec.ConfigValue<String> STORE_SUPPORT_EMAIL;
    public static final ModConfigSpec.BooleanValue BROADCAST_NEW_PLAYERS;
    public static final ModConfigSpec.BooleanValue LAG_WARN_ENABLED;
    public static final ModConfigSpec.IntValue     LAG_WARN_MSPT;
    public static final ModConfigSpec.IntValue     LAG_WARN_SUSTAIN_SECONDS;
    public static final ModConfigSpec.IntValue     LAG_WARN_COOLDOWN_SECONDS;
    public static final ModConfigSpec.BooleanValue SOUND_FEEDBACK;
    public static final ModConfigSpec.BooleanValue SAVEGUARD_ENABLED;
    public static final ModConfigSpec.IntValue     SAVEGUARD_PLAYER_SECONDS;
    public static final ModConfigSpec.IntValue     SAVEGUARD_WORLD_SECONDS;
    public static final ModConfigSpec.BooleanValue SAVEGUARD_SKIP_WHEN_EMPTY;
    public static final ModConfigSpec.IntValue     SAVEGUARD_SLOW_WARN_MS;

    public static final ModConfigSpec.IntValue     AUTH_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue     SESSION_GRACE_MINUTES;
    public static final ModConfigSpec.IntValue     LOBBY_BYPASS_MINUTES;
    public static final ModConfigSpec.IntValue     STARTUP_BONUS_SPURS;
    public static final ModConfigSpec.BooleanValue SEASON_CLEAR_WORLD_DATA;
    public static final ModConfigSpec.BooleanValue SEASON_GRANT_REWARDS;
    public static final ModConfigSpec.IntValue     ADMIN_LOCK_MINUTES;
    public static final ModConfigSpec.ConfigValue<String> ADMIN_RATE_LIMIT_EXEMPT;
    public static final ModConfigSpec.BooleanValue VOTE_REWARD_ENABLED;
    public static final ModConfigSpec.IntValue     VOTE_REWARD_SPURS_BASE;
    public static final ModConfigSpec.IntValue     VOTE_REWARD_SPURS_MAX;
    public static final ModConfigSpec.IntValue     VOTE_REWARD_DIAMONDS_BASE;
    public static final ModConfigSpec.IntValue     VOTE_REWARD_DIAMONDS_MAX;
    public static final ModConfigSpec.IntValue     VOTE_REWARD_VOTES_PER_STEP;
    public static final ModConfigSpec.ConfigValue<String> VOTE_URL;
    public static final ModConfigSpec.IntValue     VOTE_COOLDOWN_HOURS;
    public static final ModConfigSpec.BooleanValue VOTE_REMINDER_ENABLED;
    public static final ModConfigSpec.BooleanValue VOTE_ANNOUNCE_ENABLED;
    public static final ModConfigSpec.IntValue     VOTE_REWARD_MAX_REWARDED;
    public static final ModConfigSpec.BooleanValue KICK_ON_NAME_CONFLICT;
    public static final ModConfigSpec.BooleanValue IDENTITY_GATE_ENFORCE;
    public static final ModConfigSpec.BooleanValue PREMIUM_KEEPS_MOJANG_UUID;
    public static final ModConfigSpec.IntValue     MAX_FAILED_ATTEMPTS;
    public static final ModConfigSpec.BooleanValue BYPASS_AUTH_FOR_OPS;

    // ── Velocity proxy bridge ──────────────────────────────────────────────────
    public static final ModConfigSpec.ConfigValue<String>  VELOCITY_SHARED_SECRET;
    public static final ModConfigSpec.IntValue     TYPE_RESOLVE_TIMEOUT_SECONDS;
    public static final ModConfigSpec.BooleanValue TRUST_FORWARDED_UUID;

    // ── Name approval / login lobby ───────────────────────────────────────────
    public static final ModConfigSpec.IntValue     AUTO_APPROVE_MINUTES;
    public static final ModConfigSpec.ConfigValue<String>  BANNED_WORDS;

    // ── Shared public lobby (floating island near origin) ─────────────────────
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_X;
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_Y;
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_Z;
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_YAW;
    public static final ModConfigSpec.DoubleValue  LOBBY_SPAWN_PITCH;
    public static final ModConfigSpec.IntValue      LOBBY_FLOOR_Y;
    public static final ModConfigSpec.IntValue      LOBBY_FALL_CATCH_DROP;
    public static final ModConfigSpec.IntValue      LOBBY_FORCELOAD_RADIUS_CHUNKS;
    public static final ModConfigSpec.BooleanValue  LOBBY_PREPLACED_BUILD;
    public static final ModConfigSpec.ConfigValue<String> LOBBY_ALLOWED_COMMANDS;
    public static final ModConfigSpec.IntValue      OVERWORLD_SPAWN_X;
    public static final ModConfigSpec.IntValue      OVERWORLD_SPAWN_Y;
    public static final ModConfigSpec.IntValue      OVERWORLD_SPAWN_Z;
    public static final ModConfigSpec.DoubleValue   OVERWORLD_SPAWN_YAW;
    public static final ModConfigSpec.IntValue      SPAWN_FORCELOAD_RADIUS_CHUNKS;

    public static final ModConfigSpec.ConfigValue<String>  RESOURCE_PACK_URL;
    public static final ModConfigSpec.ConfigValue<String>  RESOURCE_PACK_HASH;
    public static final ModConfigSpec.ConfigValue<String>  SERVER_DISPLAY_NAME;
    public static final ModConfigSpec.ConfigValue<String>  DISPLAY_RGB_NAMES;
    public static final ModConfigSpec.ConfigValue<String>  STAFF_OWNER;
    public static final ModConfigSpec.ConfigValue<String>  STAFF_ADMIN;
    public static final ModConfigSpec.ConfigValue<String>  STAFF_MOD;
    public static final ModConfigSpec.BooleanValue         TPS_HUD_ENABLED;
    public static final ModConfigSpec.BooleanValue         ITEM_CLEAR_ENABLED;
    public static final ModConfigSpec.IntValue             ITEM_CLEAR_INTERVAL_MINUTES;
    public static final ModConfigSpec.ConfigValue<String>  ITEM_CLEAR_WARN_SECONDS;
    public static final ModConfigSpec.BooleanValue         ITEM_CLEAR_KEEP_NAMED;
    public static final ModConfigSpec.BooleanValue         SIDEBAR_ENABLED;
    public static final ModConfigSpec.BooleanValue         AFK_ENABLED;
    public static final ModConfigSpec.IntValue             AFK_TIMEOUT_MINUTES;
    public static final ModConfigSpec.BooleanValue         AFK_ANNOUNCE;
    public static final ModConfigSpec.BooleanValue         AFK_SEND_TO_LOBBY;
    public static final ModConfigSpec.BooleanValue         AFK_KICK_ENABLED;
    public static final ModConfigSpec.IntValue             AFK_KICK_BAN_MINUTES;
    public static final ModConfigSpec.BooleanValue         AFK_KICK_EXEMPT_OPS;
    public static final ModConfigSpec.BooleanValue         AFK_MACRO_DETECTION;
    public static final ModConfigSpec.IntValue             AFK_WARN_SECONDS;
    public static final ModConfigSpec.BooleanValue         RPM_CAP_ENABLED;
    public static final ModConfigSpec.IntValue             RPM_CAP_WORLD;
    public static final ModConfigSpec.IntValue             RPM_CAP_SUBLEVEL;
    public static final ModConfigSpec.BooleanValue         RPM_CAP_DESTROYS;
    public static final ModConfigSpec.BooleanValue         CHAT_FILTER_ENABLED;
    public static final ModConfigSpec.BooleanValue         WATCHDOG_TAUNTS_ENABLED;
    public static final ModConfigSpec.IntValue             WATCHDOG_TAUNT_MIN_MINUTES;
    public static final ModConfigSpec.IntValue             WATCHDOG_TAUNT_MAX_MINUTES;
    public static final ModConfigSpec.BooleanValue         STALL_WATCHDOG_ENABLED;
    public static final ModConfigSpec.IntValue             STALL_WARN_SECONDS;
    public static final ModConfigSpec.IntValue             STALL_KILL_SECONDS;
    public static final ModConfigSpec.IntValue             STALL_SHUTDOWN_KILL_SECONDS;
    public static final ModConfigSpec.BooleanValue         LAG_ATTRIBUTION_ENABLED;
    public static final ModConfigSpec.IntValue             LAG_SAMPLE_AFTER_MS;
    public static final ModConfigSpec.IntValue             LAG_SAMPLE_INTERVAL_MS;
    public static final ModConfigSpec.IntValue             LAG_SPIKE_REPORT_MS;
    public static final ModConfigSpec.IntValue             LAG_REPORT_COOLDOWN_MINUTES;
    public static final ModConfigSpec.IntValue             LAG_CENSUS_MAX_ENTITIES;
    public static final ModConfigSpec.BooleanValue         RESOLVE_DISPLAY_NAMES;
    public static final ModConfigSpec.IntValue             WELCOME_INTERVAL_HOURS;
    public static final ModConfigSpec.BooleanValue         MASK_ADVANCEMENT_NAMES;
    public static final ModConfigSpec.BooleanValue         SKIP_RECIPE_ADVANCEMENT_LISTENERS;

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

    // ── Confiscation (moderation freeze) ──────────────────────────────────────
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> CONFISCATE_ALLOWED_COMMANDS;

    // ── Season 3 leveling + mail ───────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue LEVELING_ENABLED;
    public static final ModConfigSpec.IntValue     LEVEL_XP_PER_ADVANCEMENT;
    public static final ModConfigSpec.IntValue     LEVEL_XP_PER_HOUR;
    public static final ModConfigSpec.IntValue     LEVEL_CURVE;
    public static final ModConfigSpec.BooleanValue LEVEL_CLAIMS_ENABLED;
    public static final ModConfigSpec.IntValue     CLAIMS_BASE;
    public static final ModConfigSpec.IntValue     CLAIMS_PER_LEVEL;
    public static final ModConfigSpec.IntValue     CLAIMS_MAX;
    public static final ModConfigSpec.IntValue     LEVEL_MILESTONE_EVERY;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> LEVEL_MILESTONE_ITEMS;
    public static final ModConfigSpec.BooleanValue OPS_GET_ALL_ADVANCEMENTS;
    public static final ModConfigSpec.BooleanValue MAIL_ENABLED;
    public static final ModConfigSpec.IntValue     MAIL_EXPIRY_DAYS;
    public static final ModConfigSpec.BooleanValue SEASON_WELCOME_MAIL;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> SEASON_WELCOME_ITEMS;
    public static final ModConfigSpec.BooleanValue LAUNCH_RESET;
    public static final ModConfigSpec.BooleanValue SURVIVAL_TELEPORT_COMMANDS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> LAUNCH_RESET_ACCOUNTS;
    public static final ModConfigSpec.BooleanValue LAUNCH_RESET_DEOP;

    public static final ModConfigSpec.BooleanValue EXPLOIT_DETECT_ENABLED;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXPLOIT_RULES;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXPLOIT_FLAGGED_BLOCKS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> EXPLOIT_ASSEMBLER_BLOCKS;
    public static final ModConfigSpec.IntValue     EXPLOIT_SCAN_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue     EXPLOIT_ALERT_COOLDOWN_SECONDS;

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
    public static final ModConfigSpec.IntValue     RTP_ANCHOR_COUNT;
    public static final ModConfigSpec.IntValue     RTP_ANCHOR_MAX_DISTANCE;
    public static final ModConfigSpec.IntValue     RTP_ANCHOR_SPREAD;
    public static final ModConfigSpec.IntValue     RTP_ANCHOR_WARM_MAX_PLAYERS;
    public static final ModConfigSpec.IntValue     RTP_ANCHOR_WARM_RING_STEP;
    public static final ModConfigSpec.IntValue     RTP_ANCHOR_WARM_MARGIN;

    public static final ModConfigSpec.BooleanValue MOVEMENT_ALERT_DISCORD;

    // ── /shipname ─────────────────────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue SHIPNAME_ENABLED;
    public static final ModConfigSpec.IntValue     SHIPNAME_MAX_LENGTH;

    // ── Plot-space guard (Sable sub-level coords / ChunkMap crash) ────────────
    public static final ModConfigSpec.BooleanValue CLEAR_STUCK_INVULNERABLE;

    // ── Sable sub-level (ship) speed ceiling ──────────────────────────────────

    // ── Standalone-lobby split (server role + lobby → SMP handoff) ────────────
    public static final ModConfigSpec.ConfigValue<String> SERVER_ROLE;
    public static final ModConfigSpec.BooleanValue HANDOFF_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> HANDOFF_HOST;
    public static final ModConfigSpec.IntValue     HANDOFF_PORT;
    public static final ModConfigSpec.IntValue     HANDOFF_COOKIE_TTL_SECONDS;
    public static final ModConfigSpec.ConfigValue<String> LOBBY_RETURN_HOST;
    public static final ModConfigSpec.IntValue     LOBBY_RETURN_PORT;
    public static final ModConfigSpec.BooleanValue REQUIRE_LOBBY_ENTRY;
    public static final ModConfigSpec.ConfigValue<String> LOBBY_ENTRY_ADDRESS;
    public static final ModConfigSpec.BooleanValue SMP_LIVENESS_ENABLED;
    public static final ModConfigSpec.IntValue     SMP_LIVENESS_POLL_SECONDS;
    public static final ModConfigSpec.IntValue     SMP_LIVENESS_CONFIRM;
    public static final ModConfigSpec.IntValue     SMP_LIVENESS_TIMEOUT_MS;
    public static final ModConfigSpec.BooleanValue AUTO_READMIT;
    public static final ModConfigSpec.IntValue     READMIT_STAGGER_MS;
    public static final ModConfigSpec.BooleanValue GRACEFUL_RESTART_ENABLED;
    public static final ModConfigSpec.IntValue     EVACUATE_SECONDS_BEFORE;

    // ── Daily host restart warning ────────────────────────────────────────────
    public static final ModConfigSpec.BooleanValue DAILY_RESTART_WARN_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> DAILY_RESTART_TIME;
    public static final ModConfigSpec.ConfigValue<String> DAILY_RESTART_TIMEZONE;
    public static final ModConfigSpec.IntValue     DAILY_RESTART_WARN_MINUTES;

    public static final ModConfigSpec.BooleanValue PLOTGUARD_ENABLED;
    public static final ModConfigSpec.BooleanValue PLOTGUARD_RESCUE;
    public static final ModConfigSpec.IntValue     PLOTGUARD_LIMIT;
    public static final ModConfigSpec.BooleanValue PLOTGUARD_BLOCK_TELEPORTS;

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
        SEASON_CLEAR_WORLD_DATA = b
            .comment("WORLD RESET pass. On boot, wipes data that describes the OLD world: logout return",
                     "positions (which would teleport a returning player into brand-new terrain) and",
                     "lobby room slots. Also freezes each player's Season 1 playtime and flags veterans.",
                     "Safe and idempotent -- it runs once per season, stamped in the 'season' column.",
                     "Safe to leave TRUE on a test server: it grants nothing, it only clears stale data.")
            .define("seasonClearWorldData", true);
        SEASON_GRANT_REWARDS = b
            .comment("REWARD pass. Re-arms the startupBonusSpurs grant for returning players and enables",
                     "the one-time Season 1 veteran reward.",
                     "",
                     "*** LEAVE THIS FALSE ON A TEST OR CREATIVE SERVER. ***",
                     "The player database is SHARED between servers. If a test server runs this pass, the",
                     "first veteran who joins it collects their reward there and is marked as paid -- so",
                     "they would receive NOTHING on the real season launch. Set it TRUE only on the live",
                     "server, and only when the real season world is the one players are joining.",
                     "It is stamped separately from seasonClearWorldData, so enabling it later still works.")
            .define("seasonGrantRewards", false);
        KICK_ON_NAME_CONFLICT = b
            .comment("Kick offline players whose Minecraft username matches a verified player's display name.")
            .define("kickOnNameConflict", true);
        IDENTITY_GATE_ENFORCE = b
            .comment("Refuse logins that would take over someone else's profile (admin/IdentityGate):",
                     "a different Mojang account on a linked profile, a profile on an identity hold, and a",
                     "premium login on an offline profile that has not proven the old password.",
                     "false = ALERT ONLY: every verdict is still logged and sent to Discord, but the login",
                     "proceeds exactly as it did before 1.11.4. This is the rollback switch - no redeploy.")
            .define("identityGateEnforce", true);
        PREMIUM_KEEPS_MOJANG_UUID = b
            .comment("Premium players keep their real Mojang UUID on this offline-mode server (auth/LoginIdentity).",
                     "The gate cookie is read in the LOGIN phase, so the GameProfile is built with the Mojang",
                     "UUID before any world data loads. Offline players keep md5(\"OfflinePlayer:\"+name).",
                     "🔴 Changes the key of every premium player's world data. Turn on ONLY on a world that is",
                     "already Mojang-keyed (Season 3) and on the lobby that feeds it, together, after the DB",
                     "re-key (/aeroid rekeypremium). NEVER on the Season 2 world. Needs a restart.")
            .define("premiumKeepsMojangUuid", false);
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
            .defineInRange("typeResolveTimeoutSeconds", 25, 0, 120);
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
        STAFF_OWNER = b
            .comment("Comma-separated usernames shown with the [OWNER] badge.",
                     "Rank is config-driven, NOT op level, so a moderator can be badged without",
                     "being given command powers - and an op can go unbadged. Re-read live.")
            .define("staffOwner", "MrCoffeeBench");
        STAFF_ADMIN = b
            .comment("Comma-separated usernames shown with the [ADMIN] badge.")
            .define("staffAdmin", "");
        STAFF_MOD = b
            .comment("Comma-separated usernames shown with the [MOD] badge.")
            .define("staffMod", "");
        TPS_HUD_ENABLED = b
            .comment("Show ops a live TPS/MSPT boss bar at the top of the screen. No command needed:",
                     "ops see it automatically and can turn it off for themselves with /authmod hud.",
                     "MSPT here is real tick WORK time (Pre->Post), so it is comparable to spark.",
                     "The interval between ticks - what the older LagMonitor measures - floors at",
                     "~50ms whenever the server keeps up, and so can never match spark's MSPT.")
            .define("tpsHudEnabled", true);
        ITEM_CLEAR_ENABLED = b
            .comment("Periodically clear items lying loose on the ground, with a countdown warning.",
                     "ONLY dropped ItemEntities are touched. Create belts, chutes, depots, funnels and",
                     "vaults keep their contents in BLOCK ENTITIES, so a clear cannot eat a running",
                     "factory; chests, backpacks and player inventories are likewise untouched.")
            .define("itemClearEnabled", true);
        ITEM_CLEAR_INTERVAL_MINUTES = b
            .comment("Minutes between clears.")
            .defineInRange("itemClearIntervalMinutes", 30, 1, 1440);
        ITEM_CLEAR_WARN_SECONDS = b
            .comment("Seconds-before-clear at which to warn, comma separated, any order.",
                     "Warnings fire on CROSSING a threshold, not on landing exactly on it, so a",
                     "lagging tick loop that skips seconds still announces every step.")
            .define("itemClearWarnSeconds", "300,120,60,30");
        ITEM_CLEAR_KEEP_NAMED = b
            .comment("Skip items with a custom name. A renamed item is nearly always a deliberate",
                     "keepsake rather than litter, so this defaults ON.")
            .define("itemClearKeepNamed", true);
        WELCOME_INTERVAL_HOURS = b
            .comment("Hours between cosmetic welcome shows per player (title + welcome chat + skin tip).",
                     "Persisted in welcome_shown.json so relogs/restarts don't replay it. 0 = every join",
                     "(the old always-spam behaviour). First-join sequences always show regardless.")
            .defineInRange("welcomeIntervalHours", 10, 0, 720);
        RESOLVE_DISPLAY_NAMES = b
            .comment("Let name-based commands accept a player's /setname DISPLAY name.",
                     "WHY: GameProfileArgument (what FTB Teams party invites, /ban and /whitelist use)",
                     "resolves names through the GameProfileCache only — never the online player list —",
                     "and that cache holds the REAL account name, written at login before NameMask runs.",
                     "So a display name never matched. Worse, on an offline-mode backend the cache does",
                     "not return 'not found': it FABRICATES a profile with a UUID derived from the name,",
                     "so an invite silently went to a phantom account and the real player saw nothing.",
                     "Only display names that DIFFER from the account name are intercepted; an exact",
                     "username still resolves through vanilla untouched.")
            .define("resolveDisplayNames", true);
        SIDEBAR_ENABLED = b
            .comment("Show the right-hand status sidebar (name, level, playtime, deaths, advancements, clan, online, season).",
                     "Server-wide master switch — players hide it individually with /sidebar, which is",
                     "stored in their persisted NBT, not the database. The panel is never shown in the",
                     "auth lobby (the tab list already carries the lobby guidance there).")
            .define("sidebarEnabled", true);
        AFK_ENABLED = b
            .comment("Stop playtime accruing while a player is AFK.",
                     "WHY: the sidebar level is derived from playtime alone, so without this an AFK",
                     "farm levels a player up as fast as playing does. Implemented by rolling their",
                     "session clock forward while idle, so idle time never enters the playtime figure",
                     "in ANY of the places that show it (/profile, sidebar, Discord /leaderboard).",
                     "Activity is player INPUT: chat, commands, breaking, using/placing, attacking,",
                     "interacting, containers, inventory clicks, hotbar, arm swings, sneak/sprint, vehicle",
                     "steering, walking and looking around. Movement caused by something else does NOT",
                     "count: position is ignored while riding, and on a Sable ship it is measured in the",
                     "ship's own coordinates, so a drifting airship no longer keeps its crew 'active'.",
                     "Taking damage and picking items up deliberately do NOT count — an AFK player in a",
                     "mob farm does both nonstop.",
                     "Turning this OFF does not retroactively restore time already excluded.")
            .define("afkEnabled", true);
        AFK_TIMEOUT_MINUTES = b
            .comment("Minutes of no activity before a player counts as AFK.",
                     "When it expires the WHOLE idle stretch is excluded, not just the part after the",
                     "threshold — otherwise a twitch every few minutes buys a free window each time.")
            .defineInRange("afkTimeoutMinutes", 10, 1, 120);
        AFK_ANNOUNCE = b
            .comment("Tell the other players in chat when someone goes AFK or comes back.",
                     "Plain grey, no house prefix — it is ambient information ('don't wait for a",
                     "reply'), so it is styled to read quieter than normal chat, not louder.",
                     "The AFK player is not sent their own announcement; they get a private line",
                     "about their playtime being paused instead.")
            .define("afkAnnounce", true);
        AFK_SEND_TO_LOBBY = b
            .comment("SMP ONLY: when a player goes AFK, TRANSFER them to the lobby server instead of",
                     "kicking them. Idling is harmless there — the lobby holds no world, no entities and",
                     "no ticking machinery — so this frees the SMP slot without disconnecting anybody,",
                     "without a cooling-off ban, and without the full gate round trip a rejoin costs.",
                     "",
                     "Tried BEFORE afkKickEnabled. If the transfer cannot be made (no lobbyReturnHost,",
                     "or the transfer is refused) it falls through to the kick, so turning this on never",
                     "silently disables the AFK handling you already had.",
                     "",
                     "Ignored on serverRole = LOBBY: there is nowhere to send them and idling is the point.")
            .define("afkSendToLobby", true);
        AFK_KICK_ENABLED = b
            .comment("Disconnect a player once they are marked AFK, and block re-entry briefly.",
                     "A bare kick is not a deterrent — the client reconnects in three seconds and",
                     "the farm carries on. Worse, every rejoin costs a full login round trip through",
                     "the gate plus a profile load, so a kick that is instantly undone is MORE load",
                     "than leaving the player standing there. The short ban below is what makes the",
                     "kick actually free a slot.",
                     "Uses vanilla's ban list, which is UUID-keyed and expires on its own.")
            .define("afkKickEnabled", true);
        AFK_KICK_BAN_MINUTES = b
            .comment("How long an AFK-kicked player is blocked from rejoining, in minutes.",
                     "0 = kick with no cooling-off period (they may rejoin immediately).")
            .defineInRange("afkKickBanMinutes", 5, 0, 1440);
        AFK_KICK_EXEMPT_OPS = b
            .comment("Never AFK-kick ops (permission 2+).",
                     "An admin parked in spectator watching a chunk, or idling while reading a log,",
                     "is doing their job. Unauthenticated players in the lobby are always exempt",
                     "regardless of this setting — they are mid-login, not idling in the world.")
            .define("afkKickExemptOps", true);
        AFK_MACRO_DETECTION = b
            .comment("Refuse machine-made input as activity: auto-clickers, a held key or a weight on the",
                     "mouse (clockwork timing, judged per input kind), mouse jigglers (flicking between 2-3",
                     "angles) and strafe macros (bouncing between 2 blocks). Refused input just doesn't",
                     "reset the idle timer, so the player goes AFK normally; nothing is punished here.",
                     "Staff get one MEDIUM alert when someone goes AFK while something kept firing.",
                     "false = every input counts, as before 1.11.5.")
            .define("afkMacroDetection", true);
        AFK_WARN_SECONDS = b
            .comment("Warn a player this many seconds before they would be moved to the lobby (or kicked)",
                     "for being AFK - a title plus a chat line. Any input cancels it. 0 = no warning.",
                     "Not shown to players who are exempt from being moved (ops).")
            .defineInRange("afkWarnSeconds", 60, 0, 300);

        RPM_CAP_ENABLED = b
            .comment("Cap Create rotational speed, with a separate ceiling inside Sable sub-levels.",
                     "Requires Create. Silently inactive if the mixin could not apply — check the",
                     "boot log for '[RpmCap]' and use /authmod rpm status.",
                     "NOTE: leaving this on costs nothing while rpmCapWorld == rpmCapSubLevel;",
                     "the ship lookup is skipped entirely when both ceilings are the same.")
            .define("rpmCapEnabled", true);
        RPM_CAP_WORLD = b
            .comment("Max RPM for kinetic blocks in the normal world. Default 256 = same as ships,",
                     "i.e. NOTHING IS CAPPED and Create behaves exactly as it always has.",
                     "Lower this (128 is the usual choice) to throttle ground machines while leaving",
                     "ships fast. RPM is a direct multiplier on Create's per-tick work: a belt at 256",
                     "moves twice the items of one at 128, and it all lands on the single-threaded",
                     "tick loop.",
                     "*** RUN '/authmod rpm scan' BEFORE LOWERING THIS. ***",
                     "It reports how many loaded machines are already above the value you are about",
                     "to set. Create's own maxRotationSpeed stays the ceiling; this may only lower it.")
            .defineInRange("rpmCapWorld", 256, 1, 8192);
        RPM_CAP_SUBLEVEL = b
            .comment("Max RPM for kinetic blocks inside a Sable sub-level (an assembled ship).",
                     "Capped by Create's own maxRotationSpeed, which is 256 by default.")
            .defineInRange("rpmCapSubLevel", 256, 1, 8192);
        RPM_CAP_DESTROYS = b
            .comment("What happens when a network exceeds the cap.",
                     "false (default, SAFE): the over-speed connection simply refuses to propagate.",
                     "  The machine stops. Nothing is broken and nothing is lost.",
                     "true (VANILLA CREATE): the block is DESTROYED, which is what Create itself does",
                     "  at its own maxRotationSpeed.",
                     "*** READ THIS BEFORE SETTING true ***",
                     "Create destroys the block on overspeed. Turning this on retroactively DESTROYS",
                     "every existing machine running between rpmCapWorld and Create's maxRotationSpeed",
                     "the moment its network next updates. Run '/authmod rpm scan' first to see how",
                     "many loaded blocks that is, and warn players, before you even consider it.")
            .define("rpmCapDestroys", false);

        CHAT_FILTER_ENABLED = b
            .comment("Screen public chat for filtered words.",
                     "Two severities per word, edited in-game with /authmod filter and stored in",
                     "chat_filter.json (no restart needed): CENSOR stars the word and lets the",
                     "message through; BLOCK drops the message entirely and raises a HIGH watchdog",
                     "alert. Both warn the sender and notify staff — BLOCK also keeps the original",
                     "text OUT of chat, the Discord bridge and the console broadcast; only the alert",
                     "carries it, because staff need to see what was actually said.",
                     "Matching tolerates spacing, punctuation, repeated letters and leetspeak, but is",
                     "anchored to word boundaries so 'ass' does not fire inside 'classic'.")
            .define("chatFilterEnabled", true);
        WATCHDOG_TAUNTS_ENABLED = b
            .comment("Occasionally remind everyone in chat that the watchdog is monitoring, with a bark.",
                     "Deterrence: the watchdog logs UUID switches, lookalike names, impossible",
                     "movement and command velocity anyway, but a monitor nobody knows about deters",
                     "nobody. Lobby players are excluded (they are still being onboarded), and",
                     "nothing is sent when the world is empty.")
            .define("watchdogTauntsEnabled", true);
        WATCHDOG_TAUNT_MIN_MINUTES = b
            .comment("Shortest gap (minutes) between watchdog reminders.")
            .defineInRange("watchdogTauntMinMinutes", 25, 1, 1440);
        WATCHDOG_TAUNT_MAX_MINUTES = b
            .comment("Longest gap (minutes) between watchdog reminders. The gap is re-rolled",
                     "uniformly in [min, max] after every broadcast — a FIXED schedule is something",
                     "to plan around, which would remove the entire deterrent value.")
            .defineInRange("watchdogTauntMaxMinutes", 55, 1, 1440);
        STALL_WATCHDOG_ENABLED = b
            .comment("Detect a HUNG server thread and restart the server if it does not recover.",
                     "WHY: the panel already auto-restarts on a CRASH, but a stall is not a crash —",
                     "the JVM stays alive, never exits, and the panel sees a healthy server while",
                     "nobody can play. That is exactly what happened 2026-08-20 18:12 (tick loop",
                     "stopped, spark timed out for 4.5 minutes, no restart) and 2026-08-21 00:00",
                     "(shutdown wedged, log full of 'Server already shutting down').",
                     "Writes a full thread dump to <world>/coffeesaeroauth/stalls/ naming the exact",
                     "blocking call, alerts staff, then halts the JVM so the panel restarts it.")
            .define("stallWatchdogEnabled", true);
        STALL_WARN_SECONDS = b
            .comment("Seconds without a tick before a thread dump is written and staff are alerted.",
                     "No kill at this point — a stall that recovers on its own is logged as RECOVERED,",
                     "which is the cheapest way to identify a slow operation without any downtime.",
                     "Keep it above your worst legitimate pause (chunk generation, a big autosave).")
            .defineInRange("stallWarnSeconds", 60, 10, 3600);
        STALL_KILL_SECONDS = b
            .comment("Seconds without a tick before the JVM is HALTED so the panel restarts it.",
                     "Must be larger than stallWarnSeconds. 0 = never kill, alert only.",
                     "Cost of a kill is bounded by SaveGuard: it banks player data every 60s and the",
                     "world every 120s, so ~1-2 minutes of progress. Cost of NOT killing is the whole",
                     "session plus indefinite downtime until somebody notices by hand.")
            .defineInRange("stallKillSeconds", 240, 0, 7200);
        STALL_SHUTDOWN_KILL_SECONDS = b
            .comment("Seconds a SHUTDOWN may take before the JVM is halted. 0 = never.",
                     "Tracked separately because ticks stop legitimately during shutdown, so the",
                     "tick-stall rule cannot apply — it would fire on every clean stop. This is the",
                     "rule that catches a wedged save (the 2026-08-21 00:00 failure).")
            .defineInRange("stallShutdownKillSeconds", 300, 0, 7200);
        LAG_ATTRIBUTION_ENABLED = b
            .comment("Sample the server thread during slow ticks and post an ATTRIBUTED lag report",
                     "to the watchdog Discord channel — which mod, which methods, which entities.",
                     "WHY, given spark is installed: spark is the better profiler but needs someone",
                     "awake to run /spark profiler and read a web report. Every lag event on this",
                     "server so far happened overnight and the evidence was gone by morning.",
                     "Reports go out at MEDIUM so they reach the watchdog channel without messaging",
                     "every op in-game (HIGH+ does that). Players are handled by lagWarn* instead.")
            .define("lagAttributionEnabled", true);
        LAG_SAMPLE_AFTER_MS = b
            .comment("Only sample once the CURRENT tick has already run this long (ms).",
                     "This is the cost control. Thread.getStackTrace() on another thread needs a",
                     "safepoint and is not free, so sampling unconditionally would itself become a",
                     "lag source. Paid only while a tick is already over budget, the overhead is",
                     "bounded and buys an attributed picture of a spike nobody witnessed.")
            .defineInRange("lagSampleAfterMs", 100, 20, 5000);
        LAG_SAMPLE_INTERVAL_MS = b
            .comment("Milliseconds between samples while a tick is overrunning. Lower = sharper",
                     "attribution and more overhead. 50ms (20/sec) is a deliberately safe default.")
            .defineInRange("lagSampleIntervalMs", 50, 10, 1000);
        LAG_SPIKE_REPORT_MS = b
            .comment("A single tick this slow (ms) triggers a report. Note this pack routinely",
                     "produces multi-second spikes during chunk loading, so keep it high enough that",
                     "reports stay meaningful rather than constant.")
            .defineInRange("lagSpikeReportMs", 500, 100, 60000);
        LAG_REPORT_COOLDOWN_MINUTES = b
            .comment("Minimum minutes between lag reports. Also gates the entity census, which is",
                     "the expensive half — see lagCensusMaxEntities.")
            .defineInRange("lagReportCooldownMinutes", 10, 1, 1440);
        LAG_CENSUS_MAX_ENTITIES = b
            .comment("Hard cap on entities walked during a census. The census runs INLINE on the",
                     "server thread (the only safe way to read entities) so it must be bounded:",
                     "diagnostics must never become the outage. A runaway world stops at this count",
                     "and the report says so.")
            .defineInRange("lagCensusMaxEntities", 20000, 1000, 500000);
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
        ADMIN_LOCK_MINUTES         = b.comment(
                "Minutes admin commands stay locked after the rate limit trips.",
                "Clear an active lock early with /authmod unlockadmin <player>.")
            .defineInRange("adminLockMinutes", 5, 1, 120);
        ADMIN_RATE_LIMIT_EXEMPT    = b.comment(
                "Comma-separated usernames NEVER rate-limited on /authmod commands.",
                "The owner belongs here: the limiter exists to catch a compromised or rogue op, and",
                "locking the person who administers the server out of their own admin commands during",
                "an incident is the opposite of what it is for. Exempt admins are still fully audited",
                "-- every command still reaches the Discord watchdog channel.")
            .define("adminRateLimitExempt", "MrCoffeeBench");
        TRUSTED_IP_MAX_COUNT       = b.comment("Max trusted IPs stored per offline player UUID.")
            .defineInRange("trustedIpMaxCount", 3, 1, 10);
        QUIET_HOURS_START          = b.comment("Quiet hours start (HH:mm UTC). Admin commands trigger owner alert.")
            .define("quietHoursStart", "02:00");
        QUIET_HOURS_END            = b.comment("Quiet hours end (HH:mm UTC).")
            .define("quietHoursEnd", "08:00");
        b.pop();

        b.comment("Vote rewards. Paid when a server-list site reports a vote through Votifier.",
                  "The reward SCALES with a player's lifetime vote count and is then capped:",
                  "    reward = min(max, base + (voteNumber - 1) / votesPerStep)",
                  "so a regular voter beats a one-off voter, but the per-vote ceiling stops it",
                  "inflating forever. Votes cast while offline are banked and paid on next join.")
            .push("voteRewards");
        VOTE_REWARD_ENABLED        = b.comment("Enable vote rewards.")
            .define("voteRewardEnabled", true);
        VOTE_REWARD_SPURS_BASE     = b.comment("Spurs for a player's FIRST vote.")
            .defineInRange("voteRewardSpursBase", 2, 0, 10000);
        VOTE_REWARD_SPURS_MAX      = b.comment("HARD CAP on spurs per vote, however many times they vote.")
            .defineInRange("voteRewardSpursMax", 10, 0, 10000);
        VOTE_REWARD_DIAMONDS_BASE  = b.comment("Diamonds for a player's FIRST vote.")
            .defineInRange("voteRewardDiamondsBase", 1, 0, 1000);
        VOTE_REWARD_DIAMONDS_MAX   = b.comment("HARD CAP on diamonds per vote.")
            .defineInRange("voteRewardDiamondsMax", 5, 0, 1000);
        VOTE_URL                   = b.comment(
                "Where /vote sends players. Shown in the reminder and in /vote.")
            .define("voteUrl", "https://www.createmodservers.com/");
        VOTE_COOLDOWN_HOURS        = b.comment(
                "Hours between votes ON THE SITE. The server cannot read the site's cooldown -- it only",
                "ever hears about a vote AFTER it lands -- so this is what /vote counts down from, using",
                "the player's own last recorded vote. Set it to match the site (24 for most, some use 12).",
                "If it is wrong the reward still pays fine; only the countdown is off.")
            .defineInRange("voteCooldownHours", 24, 1, 168);
        VOTE_REMINDER_ENABLED      = b.comment(
                "Tell a player when they can vote again -- once on join if they are already eligible,",
                "and once at the moment the cooldown elapses while they are online. Never repeats.")
            .define("voteReminderEnabled", true);
        VOTE_REWARD_MAX_REWARDED   = b.comment(
                "LIFETIME cap on how many of a player's votes are ever PAID. 0 = unlimited.",
                "With 30, votes 1-30 pay the ladder below and vote 31 onward pays nothing at all.",
                "Votes past the cap are still recorded and still count for the server's listing",
                "rank -- the player is simply told they have collected everything.")
            .defineInRange("voteRewardMaxRewardedVotes", 30, 0, 100000);   // IGNORED since 1.13.4: streak-based, no lifetime cap
        VOTE_ANNOUNCE_ENABLED      = b.comment(
                "Announce every vote to the whole server, with a quiet sound for bystanders.",
                "Fires when the vote LANDS, so it celebrates offline voters too.")
            .define("voteAnnounceEnabled", true);
        VOTE_REWARD_VOTES_PER_STEP = b.comment(
                "Votes needed to earn +1 spur and +1 diamond. 1 = the reward grows every single vote.",
                "With the defaults (spurs 2->10, diamonds 1->5, step 1) the ladder is:",
                "    vote 1: 2 spurs, 1 diamond      vote 4: 5 spurs, 4 diamonds",
                "    vote 2: 3 spurs, 2 diamonds     vote 5: 6 spurs, 5 diamonds  <- diamonds capped",
                "    vote 3: 4 spurs, 3 diamonds     vote 9: 10 spurs, 5 diamonds <- spurs capped",
                "and every vote after that pays the caps: 10 spurs + 5 diamonds.")
            .defineInRange("voteRewardVotesPerStep", 1, 1, 1000);
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
                "already blocks /spawn while combat-tagged. In-memory (resets on server restart). 0 = no cooldown.",
                "Default 1 (2026-08-08). NOTE: this default only applies to a FRESH config file —",
                "an existing coffees_aero_auth-server.toml keeps whatever value it already has.")
            .defineInRange("spawnTeleportCooldownMinutes", 1, 0, 1440);
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
        RTP_ANCHOR_COUNT = b.comment(
                "ANCHOR MODE (1.7.5). Number of fixed destinations reused by /rtp instead of rolling a fresh",
                "random target every time. Each anchor is generated ONCE (background warmer, one at a time, only",
                "while no player rtp is running) and then persists on disk, so later arrivals LOAD chunks (~ms)",
                "instead of GENERATING them (0.5-3.4s each measured live). That is the whole win: the pregen wait",
                "and the worldgen burst both disappear for every use after the first.",
                "Anchors are stored in rtp_anchors.json. Delete that file to re-roll them.",
                "0 = disable anchors entirely and use the original per-request random target.")
            .defineInRange("rtpAnchorCount", 30, 0, 200);
        RTP_ANCHOR_MAX_DISTANCE = b.comment(
                "Max distance (blocks) from world spawn when PICKING anchors. rtpMinDistance is still the floor.",
                "Anchors are spread evenly by angle around spawn so they do not clump.")
            .defineInRange("rtpAnchorMaxDistance", 15000, 1000, 1_000_000);
        RTP_ANCHOR_SPREAD = b.comment(
                "Blocks of random scatter around an anchor when landing. Stops 30 anchors becoming 30 exact",
                "blocks that get stripped bare and built over. Kept inside the pregenerated bubble: with",
                "rtpPregenRadius=7 the safe ceiling is about 7*16-16 = 96. Larger risks landing on the",
                "ungenerated rim, which re-introduces the arrival worldgen burst this feature exists to remove.")
            .defineInRange("rtpAnchorSpread", 64, 0, 96);
        RTP_ANCHOR_WARM_MAX_PLAYERS = b.comment(
                "Warm anchors ONLY while at most this many players are online. Default 0 = empty server only.",
                "ADDED 1.7.6 after 1.7.5 went live: the warmer previously ran in every idle window, so it was",
                "generating 6750 chunks (30 anchors x 225) WHILE players were on, and that worldgen is exactly",
                "the load /rtp anchors exist to eliminate. Warming an empty server is free; warming a busy one",
                "just moves the lag rather than removing it.",
                "Pausing costs nothing: generated chunks persist on DISK, so a paused job simply resumes later",
                "and re-walks the finished rings at load speed instead of generation speed.",
                "Raise it only if you want warming to continue with players online.")
            .defineInRange("rtpAnchorWarmMaxPlayers", 0, 0, 200);
        RTP_ANCHOR_WARM_RING_STEP = b.comment(
                "How many chunk-rings the WARMER widens by each time the current ring completes (player /rtp",
                "pregens always use 2). Higher = fewer poll cycles and more chunks generating in parallel =",
                "faster warming, at the cost of a heavier burst. 4 is safe because warming only runs when the",
                "server is under rtpAnchorWarmMaxPlayers, i.e. normally empty with nobody to disturb.")
            .defineInRange("rtpAnchorWarmRingStep", 4, 1, 8);
        RTP_ANCHOR_WARM_MARGIN = b.comment(
                "Extra chunk rings the WARMER generates BEYOND rtpPregenRadius. 1.7.8 fix — do not set to 0.",
                "A pregenerated bubble's outermost ring is SOFT: those chunks are FULL on disk, but promoting",
                "them back to FULL on the next visit needs their own neighbours one ring further out, which the",
                "warmer never generated. So arriving re-ran worldgen for the rim only — measured live 2026-08-05:",
                "the inner 169 chunks loaded in under a second, then the 56-chunk rim took 152s at ~2.7s/chunk.",
                "Warming one or two rings past what /rtp requires puts the required rim's neighbours on disk too,",
                "so the whole player bubble is a pure load. Cost is one-time and offline: radius 7 + margin 2 is",
                "19x19 = 361 chunks per anchor instead of 225.")
            .defineInRange("rtpAnchorWarmMargin", 2, 0, 6);
        b.pop();

        b.comment("Watchdog notification routing").push("watchdogalerts");
        MOVEMENT_ALERT_DISCORD = b.comment(
                "Send 'Impossible Movement' to the Discord webhook. Default FALSE (1.7.5).",
                "This check fires on any large server-applied velocity — AeroKit's launch stick, mount",
                "glitches, lag spikes — and at MEDIUM severity each one queued a webhook. One AeroKit",
                "session was enough to rate limit the bridge and stall chat delivery (2026-08-04).",
                "Detection and the velocity-zeroing are unaffected; the daily anomaly roll-up still lists it.",
                "Use /authmod movegrace <player> <seconds> to exempt a player before an intentional launch.")
            .define("movementAlertDiscord", false);
        b.pop();

        b.comment("Player-facing /shipname. Sable's own rename is /sable sub_level name set, which is op-only,",
                  "so without this players had to ask an admin for a purely cosmetic change. The name shown on",
                  "the AeroClaims claim screen is this same sub-level name — every ship reads 'ship' until set.")
            .push("shipname");
        SHIPNAME_ENABLED = b.comment("Enable /shipname for players.")
            .define("shipNameEnabled", true);
        SHIPNAME_MAX_LENGTH = b.comment("Maximum ship name length. Section signs are stripped regardless,",
                "so players cannot inject colour codes or §k scramble into the claim UI.")
            .defineInRange("shipNameMaxLength", 32, 4, 64);
        b.pop();

        b.comment("Plot-space guard. Sable parks sub-level DATA at ~20,000,000 blocks out; a PLAYER at those",
                  "coordinates makes vanilla ask for chunks Sable's plot.ChunkMapMixin owns, the ChunkHolder is",
                  "absent, and ChunkMap.acquireGeneration NPEs on the MAIN TICK LOOP (live crash 2026-08-04).",
                  "The position is saved in playerdata/<uuid>.dat, so it recurs every login until someone edits",
                  "the .dat offline. This clamps it at load instead.")
            .push("plotguard");
        PLOTGUARD_ENABLED = b.comment("Master switch. The load-time clamp (the anti-crash-loop half) is always on when this is true.")
            .define("plotGuardEnabled", true);
        PLOTGUARD_RESCUE = b.comment(
                "Governs the 1Hz LIVE sweep only — whether an already-online player found in plot space is",
                "teleported back (true) or merely logged (false).",
                "Ships false on purpose: it rests on the assumption that Sable never legitimately places a",
                "player past the limit (a player on a normal assembled ship has NORMAL world coordinates).",
                "Run it false first; if the only names in the [PlotGuard] log lines are genuinely stuck players,",
                "flip it true. If someone appears while happily flying a ship, raise plotGuardLimit instead.")
            .define("plotGuardRescue", false);
        PLOTGUARD_LIMIT = b.comment(
                "Blocks from origin on X or Z past which a player is considered to be in plot space.",
                "Sable plots sit near 20,000,000; normal survival play never approaches 1,000,000.")
            .defineInRange("plotGuardLimit", 1_000_000, 100_000, 25_000_000);
        PLOTGUARD_BLOCK_TELEPORTS = b.comment(
                "Refuse any teleport whose DESTINATION is past plotGuardLimit. Default true — this prevents a",
                "hard server crash, so it is NOT gated behind plotGuardRescue.",
                "ADDED 1.7.7: warping to a waystone placed inside a SHIP crashed the server (heavy lag first).",
                "A waystone stores its own BLOCK position, and ship blocks live in Sable plot space at",
                "~20,000,000 — so the warp is a teleport to plot space, i.e. the same ChunkMap.acquireGeneration",
                "NPE as 08-04, reached by an ordinary player action.",
                "The tick sweep CANNOT cover this: ServerPlayer.teleportTo adds a POST_TELEPORT chunk ticket at",
                "the destination on its first two lines, before any tick handler runs. Only a HEAD guard is early",
                "enough. Blocked teleports leave the player where they were and tell them why.")
            .define("plotGuardBlockTeleports", true);
        CLEAR_STUCK_INVULNERABLE = b.comment(
                "Clear a stuck Invulnerable flag out of a player's SAVED DATA at join.",
                "The vanilla Invulnerable tag is written by Entity.addAdditionalSaveData and read back by",
                "Entity.load, so once something sets it on a player it lives in playerdata/<uuid>.dat and",
                "survives every relog and restart — the player is immune to mobs, fall, fire, drowning and PvP",
                "(everything except /kill-class damage and a creative player's hit).",
                "A DATAPACK CANNOT FIX THIS: EntityDataAccessor.setData throws for any Player, so",
                "/data modify entity <player> is refused for everyone, always. Detection still works, which is",
                "what the aero-invuln-audit datapack does. This is the repair half.",
                "Only ever CLEARS, never sets, and only at join — so it cannot fight an admin or a mod that",
                "sets invulnerability deliberately during play. Creative and spectator are skipped (they use",
                "abilities.invulnerable, a different field entirely). Every repair is logged with the name.",
                "Turn OFF only if a mod in the pack legitimately persists Invulnerable on players.")
            .define("clearStuckInvulnerableOnJoin", true);
        b.pop();

                b.comment("Standalone-lobby split. ONE JAR SERVES BOTH SERVERS — the role below is the only",
                  "difference. Two builds is what produced the 1.7.51-vs-1.7.54 skew across a shared",
                  "database on 2026-09-07, which makes every later bug ambiguous. Do not fork the jar.")
            .push("split");
        SERVER_ROLE = b.comment(
                "SMP   = the survival backend. Unchanged behaviour; the in-process auth lobby still works.",
                "LOBBY = the standalone login front door. /spawn TRANSFERS to the SMP instead of teleporting.",
                "🔴 Defaults to SMP on purpose: an accidental deploy must never turn a survival server into",
                "a lobby that ejects everyone who types /spawn. Anything unrecognised is treated as SMP.")
            .define("serverRole", "SMP");
        HANDOFF_ENABLED = b.comment(
                "Master switch for the LOBBY -> SMP handoff. Ignored unless serverRole = LOBBY.",
                "Turn this off to strand players on the lobby deliberately (maintenance on the SMP).")
            .define("handoffEnabled", true);
        HANDOFF_HOST = b.comment(
                "Host the lobby transfers players TO — the SMP as players' clients must reach it.",
                "🔴 This is a CLIENT-VISIBLE address, not an internal one: the vanilla transfer packet makes",
                "the client disconnect and reconnect here itself. An internal/container address will look",
                "like a working config and fail for every real player.",
                "The SMP must also have accepts-transfers=true, or the reconnect is refused.")
            .define("handoffHost", "");
        HANDOFF_PORT = b.comment("Port on handoffHost. The SMP's public game port.")
            .defineInRange("handoffPort", 25565, 1, 65535);
        HANDOFF_COOKIE_TTL_SECONDS = b.comment(
                "Lifetime of the handoff cookie the lobby signs for the SMP.",
                "Must cover a client disconnect + reconnect and nothing more — it is a bearer token for a",
                "verified identity. The cookie is single-use (nonce), so this is a ceiling, not a window",
                "that stays open. 30s is generous for a transfer that normally takes 2-3s.")
            .defineInRange("handoffCookieTtlSeconds", 30, 5, 300);
        LOBBY_RETURN_HOST = b.comment(
                "The REVERSE trip: where /lobby sends a player back to. Set this on the SMP.",
                "Blank = disabled, and /lobby keeps its old meaning (admin preview of this server's own",
                "auth_lobby). Like handoffHost this is a CLIENT-VISIBLE address — the client dials it.",
                "The lobby server must have accepts-transfers=true.",
                "🔑 The SMP signs a cookie for this trip, so premium survives it. Without one the player",
                "would arrive at the lobby resolved as OFFLINE.")
            .define("lobbyReturnHost", "");
        LOBBY_RETURN_PORT = b.comment("Port of the lobby server for the /lobby return trip.")
            .defineInRange("lobbyReturnPort", 25565, 1, 65535);
        REQUIRE_LOBBY_ENTRY = b.comment(
                "SMP ONLY: refuse players who connect DIRECTLY instead of arriving from the lobby.",
                "Arrival is proved by the signed cookie the lobby (or the gate) stores on the client before",
                "transferring — a direct connection has none, which is the same signal that already stops a",
                "direct connection claiming premium. Refused players are disconnected with lobbyEntryAddress.",
                "🔴 EXEMPT: operators of THIS server (permission 4). ops.json is per-server, so an op on the",
                "lobby who is not an op here gets no exemption — which is the intended rule.",
                "⚠️ This also closes the premium reconnect-grace path, because that path is BY DEFINITION a",
                "direct connection. A player whose cookie was spent must go back through the front door.",
                "Defaults to FALSE: switching this on with a wrong lobbyEntryAddress locks everyone out of",
                "the survival server, so it must be an explicit, deliberate choice.")
            .define("requireLobbyEntry", false);
        LOBBY_ENTRY_ADDRESS = b.comment(
                "The address shown to a refused player — what they should actually connect to.",
                "This is the PUBLIC front door (the gate), not the lobby's internal address.")
            .define("lobbyEntryAddress", "");

        SMP_LIVENESS_ENABLED = b.comment(
                "LOBBY ONLY: watch whether the SMP is actually up, by Server List Ping to handoffHost.",
                "A heartbeat row in the shared MySQL would be easier and answers the WRONG question — it",
                "can be stale-but-alive (writer thread died) or alive-but-not-joinable (mid-boot). An SLP",
                "tests the only thing a player cares about: is the port answering the protocol.")
            .define("smpLivenessEnabled", false);
        SMP_LIVENESS_POLL_SECONDS = b.comment("Seconds between polls. Runs on its own thread, never the tick loop.")
            .defineInRange("smpLivenessPollSeconds", 15, 5, 300);
        SMP_LIVENESS_CONFIRM = b.comment(
                "🔴 How many CONSECUTIVE identical polls flip the state. This is the sharp edge of the whole",
                "feature: one false negative refuses everybody, one false positive fires players at a dead",
                "port. Never set this to 1 — a single dropped packet is not an outage.")
            .defineInRange("smpLivenessConfirmCount", 3, 2, 10);
        SMP_LIVENESS_TIMEOUT_MS = b.comment("Socket timeout per poll.")
            .defineInRange("smpLivenessTimeoutMs", 3000, 500, 15000);
        AUTO_READMIT = b.comment(
                "When the SMP returns, transfer waiting players automatically instead of only telling them.",
                "OFF by default: it moves people without asking, and a wrong liveness reading would move them",
                "into a server that is not ready.")
            .define("autoReadmit", false);
        READMIT_STAGGER_MS = b.comment(
                "Milliseconds between automatic re-admissions. A restart can end with a room full of people;",
                "transferring them at once hands the SMP the whole lobby's reconnects in one tick.")
            .defineInRange("readmitStaggerMs", 750, 0, 10000);
        GRACEFUL_RESTART_ENABLED = b.comment(
                "SMP ONLY: at the end of the restart countdown, TRANSFER everyone to the lobby instead of",
                "letting the restart drop them. They are never kicked and never asked to reconnect.",
                "⚠️ This does NOT stop the server — the Pterodactyl panel still owns that. Two things",
                "restarting one process gives you a stop landing on a server that is already starting.",
                "",
                "⚠️ Requires lobbyReturnHost/lobbyReturnPort to be set and AERO_GATE_SECRET present.",
                "Without a return host, returnToLobby() declines and the evacuation quietly does nothing.",
                "Default changed false -> true on 2026-09-09 at the owner's request.")
            .define("gracefulRestartEnabled", true);
        EVACUATE_SECONDS_BEFORE = b.comment(
                "Seconds before the restart moment to evacuate. Transfers are asynchronous — the client",
                "disconnects and reconnects itself — so evacuating AT the restart cuts transfers in flight.")
            .defineInRange("evacuateSecondsBeforeRestart", 20, 5, 300);
        b.pop();

        b.comment("Warning for the HOST'S daily scheduled restart.",
                  "The Lagless panel restarts this server on a timer and tells nobody, so players in a Create",
                  "airship or mid-trade simply drop. This does NOT restart anything — it only counts down to a",
                  "moment the panel decided, using the same boss bar as /authmod warn.",
                  "🔴 IF YOU CHANGE THE PANEL'S SCHEDULE, CHANGE dailyRestartTime TOO. Nothing here can read the",
                  "panel's timetable, and a countdown that ends at the wrong moment is worse than none.")
            .push("daily_restart");
        DAILY_RESTART_WARN_ENABLED = b.comment("Master switch for the automatic daily restart warning.")
            .define("dailyRestartWarnEnabled", true);
        DAILY_RESTART_TIME = b.comment(
                "Time of the host's daily restart, 24-hour HH:mm, in dailyRestartTimezone.",
                "Must match the panel's scheduled task exactly.")
            .define("dailyRestartTime", "10:30");
        DAILY_RESTART_TIMEZONE = b.comment(
                "IANA zone id the above time is expressed in, e.g. Asia/Colombo, UTC, Europe/London.",
                "Deliberately NOT the JVM default: the container currently reports +05:30, which happens to",
                "match Asia/Colombo, so a default-zone implementation would look right today and fire at the",
                "wrong hour the moment the backend moves host — and it has moved three times already.")
            .define("dailyRestartTimezone", "Asia/Colombo");
        DAILY_RESTART_WARN_MINUTES = b.comment(
                "How many minutes before the restart the countdown appears.")
            .defineInRange("dailyRestartWarnMinutes", 10, 1, 120);
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
        LOBBY_SPAWN_YAW = b.comment(
                "Which way a player faces when placed on the lobby spawn pad.",
                "Minecraft yaw: 0 = +Z (south), 90 = -X (west), 180 = -Z (north), -90 = +X (east).",
                "Default 180 is the pre-2026-09-08 hardcoded behaviour (due north), so leaving it alone",
                "changes nothing on the SMP's in-process lobby.",
                "To face a specific point from the pad:  yaw = -atan2(dx, dz) in degrees.")
            .defineInRange("lobbySpawnYaw", 180.0, -180.0, 180.0);
        LOBBY_SPAWN_PITCH = b.comment(
                "Vertical facing on the lobby spawn pad. POSITIVE IS DOWN (Minecraft convention).",
                "To face a point:  pitch = -atan2(dy, sqrt(dx*dx + dz*dz)) in degrees, where dy is measured",
                "from the player's EYE height (pad Y + 1.62), not from their feet — miss that and the aim",
                "sits about a block high at close range.")
            .defineInRange("lobbySpawnPitch", 0.0, -90.0, 90.0);
        LOBBY_FLOOR_Y = b
            .comment("Reference island floor Y for the fall-catch.")
            .defineInRange("lobbyFloorY", 100, -64, 320);
        LOBBY_FALL_CATCH_DROP = b
            .comment("Blocks below the floor before a player who fell off the island is returned to spawn.")
            .defineInRange("lobbyFallCatchDrop", 15, 3, 200);
        LOBBY_ALLOWED_COMMANDS = b
            .comment("Commands a NON-OP player may run while inside the auth lobby (comma-separated, no",
                     "leading slash). Everything else is refused with a pointer to /spawn. Ops are exempt.",
                     "WHY A WHITELIST, NOT A BLOCKLIST: while a player is in the lobby their real inventory",
                     "lives in the lobby stash, and /spawn is the ONE exit that restores it AND pays the",
                     "first-world-entry rewards (starter spurs, Season veteran reward). Any teleport that",
                     "leaves the lobby by another route silently skips those — /home did exactly that until",
                     "2026-08-18. A blocklist would have to name every teleport command in the pack",
                     "(grand-teleport, waystones, future mods); a whitelist is closed by default.",
                     "A namespace prefix is stripped before matching, so 'home' also covers 'ftbessentials:home'.")
            .define("lobbyAllowedCommands",
                    "spawn,login,register,setname,changename,changepassword,logout,discord,profile,setbio,"
                  + "skin,vote,help,msg,tell,w,r,whisper,me,mytrustedips,sidebar");
        LOBBY_FORCELOAD_RADIUS_CHUNKS = b
            .comment("IGNORED since 1.13.3 — the mod no longer force-loads anything (owner, 2026-10-04).")
            .defineInRange("lobbyForceloadRadiusChunks", 8, 1, 32);
        LOBBY_PREPLACED_BUILD = b
            .comment("DEPRECATED and ignored since 2026-09-09 — kept only so the key does not vanish from",
                     "existing configs. The mod no longer has ANY code that writes blocks into the lobby, so",
                     "there is nothing left for this to switch off. It used to gate a template and platform",
                     "placer; that placer was deleted rather than left behind a flag, because a flag that must",
                     "stay set to avoid damaging a hand-built map is a weaker guarantee than no code at all.")
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
        OVERWORLD_SPAWN_YAW = b
            .comment("Direction players face at spawn (yaw, degrees): 0 = south, 90 = west, 180 = north, -90 = east.")
            .defineInRange("overworldSpawnYaw", 0.0, -180.0, 180.0);
        SPAWN_FORCELOAD_RADIUS_CHUNKS = b
            .comment("IGNORED since 1.13.3 — the mod no longer force-loads anything (owner, 2026-10-04). Kept so",
                     "existing configs do not lose the key; the old ring is released once on the next boot.")
            .defineInRange("spawnForceloadRadiusChunks", 7, 0, 16);
        b.pop();

        b.push("home");
        HOME_TP_COOLDOWN_MINUTES = b
            .comment("Cooldown in minutes for /home (teleport to your bed/respawn point). 0 = no cooldown. Ops exempt.")
            .defineInRange("homeTeleportCooldownMinutes", 5, 0, 1440);
        b.pop();

        b.push("moderation");
        CONFISCATE_ALLOWED_COMMANDS = b
            .comment("Root command literals a CONFISCATED player may still run (lowercase, no slash,",
                     "no namespace prefix — 'back', not '/ftbessentials:back').",
                     "Everything else is blocked. Empty by default: a confiscated player is meant to",
                     "talk to the admin holding them, and chat is never blocked.")
            .defineListAllowEmpty("confiscateAllowedCommands",
                java.util.List.<String>of(),
                () -> "msg",
                o -> o instanceof String s && !s.isBlank());
        b.pop();

        b.push("exploit");
        EXPLOIT_DETECT_ENABLED = b
            .comment("Alert admins when a flagged block is assembled onto a watched bearing.",
                     "ALERT ONLY — nothing is blocked and no assembly is ever refused.")
            .define("exploitDetectEnabled", true);
        EXPLOIT_RULES = b
            .comment("Exploit rules, one per line, as \"assembler|flagged\".",
                     "\"*\" on either side means ANY. Malformed lines are dropped, not fatal.",
                     "",
                     "Pairing matters: an Item Drain is only an exploit on a Swivel Bearing, while",
                     "string is an exploit on ANY bearing. A flat two-list cross-product could not",
                     "express both, and would alert on every item drain on every bearing.",
                     "",
                     "NOTE: placing string produces the BLOCK minecraft:tripwire, not",
                     "minecraft:string — the item id never appears inside a contraption.")
            .defineListAllowEmpty("exploitRules",
                com.coffeesaerosmp.auth.exploit.ExploitRules.DEFAULT_RULES,
                () -> "*|minecraft:tripwire",
                o -> o instanceof String s && s.indexOf('|') > 0);
        EXPLOIT_FLAGGED_BLOCKS = b
            .comment("Block ids that raise an alert when found in an assembling structure.",
                     "Empty disables detection. NO '*' wildcard here — it would alert on every",
                     "block of every contraption. Unknown ids are logged once at boot and ignored.")
            .defineListAllowEmpty("exploitFlaggedBlocks",
                java.util.List.of("create:item_drain"),
                () -> "create:item_drain",
                o -> o instanceof String s && !s.isBlank());
        EXPLOIT_ASSEMBLER_BLOCKS = b
            .comment("Anchor block ids to watch. The literal \"*\" watches every assembler.",
                     "simulated:swivel_bearing is the Swivel Bearing — note it comes from the",
                     "'simulated' mod nested inside the Create: Aeronautics bundle, not Create,",
                     "and it assembles a Sable sub-level rather than a Create contraption.")
            .defineListAllowEmpty("exploitAssemblerBlocks",
                java.util.List.of("simulated:swivel_bearing"),
                () -> "simulated:swivel_bearing",
                o -> o instanceof String s && !s.isBlank());
        EXPLOIT_SCAN_MAX_BLOCKS = b
            .comment("Stop scanning an assembling structure after this many blocks.",
                     "Bounds the tick cost on very large ships; a capped scan is logged once.")
            .defineInRange("exploitScanMaxBlocks", 20000, 100, 500000);
        EXPLOIT_ALERT_COOLDOWN_SECONDS = b
            .comment("Minimum seconds between alerts for the same anchor + block.",
                     "A bearing on a redstone clock would otherwise ping admins continuously.",
                     "A suppressed alert does not extend the window, so the next one still fires",
                     "on schedule rather than being pushed back forever by constant re-assembly.")
            .defineInRange("exploitAlertCooldownSeconds", 600, 0, 86400);
        b.pop();

        b.push("advancements");
        MASK_ADVANCEMENT_NAMES = b
            .comment("Announce advancements with the player's display name instead of their account username.",
                     "When true, vanilla's announceAdvancements chat broadcast is disabled at startup and replaced",
                     "by a display-name version. The earner's own toast popup is unaffected.")
            .define("maskAdvancementNames", true);
        SKIP_RECIPE_ADVANCEMENT_LISTENERS = b
            .comment("Do not register criteria listeners for recipe-unlock advancements.",
                     "",
                     "MEASURED 2026-09-08 on a 180s spark profile of the live server: the advancement",
                     "system was 9.39% of the server thread, and 7.89% (14,188 ms) of that was a single",
                     "path - ServerPlayer$2.slotChanged -> InventoryChangeTrigger.trigger. Every slot",
                     "change linearly scans every registered inventory_changed listener. The pack ships",
                     "10,018 advancements of which 9,195 are recipe unlocks, and 9,168 of those carry an",
                     "inventory_changed criterion - so 98% of that scan exists only to fill the recipe book.",
                     "",
                     "Safe here because doLimitedCrafting is false (recipes never gate crafting) and the",
                     "pack ships EMI for browsing and auto-fill. Already-unlocked recipes are kept: they",
                     "live in the player's recipeBook NBT, not in advancements. Fully reversible - set this",
                     "false and listeners register again on the next join.",
                     "",
                     "COST: the recipe book stops filling in as players pick up new ingredients.")
            .define("skipRecipeAdvancementListeners", true);
        b.pop();


        b.push("community");
        DISCORD_INVITE_URL = b
            .comment("Discord invite shown to new players in the first-join welcome (clickable).",
                     "Blank = the Discord line is omitted entirely.")
            .define("discordInviteUrl", "https://discord.gg/AnFUh5vTz6");
        BROADCAST_NEW_PLAYERS = b
            .comment("Announce a brand-new player's arrival to everyone online. First join only —",
                     "never on a returning login.")
            .define("broadcastNewPlayers", true);
        b.pop();

        b.comment("Lag warning. See LagMonitor.").push("lagwarn");
        LAG_WARN_ENABLED = b
            .comment("Tell players when the server is genuinely struggling, instead of leaving them",
                     "to guess whether it is them or the server.")
            .define("lagWarnEnabled", true);
        LAG_WARN_MSPT = b
            .comment("Average milliseconds-per-tick that counts as BAD lag. 50 = a perfect 20 TPS.",
                     "Default 250 (~4 TPS) — deliberately high, so this fires on real trouble and not",
                     "on the routine 4s spikes this pack already produces. Lower it and players get",
                     "spammed during normal chunk loading, which trains them to ignore it.")
            .defineInRange("lagWarnMsptThreshold", 250, 100, 5000);
        LAG_WARN_SUSTAIN_SECONDS = b
            .comment("How long the average must stay above the threshold before anyone is told.",
                     "Stops a single garbage-collection pause from announcing itself.")
            .defineInRange("lagWarnSustainSeconds", 10, 1, 300);
        LAG_WARN_COOLDOWN_SECONDS = b
            .comment("Minimum seconds between lag warnings, so a long bad patch warns once, not forever.")
            .defineInRange("lagWarnCooldownSeconds", 300, 30, 3600);
        b.pop();

        b.push("feedback");
        SOUND_FEEDBACK = b
            .comment("Play a short sound when a command succeeds, is refused, or a reward is ready.",
                     "Sent only to the acting player (playNotifySound), never to bystanders.",
                     "false = every command is silent again.")
            .define("soundFeedback", true);
        b.pop();

        b.comment("Crash-loss protection. See SaveGuard.").push("saveguard");
        SAVEGUARD_ENABLED = b
            .comment("Periodically flush player data and the world so a hard crash loses less.",
                     "WHY: Sable's native Rapier physics can panic across the JNI boundary, and that",
                     "boundary cannot unwind, so Rust calls abort(). The JVM dies instantly — no crash",
                     "report, no shutdown, NO WORLD SAVE. On 2026-08-08 the server aborted 3m18s after",
                     "boot, inside vanilla's 5-minute autosave window, so every one of the six players",
                     "online lost the entire session (including a Tombstone grave created 36s earlier).",
                     "This cannot prevent the abort. It bounds what an abort costs.")
            .define("saveGuardEnabled", true);
        SAVEGUARD_PLAYER_SECONDS = b
            .comment("Seconds between PLAYER-DATA saves (inventories, ender chests, positions).",
                     "Cheap: writes one small file per online player, no chunk I/O. This is the setting",
                     "that protects backpacks and inventories. 0 disables just this half.")
            .defineInRange("saveGuardPlayerSeconds", 60, 0, 3600);
        SAVEGUARD_WORLD_SECONDS = b
            .comment("Seconds between FULL world saves (chunks + player data). Expensive — this is the",
                     "one that can cause a tick spike, so keep it well above saveGuardPlayerSeconds.",
                     "Needed for anything stored in the WORLD rather than on the player: Tombstone",
                     "graves, dropped items, chests, ships. Vanilla's own autosave is 300s; 120 halves",
                     "the worst-case loss. 0 disables just this half.")
            .defineInRange("saveGuardWorldSeconds", 120, 0, 3600);
        SAVEGUARD_SKIP_WHEN_EMPTY = b
            .comment("Skip both saves when nobody is online. Default true — there is nothing to lose on",
                     "an empty server, and it keeps idle disk I/O at zero.")
            .define("saveGuardSkipWhenEmpty", true);
        SAVEGUARD_SLOW_WARN_MS = b
            .comment("Log a WARN when a save blocks the server thread longer than this (ms). Tune the",
                     "intervals up if this fires often — a save that stalls the tick loop is its own problem.")
            .defineInRange("saveGuardSlowWarnMs", 1000, 100, 60_000);
        b.pop();

        b.comment("Rank and cosmetics store (Tebex).").push("store");
        STORE_ENABLED = b
            .comment("Master switch for the player-facing store commands (/buy, /cosmetics).",
                     "",
                     "OFF by default on purpose. The admin grant surface stays available either way, so",
                     "a rank can be handed out and tested before anything is advertised to players — and",
                     "a store that is half-configured should be invisible rather than broken.")
            .define("storeEnabled", false);
        STORE_URL = b
            .comment("Public webstore URL, shown by /buy. Must be reachable without logging in:",
                     "Mojang's rules require all content and prices to be visible before anyone signs up.")
            .define("storeUrl", "");
        STORE_SUPPORT_EMAIL = b
            .comment("Support contact shown alongside the store link.",
                     "🔑 Required by Mojang's rules, and a Discord or forum link explicitly does NOT",
                     "count — it has to be an email address.")
            .define("storeSupportEmail", "");
        b.pop();

        b.comment("Season 3 levels: achievement-driven level, claims by level, level-up mail (leveling/).").push("leveling");
        LEVELING_ENABLED = b
            .comment("Master switch. ON: the sidebar level comes from LevelFormula (real advancements + season",
                     "playtime), FTB claim limits follow the level, and each new level mails a reward.",
                     "OFF (default): the S2 playtime-only level, nothing else changes. SMP role only.")
            .define("levelingEnabled", false);
        LEVEL_XP_PER_ADVANCEMENT = b.comment("XP per completed real (non-recipe) advancement.")
            .defineInRange("levelXpPerAdvancement", 10, 0, 10_000);
        LEVEL_XP_PER_HOUR = b.comment("XP per hour of SEASON playtime (AFK excluded). 10/6 ≈ 80/20 at the S2 pace.")
            .defineInRange("levelXpPerHour", 6, 0, 10_000);
        LEVEL_CURVE = b.comment("level = 1 + floor(sqrt(xp / curve)). Bigger = slower levels.")
            .defineInRange("levelCurve", 5, 1, 100_000);
        LEVEL_CLAIMS_ENABLED = b
            .comment("Set each player's FTB Chunks EXTRA claim chunks from their level.",
                     "🔴 FTB Chunks' own max_claimed_chunks (config/ftbchunks-world.snbt) must equal claimsBase.")
            .define("levelClaimsEnabled", true);
        CLAIMS_BASE = b.comment("Claims at level 1 (= FTB Chunks max_claimed_chunks).")
            .defineInRange("claimsBase", 5, 0, 100_000);
        CLAIMS_PER_LEVEL = b.comment("Extra claims per level above 1.")
            .defineInRange("claimsPerLevel", 2, 0, 100_000);
        CLAIMS_MAX = b.comment("Hard cap per person (owner: 150, 2026-10-04; was 50). Teams add their members'",
                               "allowances together (FTB party_limit_mode = \"sum\"), capped by FTB's hard_team_claim_limit.")
            .defineInRange("claimsMax", 150, 0, 100_000);
        LEVEL_MILESTONE_EVERY = b.comment("Every Nth level also mails levelMilestoneItems. 0 = never.")
            .defineInRange("levelMilestoneEvery", 5, 0, 1000);
        LEVEL_MILESTONE_ITEMS = b
            .comment("Items for milestone levels, 'namespace:item*count' (e.g. 'minecraft:diamond*4').")
            .defineListAllowEmpty("levelMilestoneItems",
                java.util.List.of("minecraft:diamond*4", "minecraft:experience_bottle*16"),
                () -> "minecraft:diamond*1",
                o -> o instanceof String s && !s.isBlank());
        OPS_GET_ALL_ADVANCEMENTS = b
            .comment("Grant EVERY advancement to permission-4 players when they join (staff sit at the top of",
                     "an achievement-based ladder rather than polluting it). ⚠ Fires every advancement reward",
                     "once, recipe unlocks included. SMP role only.")
            .define("opsGetAllAdvancements", false);
        b.pop();

        b.comment("/mail — a server-side mailbox GUI (mail/). Rewards, admin mail, season welcome.").push("mail");
        MAIL_ENABLED = b
            .comment("Master switch for /mail, the join notice and system mail. Needs MySQL. SMP role only;",
                     "the lobby just reports the unread count.")
            .define("mailEnabled", false);
        MAIL_EXPIRY_DAYS = b.comment("Unclaimed mail is deleted after this many days. 0 = never.")
            .defineInRange("mailExpiryDays", 60, 0, 3650);
        SEASON_WELCOME_MAIL = b
            .comment("Deliver the starter spurs (startupBonusSpurs) plus seasonWelcomeItems as a welcome mail",
                     "instead of dropping them straight into the inventory.")
            .define("seasonWelcomeMail", true);
        SEASON_WELCOME_ITEMS = b
            .comment("Extra items in the welcome mail, 'namespace:item*count'.")
            .defineListAllowEmpty("seasonWelcomeItems",
                java.util.List.of("minecraft:bread*16", "minecraft:oak_sapling*4"),
                () -> "minecraft:bread*1",
                o -> o instanceof String s && !s.isBlank());
        b.pop();

        b.comment("Survival teleportation commands provided by this mod.").push("teleport");
        SURVIVAL_TELEPORT_COMMANDS = b
            .comment("false = /tpa, /tpaccept, /tpdeny and /rtp disappear for players (ops keep them). /spawn is",
                     "unaffected. DEFAULT false: Season 3 has no survival teleportation (owner, 2026-10-04) — and a",
                     "default, unlike a hand-added key, cannot be stripped by an older jar's config correction.",
                     "FTB Essentials' own /tpa /home /back /rtp /warp /playerspawn are off in config/ftbessentials.snbt.")
            .define("survivalTeleportCommands", false);
        b.pop();

        b.comment("Season launch: the one-time 'fresh start' for accounts used during the staff test (launch/LaunchReset).").push("launch");
        LAUNCH_RESET = b
            .comment("Flip to true ONCE, at launch. On the next boot every account in launchResetAccounts is reset to",
                     "a brand-new player (inventory, ender chest, position, XP, advancements, stats, personal quest",
                     "progress, homes) and, if launchResetDeop, de-opped. A stamp file in the world then records that it",
                     "ran, so leaving this true afterwards does NOTHING. Files are MOVED to a backup folder, never deleted.",
                     "FTB teams and claims are NOT touched (spawn stays protected). The world itself is never touched.")
            .define("launchReset", false);
        LAUNCH_RESET_ACCOUNTS = b
            .comment("Account names to reset (case-insensitive; resolved through usercache.json, i.e. accounts that",
                     "actually joined during the test). Never hard-coded: the owner fills this in before launch.")
            .defineListAllowEmpty("launchResetAccounts",
                java.util.List.<String>of(),
                () -> "PlayerName",
                o -> o instanceof String s && !s.isBlank());
        LAUNCH_RESET_DEOP = b
            .comment("Also remove op from those accounts (they become ordinary survival players).")
            .define("launchResetDeop", true);
        b.pop();

        SERVER_SPEC = b.build();
    }
}
