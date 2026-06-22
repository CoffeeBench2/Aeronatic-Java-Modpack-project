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

    public CoffeesAeroAuth(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, AuthConfig.SERVER_SPEC);

        // Networking: receive premium/cracked tag from the AeroVelocity proxy (mod bus event)
        modBus.addListener(AeroNetworking::register);

        // Server lifecycle
        NeoForge.EVENT_BUS.addListener(CoffeesAeroAuth::onServerStarting);
        NeoForge.EVENT_BUS.addListener(CoffeesAeroAuth::onServerStopping);
        NeoForge.EVENT_BUS.addListener(CoffeesAeroAuth::onRegisterCommands);

        // Auth: join / leave
        NeoForge.EVENT_BUS.addListener(PlayerAuthEvents::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(PlayerAuthEvents::onPlayerLeave);

        // Restrictions: movement, interaction, inventory
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onLivingTick);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onRightClickBlock);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onRightClickItem);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onEntityInteract);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onLeftClickBlock);
        NeoForge.EVENT_BUS.addListener(PlayerRestrictEvents::onAttackEntity);

        // Chat formatting + Discord bridge
        NeoForge.EVENT_BUS.addListener(ChatEvents::onServerChat);

        // Watchdog: command velocity, movement, death, advancements
        NeoForge.EVENT_BUS.addListener(WatchdogEvents::onCommand);
        NeoForge.EVENT_BUS.addListener(WatchdogEvents::onLivingTick);
        NeoForge.EVENT_BUS.addListener(WatchdogEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(WatchdogEvents::onAdvancement);
    }

    private static void onServerStarting(ServerStartingEvent event) {
        Path dataDir = event.getServer().getWorldPath(LevelResource.ROOT).resolve("coffeesaeroauth");
        Map<String, String> env = EnvLoader.load();
        AeroNetworking.initSecret(env);

        // ── Database ──────────────────────────────────────────────────────────
        DB_MANAGER = new DatabaseManager();
        DB_MANAGER.initialize(env);
        if (DB_MANAGER.isAvailable()) {
            DB_MANAGER.createSchema();
            MigrationRunner.runIfNeeded(DB_MANAGER, dataDir);
        }
        DB_MANAGER.startHealthCheck();

        // ── Core auth stack ───────────────────────────────────────────────────
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
            (author, msg) -> { if (DISCORD_BRIDGE != null) DISCORD_BRIDGE.onDiscordMessage(event.getServer(), author, msg); },
            AuthConfig.DISCORD_ADMIN_CHANNEL_ID.get(),
            AuthConfig.DISCORD_ADMIN_ROLE_ID.get(),
            (author, msg) -> { if (ADMIN_CONSOLE != null) ADMIN_CONSOLE.runCommand(author, msg); },
            interactions::handle
        );
        DISCORD_BRIDGE = new DiscordBridge(WEBHOOK_QUEUE, gateway);
        if (AuthConfig.DISCORD_ENABLED.get() && !discordToken.isBlank()) {
            DISCORD_BRIDGE.startGateway();
            ADMIN_CONSOLE.startMirror();
        }

        WATCHDOG = new WatchdogManager(event.getServer(), ipBans, trustedIps, auditLog, watchdogLog, WEBHOOK_QUEUE);
        WATCHDOG.start(PROFILE_STORE);

        // ── Private room + name approval ──────────────────────────────────────
        ROOM_MANAGER   = new PrivateRoomManager(event.getServer());
        APPROVAL_QUEUE = new NameApprovalQueue(PROFILE_STORE, WEBHOOK_QUEUE, DISCORD_REST, event.getServer(), ROOM_MANAGER);
        ROOM_MANAGER.runStartupCleanup(PROFILE_STORE);

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
        // Obsidian first — needs AUTH_MANAGER + WATCHDOG to write devlog
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
        if (PROFILE_STORE != null)  { PROFILE_STORE.shutdown(); PROFILE_STORE = null; }
        if (DB_MANAGER != null)     { DB_MANAGER.shutdown();    DB_MANAGER = null; }
        DISPLAY_NAMES = null;
        AUTH_MANAGER  = null;
        ROOM_MANAGER  = null;
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        AuthCommands.register(event.getDispatcher());
        ProfileCommands.register(event.getDispatcher());
    }
}
