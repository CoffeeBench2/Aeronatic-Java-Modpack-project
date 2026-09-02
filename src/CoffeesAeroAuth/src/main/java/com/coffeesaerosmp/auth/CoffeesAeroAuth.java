package com.coffeesaerosmp.auth;

import com.coffeesaerosmp.auth.auth.AuthManager;
import com.coffeesaerosmp.auth.commands.AuthCommands;
import com.coffeesaerosmp.auth.commands.ProfileCommands;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.DatabaseManager;
import com.coffeesaerosmp.auth.db.MigrationRunner;
import com.coffeesaerosmp.auth.db.ProfileStore;
import com.coffeesaerosmp.auth.discord.DiscordBridge;
import com.coffeesaerosmp.auth.discord.DiscordGateway;
import com.coffeesaerosmp.auth.discord.WebhookQueue;
import com.coffeesaerosmp.auth.lobby.NameApprovalQueue;
import com.coffeesaerosmp.auth.lobby.PrivateRoomManager;
import com.coffeesaerosmp.auth.obsidian.ObsidianClient;
import com.coffeesaerosmp.auth.obsidian.ObsidianExporter;
import com.coffeesaerosmp.auth.util.EnvLoader;
import com.coffeesaerosmp.auth.events.ChatEvents;
import com.coffeesaerosmp.auth.events.PlayerAuthEvents;
import com.coffeesaerosmp.auth.events.PlayerRestrictEvents;
import com.coffeesaerosmp.auth.events.WatchdogEvents;
import com.coffeesaerosmp.auth.network.AeroNetworking;
import com.coffeesaerosmp.auth.profile.DisplayNameManager;
import com.coffeesaerosmp.auth.watchdog.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;

@Mod(CoffeesAeroAuth.MOD_ID)
public class CoffeesAeroAuth {

    public static final String MOD_ID = "coffees_aero_auth";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    // Initialized on ServerStartingEvent, cleared on ServerStoppingEvent
    public static volatile DatabaseManager    DB_MANAGER;
    public static volatile ProfileStore       PROFILE_STORE;
    public static volatile DisplayNameManager DISPLAY_NAMES;
    public static volatile AuthManager        AUTH_MANAGER;
    public static volatile WatchdogManager    WATCHDOG;
    public static volatile DiscordBridge      DISCORD_BRIDGE;
    public static volatile com.coffeesaerosmp.auth.discord.DiscordRest DISCORD_REST;
    public static volatile com.coffeesaerosmp.auth.discord.AdminConsoleBridge ADMIN_CONSOLE;
    public static volatile WebhookQueue       WEBHOOK_QUEUE;
    public static volatile ObsidianExporter   OBSIDIAN_EXPORTER;
    public static volatile PrivateRoomManager ROOM_MANAGER;
    public static volatile NameApprovalQueue  APPROVAL_QUEUE;
    public static volatile com.coffeesaerosmp.auth.lobby.LobbyInventoryStash LOBBY_STASH;
    public static volatile com.coffeesaerosmp.auth.daily.DailyRewardManager DAILY_REWARDS;
    public static volatile com.coffeesaerosmp.auth.vote.VoteRewards VOTE_REWARDS;

    /** Cookie key the gate sets on the client and we read here (matches AeroGate's aerosmp:auth). */
    public static final net.minecraft.resources.ResourceLocation AUTH_COOKIE_KEY =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("aerosmp", "auth");
    /** Verifier for gate-signed auth cookies — disabled until AERO_GATE_SECRET is set in .env. */
    public static volatile com.coffeesaerosmp.auth.auth.CookieAuth COOKIE_AUTH;

    public CoffeesAeroAuth(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, AuthConfig.SERVER_SPEC);

        // Networking: receive premium/cracked tag from the AeroVelocity proxy (mod bus event)
        modBus.addListener(AeroNetworking::register);

        // Global Loot Modifier codecs. The TYPE must be registered in code; the RULES stay in a
        // datapack (coffees_aero_auth:loot_modifiers/rarity_nerf.json) so they are /reload-tunable.
        com.coffeesaerosmp.auth.loot.AeroLootModifiers.register(modBus);

        // Server lifecycle
        NeoForge.EVENT_BUS.addListener(CoffeesAeroAuth::onServerStarting);
        NeoForge.EVENT_BUS.addListener(CoffeesAeroAuth::onServerStopping);
        NeoForge.EVENT_BUS.addListener(CoffeesAeroAuth::onRegisterCommands);

        // Auth: join / leave
        NeoForge.EVENT_BUS.addListener(PlayerAuthEvents::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(PlayerAuthEvents::onPlayerLeave);

        // Daily: /daily is Overworld-only, so a nudge withheld in the lobby is delivered on the
        // dimension change into the world instead of being dropped.
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent e) -> {
                if (DAILY_REWARDS != null) DAILY_REWARDS.onChangedDimension(e);
            });
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) -> {
                if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
                    if (DAILY_REWARDS != null) DAILY_REWARDS.onPlayerLeave(sp);
                    // Drop cached sidebar rows + advancement count so nothing leaks across sessions.
                    com.coffeesaerosmp.auth.sidebar.SidebarManager.onPlayerLogout(sp);
                    com.coffeesaerosmp.auth.afk.AfkTracker.onPlayerLogout(sp);
                    com.coffeesaerosmp.auth.events.ChatEvents.onPlayerLogout(sp);
                }
            });

        // Skins: owned by the CoffeesAeroSkins mod since 1.6.0 — auth just plugs in its profile
        // store as storage/policy backend and forwards gate-verified premium UUIDs (SkinsHook).
        com.coffeesaerosmp.auth.compat.SkinsHook.install();

        // Restrictions: movement, interaction, inventory
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onLivingTick);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onAttackEntity);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onItemToss);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onItemPickup);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onLobbyDeath);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onLobbyRespawn);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onLobbyCommand);



        // Tick heartbeat for the stall watchdog. Registered FIRST among the tick listeners and kept
        // to a single volatile write: this is the one handler that must never itself be the reason
        // a tick is slow, and it must run even if a later listener throws.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.watchdog.StallWatchdog.onServerTick(e.getServer()));

        // Lag attribution. Pre stamps the tick's start so the sampler thread can tell a tick is
        // overrunning WHILE it overruns — the only moment its stack is worth capturing.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e) ->
            com.coffeesaerosmp.auth.watchdog.LagAttributor.onTickPre(e.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.watchdog.LagAttributor.onTickPost(e.getServer()));

        // True MSPT. Pre->Post is the tick's WORK time, which is what spark reports; the Post->Post
        // interval LagMonitor uses floors at ~50ms whenever the server keeps up, so it can never
        // agree with spark. Both are kept: interval drives TPS, work time drives MSPT.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre e) ->
            com.coffeesaerosmp.auth.watchdog.TickStats.onTickPre());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.watchdog.TickStats.onTickPost());

        // Ops' live TPS/MSPT boss bar. Throttles itself to ~2 updates/sec.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.display.TpsHud.onServerTick(e.getServer()));

        // Ground-item clear with a countdown. Throttles itself to once per second.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.world.ItemClearer.onServerTick(e.getServer()));

        // Animated tab-list header/footer (airship + live pilot count + rotating tips).
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.tablist.TabListManager.onServerTick(e.getServer()));

        // Right-hand status sidebar (per-player packets; rebuilt 1/s, diff-only sends).
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.sidebar.SidebarManager.onServerTick(e.getServer()));
        // Keeps the sidebar's advancement count current without rescanning every advancement.
        NeoForge.EVENT_BUS.addListener(
            com.coffeesaerosmp.auth.sidebar.SidebarManager::onAdvancementEarned);

        // Idle timer — pauses playtime accrual while a player is AFK, so the playtime-derived
        // level can't be farmed by standing still. Rolls sessionStartEpoch forward rather than
        // accumulating a subtraction, so every consumer of playtime is corrected at once.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.afk.AfkTracker.onServerTick(e.getServer()));
        // Deliberate actions that count as activity. Movement and looking around are picked up by
        // the tracker's own tick, so these are only the things a player can do while standing
        // perfectly still. ⚠ Damage taken and items picked up are NOT here on purpose — an AFK
        // player parked in a mob farm generates both continuously, which is the exact case this
        // whole feature exists to catch.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.ServerChatEvent e) ->
            com.coffeesaerosmp.auth.afk.AfkTracker.touch(e.getPlayer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.CommandEvent e) -> {
            // Fires for the console and command blocks too, so the source must really be a player.
            if (e.getParseResults().getContext().getSource().getEntity()
                    instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.coffeesaerosmp.auth.afk.AfkTracker.touch(sp);
            }
        });
        // BreakEvent is not a PlayerEvent, so it needs its own unwrap rather than onPlayerActivity.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) -> {
            if (e.getPlayer() instanceof net.minecraft.server.level.ServerPlayer sp) {
                com.coffeesaerosmp.auth.afk.AfkTracker.touch(sp);
            }
        });
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) ->
                com.coffeesaerosmp.auth.afk.AfkTracker.onPlayerActivity(e));
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem e) ->
                com.coffeesaerosmp.auth.afk.AfkTracker.onPlayerActivity(e));
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract e) ->
                com.coffeesaerosmp.auth.afk.AfkTracker.onPlayerActivity(e));
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.entity.player.AttackEntityEvent e) ->
                com.coffeesaerosmp.auth.afk.AfkTracker.onPlayerActivity(e));
        NeoForge.EVENT_BUS.addListener(
            (net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open e) ->
                com.coffeesaerosmp.auth.afk.AfkTracker.onPlayerActivity(e));

        // Periodic "the watchdog is watching" reminder + bark. Random interval, world chat only.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.watchdog.WatchdogTaunts.onServerTick(e.getServer()));

        // Restart countdown boss bar — redraws itself; no-op when no countdown is running.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.util.RestartWarning.onServerTick(e.getServer()));

        // Vote reminder: tells a player the moment their vote cooldown elapses (throttled to ~30s
        // internally, and once per cooldown rather than once per sweep).
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
            if (VOTE_REWARDS != null) VOTE_REWARDS.onServerTick(e.getServer());
        });

        // /rtp async pregen driver (progress action bar, teleport-when-ready, timeouts).
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.commands.RtpCommand.onServerTick(e.getServer()));

        // Plot-space sweep (1Hz internally). The anti-crash-loop half runs from the ServerPlayer
        // mixin at load time; this is the mid-session net. See PlotGuard for the 2026-08-04 crash.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.protect.PlotGuard.onServerTick(e.getServer()));

        // Periodic saves (1Hz internally). Sable's native Rapier panic calls abort(), which kills
        // the JVM with no shutdown and no world save — see SaveGuard for the 2026-08-08 data loss.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.protect.SaveGuard.onServerTick(e.getServer()));

        // Sustained-lag warning, so players stop blaming their own connection. Deliberately hard to
        // trigger — see LagMonitor for why warning on this pack's routine spikes would be worse.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) ->
            com.coffeesaerosmp.auth.watchdog.LagMonitor.onServerTick(e.getServer()));

        // Chat formatting + Discord bridge
        NeoForge.EVENT_BUS.addListener(ChatEvents::onServerChat);

        // Watchdog: command velocity, movement, death, advancements
        NeoForge.EVENT_BUS.addListener(WatchdogEvents::onCommand);
        NeoForge.EVENT_BUS.addListener(WatchdogEvents::onLivingTick);
        NeoForge.EVENT_BUS.addListener(WatchdogEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(WatchdogEvents::onAdvancement);

        // Rail anti-grief: auto-claim chunks where players build Create rail (no-op without FTB Chunks).
        NeoForge.EVENT_BUS.addListener(com.coffeesaerosmp.auth.protect.RailAutoClaim::onBlockPlace);
        NeoForge.EVENT_BUS.addListener(com.coffeesaerosmp.auth.protect.RailAutoClaim::onRightClickBlock);

        // PvP combat guard: tag on player-vs-player damage, block escape commands while tagged,
        // punish combat-logging, and track deaths for the /back death-return.
        NeoForge.EVENT_BUS.addListener(com.coffeesaerosmp.auth.pvp.CombatGuard::onLivingDamage);
        NeoForge.EVENT_BUS.addListener(com.coffeesaerosmp.auth.pvp.CombatGuard::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(com.coffeesaerosmp.auth.pvp.CombatGuard::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(com.coffeesaerosmp.auth.pvp.CombatGuard::onCommand);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        Path dataDir = event.getServer().getWorldPath(LevelResource.ROOT).resolve("coffeesaeroauth");
        Map<String, String> env = EnvLoader.load();
        AeroNetworking.initSecret(env);

        // Start the save clocks from boot, so the first periodic save lands one interval from now
        // rather than immediately (a join burst is the worst moment to add disk I/O).
        com.coffeesaerosmp.auth.protect.SaveGuard.onServerStarted();

        // Gate cookie verifier — shared HMAC secret with the Velocity AeroGate plugin (secret.txt).
        COOKIE_AUTH = new com.coffeesaerosmp.auth.auth.CookieAuth(
            com.coffeesaerosmp.auth.auth.CookieAuth.hexToBytes(env.getOrDefault("AERO_GATE_SECRET", "")));
        LOGGER.info("[Gate] Cookie auth {} ({}).",
            COOKIE_AUTH.enabled() ? "ENABLED" : "DISABLED",
            COOKIE_AUTH.enabled() ? "AERO_GATE_SECRET loaded" : "AERO_GATE_SECRET missing — gate logins resolve OFFLINE");

        // ── Database ──────────────────────────────────────────────────────────
        DB_MANAGER = new DatabaseManager();
        DB_MANAGER.initialize(env);
        if (DB_MANAGER.isAvailable()) {
            DB_MANAGER.createSchema();
            MigrationRunner.runIfNeeded(DB_MANAGER, dataDir);
            // Season/world-reset migration. MUST run before ProfileStore.initialize() below:
            // the store is cache-first, so if it loaded first the in-memory copies would still
            // hold Season 1 return positions and write them straight back over this.
            com.coffeesaerosmp.auth.db.SeasonMigration.run(DB_MANAGER);
        }
        DB_MANAGER.startHealthCheck();

        // ── Core auth stack ───────────────────────────────────────────────────
        // Load before anyone can join, so the first player of the session sees the banner.
        com.coffeesaerosmp.auth.util.TestingMode.initialize(dataDir);
        com.coffeesaerosmp.auth.chat.ChatFilter.initialize(dataDir);

        PROFILE_STORE = new ProfileStore(dataDir, DB_MANAGER);
        PROFILE_STORE.initialize();
        DISPLAY_NAMES = new DisplayNameManager(PROFILE_STORE);
        AUTH_MANAGER  = new AuthManager(PROFILE_STORE, DISPLAY_NAMES, DB_MANAGER);

        // ── Watchdog stack ────────────────────────────────────────────────────
        IpBanManager   ipBans     = new IpBanManager(DB_MANAGER);
        ipBans.initialize();
        TrustedIpStore trustedIps = new TrustedIpStore(dataDir, DB_MANAGER);
        trustedIps.initialize();
        AuditLogger    auditLog   = new AuditLogger("audit",    dataDir);
        AuditLogger    watchdogLog = new AuditLogger("watchdog", dataDir);

        // ── Discord stack ─────────────────────────────────────────────────────
        WEBHOOK_QUEUE = new WebhookQueue();
        if (AuthConfig.DISCORD_ENABLED.get()) {
            WEBHOOK_QUEUE.start();
        }
        // Bot token: prefer .env (secrets rule), fall back to config.
        String discordToken = env.getOrDefault("DISCORD_BOT_TOKEN", AuthConfig.DISCORD_BOT_TOKEN.get());
        DISCORD_REST = new com.coffeesaerosmp.auth.discord.DiscordRest(discordToken);
        ADMIN_CONSOLE = new com.coffeesaerosmp.auth.discord.AdminConsoleBridge(
            event.getServer(), DISCORD_REST, AuthConfig.DISCORD_ADMIN_CHANNEL_ID.get());
        com.coffeesaerosmp.auth.discord.DiscordInteractions interactions =
            new com.coffeesaerosmp.auth.discord.DiscordInteractions(
                event.getServer(), DISCORD_REST, AuthConfig.DISCORD_ADMIN_ROLE_ID.get());
        DiscordGateway gateway = new DiscordGateway(
            event.getServer(),
            discordToken,
            AuthConfig.DISCORD_PUBLIC_CHANNEL_ID.get(),
            AuthConfig.DISCORD_TO_MC_ROLE_ID.get(),
            (authorId, author, msg) -> { if (DISCORD_BRIDGE != null) DISCORD_BRIDGE.onDiscordMessage(event.getServer(), authorId, author, msg); },
            AuthConfig.DISCORD_ADMIN_CHANNEL_ID.get(),
            AuthConfig.DISCORD_ADMIN_ROLE_ID.get(),
            (author, msg) -> { if (ADMIN_CONSOLE != null) ADMIN_CONSOLE.runCommand(author, msg); },
            interactions::handle
        );
        // Register /uptime + /link as global slash commands on every READY (bulk PUT — idempotent).
        gateway.setReadyCallback(() -> DISCORD_REST.setGlobalCommands(
            gateway.getApplicationId(),
            com.coffeesaerosmp.auth.discord.DiscordInteractions.GLOBAL_COMMANDS_JSON));
        DISCORD_BRIDGE = new DiscordBridge(WEBHOOK_QUEUE, gateway);
        DISCORD_BRIDGE.initMilestones(dataDir);
        if (AuthConfig.DISCORD_ENABLED.get() && !discordToken.isBlank()) {
            DISCORD_BRIDGE.startGateway();
            ADMIN_CONSOLE.startMirror();
        }

        WATCHDOG = new WatchdogManager(event.getServer(), ipBans, trustedIps, auditLog, watchdogLog, WEBHOOK_QUEUE, dataDir);
        WATCHDOG.start(PROFILE_STORE);

        // Hung-tick detector. Started AFTER WATCHDOG so its alerts have somewhere to go, and it
        // watches from its own thread so a wedged tick loop cannot stop it from noticing.
        com.coffeesaerosmp.auth.watchdog.StallWatchdog.start(event.getServer(), dataDir);
        com.coffeesaerosmp.auth.watchdog.LagAttributor.start(event.getServer());

        // ── Private room + name approval ──────────────────────────────────────
        ROOM_MANAGER   = new PrivateRoomManager(event.getServer());
        APPROVAL_QUEUE = new NameApprovalQueue(PROFILE_STORE, WEBHOOK_QUEUE, DISCORD_REST, event.getServer(), ROOM_MANAGER);
        ROOM_MANAGER.runStartupCleanup(PROFILE_STORE);

        // Disable vanilla's advancement chat broadcast (it uses the account username); our
        // WatchdogEvents.onAdvancement re-emits it with the display name instead.
        if (com.coffeesaerosmp.auth.config.AuthConfig.MASK_ADVANCEMENT_NAMES.get()) {
            event.getServer().getGameRules()
                .getRule(net.minecraft.world.level.GameRules.RULE_ANNOUNCE_ADVANCEMENTS)
                .set(false, event.getServer());
        }
        LOBBY_STASH    = new com.coffeesaerosmp.auth.lobby.LobbyInventoryStash(dataDir);
        LOBBY_STASH.initialize();
        DAILY_REWARDS  = new com.coffeesaerosmp.auth.daily.DailyRewardManager(dataDir);
        VOTE_REWARDS   = new com.coffeesaerosmp.auth.vote.VoteRewards(dataDir);
        com.coffeesaerosmp.auth.clan.ClanTags.initialize(dataDir);
        com.coffeesaerosmp.auth.display.HiddenOps.initialize(dataDir);
        com.coffeesaerosmp.auth.display.TpsHud.initialize(dataDir);
        com.coffeesaerosmp.auth.world.ItemClearer.initialize();
        com.coffeesaerosmp.auth.commands.RtpCommand.initialize(dataDir);
        com.coffeesaerosmp.auth.commands.RtpAnchors.initialize(dataDir);
        com.coffeesaerosmp.auth.auth.WelcomeMessages.initialize(dataDir);
        com.coffeesaerosmp.auth.util.NameStyles.initialize(dataDir);

        // ── Obsidian stack ────────────────────────────────────────────────────
        if (AuthConfig.OBSIDIAN_ENABLED.get()) {
            String obsUrl = env.getOrDefault("OBSIDIAN_URL",     AuthConfig.OBSIDIAN_URL.get());
            String obsKey = env.getOrDefault("OBSIDIAN_API_KEY", AuthConfig.OBSIDIAN_API_KEY.get());
            if (!obsKey.isBlank()) {
                ObsidianClient obsClient = new ObsidianClient(obsUrl, obsKey);
                OBSIDIAN_EXPORTER = new ObsidianExporter(obsClient, PROFILE_STORE,
                    AuthConfig.OBSIDIAN_VAULT_PATH.get());
                if (obsClient.ping()) {
                    LOGGER.info("[Obsidian] Vault reachable at {}", obsUrl);
                } else {
                    LOGGER.warn("[Obsidian] Vault unreachable at {} — writes will be retried.", obsUrl);
                }
            } else {
                LOGGER.warn("[Obsidian] enabled=true but no API key found in .env or config — skipping.");
            }
        }

        LOGGER.info("CoffeesAeroAuth started. DB: {} | Data: {}",
            DB_MANAGER.getState(), dataDir);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        // FIRST LINE ON PURPOSE: from here on ticks stop legitimately, so the tick-stall rule must
        // give way to the shutdown deadline. Everything below this point is part of the shutdown
        // that is now being supervised — including the player-session close, which goes to MySQL in
        // Helsinki and is a prime suspect for the 2026-08-21 00:00 wedge.
        com.coffeesaerosmp.auth.watchdog.StallWatchdog.onServerStopping();
        // Close live sessions FIRST: playtime only accumulates in onPlayerLeave, so a panel
        // restart/stop with players online silently lost their whole session (playtime AND the
        // /spawn return-position). Idempotent — the natural PlayerLoggedOutEvent that follows
        // finds sessionStartEpoch already 0 and adds nothing twice.
        if (AUTH_MANAGER != null && event.getServer() != null) {
            for (net.minecraft.server.level.ServerPlayer p :
                    new java.util.ArrayList<>(event.getServer().getPlayerList().getPlayers())) {
                try { AUTH_MANAGER.onPlayerLeave(p); } catch (Exception ignored) {}
            }
        }
        // Obsidian next — needs AUTH_MANAGER + WATCHDOG to write devlog
        if (OBSIDIAN_EXPORTER != null) {
            OBSIDIAN_EXPORTER.onServerStop(AUTH_MANAGER, WATCHDOG);
            OBSIDIAN_EXPORTER = null;
        }
        if (APPROVAL_QUEUE != null) { APPROVAL_QUEUE.stop(); APPROVAL_QUEUE = null; }
        if (WATCHDOG != null)       { WATCHDOG.stop();       WATCHDOG = null; }
        if (ADMIN_CONSOLE != null)  { ADMIN_CONSOLE.stopMirror(); ADMIN_CONSOLE = null; }
        if (DISCORD_BRIDGE != null) { DISCORD_BRIDGE.stop(); DISCORD_BRIDGE = null; }
        DISCORD_REST = null;
        if (WEBHOOK_QUEUE != null)  { WEBHOOK_QUEUE.stop();  WEBHOOK_QUEUE = null; }
        // Drain queued background writes (profiles/audit/trusted-ips) BEFORE the DB pool closes.
        com.coffeesaerosmp.auth.util.AsyncIo.drain(10);
        if (PROFILE_STORE != null)  { PROFILE_STORE.shutdown(); PROFILE_STORE = null; }
        if (DB_MANAGER != null)     { DB_MANAGER.shutdown();    DB_MANAGER = null; }
        DISPLAY_NAMES = null;
        AUTH_MANAGER  = null;
        ROOM_MANAGER  = null;
        LOBBY_STASH   = null;
    }

    /**
     * Called on the server thread when a transferred client returns the gate's auth cookie
     * (see {@code ServerCommonCookieMixin}). Verifies it and resolves premium/offline. No / bad /
     * expired / replayed cookie → OFFLINE, never premium.
     */
    public static void handleAuthCookie(net.minecraft.server.level.ServerPlayer player, byte[] payload) {
        if (AUTH_MANAGER == null) return;
        String name = player.getGameProfile().getName();
        if (COOKIE_AUTH == null || !COOKIE_AUTH.enabled()) {
            AUTH_MANAGER.resolvePlayerType(player, false);   // no secret → cannot trust cookies
            return;
        }
        if (payload == null) {                               // direct connect / not via the gate
            if (resolveViaReconnectGrace(player, name, "no cookie")) return;
            AUTH_MANAGER.resolvePlayerType(player, false);
            return;
        }
        com.coffeesaerosmp.auth.auth.CookieAuth.Verified v = COOKIE_AUTH.verify(payload);
        if (v == null) {
            // A spent cookie is NORMAL on a direct reconnect (launcher auto-reconnect after a kick):
            // the nonce is single-use. Same name + same IP inside the grace window still counts as
            // the gate-verified premium player; everything else stays OFFLINE as before.
            if (resolveViaReconnectGrace(player, name, "cookie invalid/expired/replay")) return;
            LOGGER.warn("[Gate] Cookie REJECTED for {} (invalid/expired/replay) — treating as OFFLINE.", name);
            AUTH_MANAGER.resolvePlayerType(player, false);
            return;
        }
        LOGGER.info("[Gate] Cookie OK: {} -> {} (verified UUID {}).",
            name, v.premium() ? "PREMIUM" : "OFFLINE", v.uuid());
        if (v.premium()) {
            com.coffeesaerosmp.auth.auth.PremiumReconnectGrace.record(
                name, v.uuid(), com.coffeesaerosmp.auth.util.NetUtil.getPlayerIP(player));
        }
        AUTH_MANAGER.resolvePlayerType(player, v.premium());
        // Premium: show their REAL Mojang skin (fetched by the gate-verified UUID) on this offline server.
        if (v.premium()) com.coffeesaerosmp.auth.compat.SkinsHook.applyPremium(player, v.uuid());
    }

    /** Premium reconnect grace: true if the player was resolved PREMIUM from a recent same-IP session. */
    private static boolean resolveViaReconnectGrace(net.minecraft.server.level.ServerPlayer player,
                                                    String name, String why) {
        String ip = com.coffeesaerosmp.auth.util.NetUtil.getPlayerIP(player);
        java.util.UUID mojangUuid = com.coffeesaerosmp.auth.auth.PremiumReconnectGrace.check(name, ip);
        if (mojangUuid == null) return false;
        LOGGER.info("[Gate] Reconnect grace: {} ({}) re-resolved PREMIUM — same IP within the grace window.",
            name, why);
        com.coffeesaerosmp.auth.auth.PremiumReconnectGrace.record(name, mojangUuid, ip);   // refresh
        AUTH_MANAGER.resolvePlayerType(player, true);
        com.coffeesaerosmp.auth.compat.SkinsHook.applyPremium(player, mojangUuid);
        return true;
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommands.register(event.getDispatcher());
        ProfileCommands.register(event.getDispatcher());
        com.coffeesaerosmp.auth.pvp.CombatGuard.registerCommands(event.getDispatcher());
        com.coffeesaerosmp.auth.commands.TpaCommands.register(event.getDispatcher());
        com.coffeesaerosmp.auth.commands.RtpCommand.register(event.getDispatcher());
        com.coffeesaerosmp.auth.commands.ShipNameCommand.register(event.getDispatcher());
        com.coffeesaerosmp.auth.commands.NameColorCommands.register(event.getDispatcher());
    }
}
