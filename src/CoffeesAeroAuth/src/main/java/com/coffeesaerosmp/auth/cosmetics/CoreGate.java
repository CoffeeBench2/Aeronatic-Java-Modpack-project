package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.network.CoreHelloPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The AeroCore handshake (1.14.0, owner 2026-10-13: "make CoffeesAeroCore compulsory"). Core sends
 * {@code aerosmp:core_hello} with its version as soon as it joins. With {@code requireAeroCore} on:
 * <ul>
 *   <li>a client that never registered the channel (no Core, or a Core older than the handshake) is refused at
 *       login, before it is ever in the world;</li>
 *   <li>a client with the channel that says hello with a version below {@code minAeroCoreVersion}, or never says
 *       hello within the grace period, is disconnected with the same instructions.</li>
 * </ul>
 * With it off, nothing is refused: the versions are only recorded ({@code /aerostore core}) so the owner can see
 * how many players already run the new Core before switching it on. Same jar on the lobby and the SMP, so both
 * gate.
 */
public final class CoreGate {

    private CoreGate() {}

    private static final Map<UUID, String> VERSIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> JOINED_MS = new ConcurrentHashMap<>();

    /** The version this player's Core reported, or null (none yet / no Core). */
    public static String version(UUID id) { return VERSIONS.get(id); }

    /** Does this player's client draw cosmetics itself (has Core with the cosmetics channel)? */
    public static boolean rendersLocally(ServerPlayer p) { return CosmeticSync.supported(p); }

    /**
     * Mod bus: refuse a client without the channel DURING CONFIGURATION, before it is in the world, so no
     * "joined the game" / "left the game" pair is ever broadcast for a player we turn away. (The version check
     * still happens after the hello, in play: a too-old Core is rare once the pack has updated.)
     */
    public static void onConfigure(net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent e) {
        var listener = e.getListener();
        e.register(new net.neoforged.neoforge.network.configuration.ICustomConfigurationTask() {
            @Override
            public void run(java.util.function.Consumer<net.minecraft.network.protocol.common.custom.CustomPacketPayload> sender) {
                if (required() && !listener.hasChannel(CoreHelloPayload.TYPE)) {
                    CoffeesAeroAuth.LOGGER.info("[CoreGate] refused a client without AeroCore during configuration");
                    listener.disconnect(message(null));
                    return;
                }
                listener.finishCurrentTask(TASK);
            }

            @Override
            public Type type() { return TASK; }
        });
    }

    private static final net.minecraft.server.network.ConfigurationTask.Type TASK =
        new net.minecraft.server.network.ConfigurationTask.Type("coffees_aero_auth:core_gate");

    public static void onLogin(ServerPlayer p) {
        JOINED_MS.put(p.getUUID(), System.currentTimeMillis());
        if (!required()) return;
        if (!p.connection.hasChannel(CoreHelloPayload.TYPE)) refuse(p, null);
    }

    public static void onLogout(UUID id) {
        VERSIONS.remove(id);
        JOINED_MS.remove(id);
    }

    public static void onHello(ServerPlayer p, String version) {
        String v = version == null ? "" : version.trim();
        VERSIONS.put(p.getUUID(), v);
        CoffeesAeroAuth.LOGGER.info("[CoreGate] {} runs AeroCore {}", p.getGameProfile().getName(), v);
        if (required() && !atLeast(v, minVersion())) refuse(p, v);
    }

    /** 1 Hz: players with the channel who never said hello within the grace period. */
    public static void tick(MinecraftServer server) {
        if (!required()) return;
        long grace = graceSeconds() * 1000L, now = System.currentTimeMillis();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (VERSIONS.containsKey(p.getUUID())) continue;
            Long at = JOINED_MS.get(p.getUUID());
            if (at != null && now - at > grace) refuse(p, null);
        }
    }

    /** Counts for /aerostore core: {with a known version, online}. */
    public static String summary(MinecraftServer server) {
        int online = server.getPlayerList().getPlayerCount();
        Map<String, Integer> byVersion = new java.util.TreeMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String v = VERSIONS.get(p.getUUID());
            byVersion.merge(v == null ? (p.connection.hasChannel(CoreHelloPayload.TYPE) ? "pending" : "none") : v, 1, Integer::sum);
        }
        return "requireAeroCore=" + required() + " min=" + minVersion() + " · " + online + " online · " + byVersion;
    }

    private static void refuse(ServerPlayer p, String had) {
        CoffeesAeroAuth.LOGGER.info("[CoreGate] refused {}: {}", p.getGameProfile().getName(),
            had == null ? "no AeroCore" : "AeroCore " + had + " < " + minVersion());
        p.connection.disconnect(message(had));
    }

    /** The disconnect screen: what is wrong and exactly how to fix it. */
    private static Component message(String had) {
        String why = had == null
            ? "§7This server needs §fAeroCore§7, which comes with the official modpack."
            : "§7Your §fAeroCore " + had + "§7 is out of date. This server needs §f" + minVersion() + "§7 or newer.";
        return Component.literal("§6☕ Coffee's Aero SMP\n\n" + why
            + "\n\n§7Update the modpack in your launcher (Modrinth or CurseForge:\n§f\"Coffee's Create: Aeronautics SMP\"§7), then join again.");
    }

    static boolean required() {
        try { return AuthConfig.REQUIRE_AEROCORE.get(); } catch (Throwable t) { return false; }
    }

    private static String minVersion() {
        try { return AuthConfig.MIN_AEROCORE_VERSION.get(); } catch (Throwable t) { return "1.4.0"; }
    }

    private static int graceSeconds() {
        try { return AuthConfig.AEROCORE_HELLO_GRACE_SECONDS.get(); } catch (Throwable t) { return 15; }
    }

    public static boolean atLeast(String have, String need) { return CoreVersions.atLeast(have, need); }
}
