package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.mojang.serialization.MapCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Datapack condition {@code coffees_aero_auth:lobby_server}: true only on the LOBBY server.
 *
 * <p>Gates {@code data/coffees_aero_auth/dimension/auth_lobby.json} and its dimension type. The SMP
 * retired {@code auth_lobby} in 1.13.3 but kept registering it, so every SMP world grew an unused void
 * dimension (Season 3's appeared on its first boot, 2026-10-04). Gating the dimension TYPE too matters:
 * {@code level.dat} remembers every dimension it has seen, and with the type still registered that entry
 * would decode and bring the dimension straight back.
 *
 * <h2>Why it reads the TOML file instead of {@code AuthConfig}</h2>
 * Dimensions are loaded with the world's datapacks, BEFORE NeoForge loads SERVER configs, so
 * {@code AuthConfig.SERVER_ROLE.get()} would throw here. The file is read directly.
 *
 * <h2>Failure direction</h2>
 * No config file (a brand-new server) = the default role, SMP = not registered. A file that exists but
 * cannot be read = REGISTERED: on the real lobby, losing its dimension for a boot is far worse than an
 * SMP keeping an empty one.
 */
public final class LobbyDimensionCondition implements ICondition {

    public static final LobbyDimensionCondition INSTANCE = new LobbyDimensionCondition();
    public static final MapCodec<LobbyDimensionCondition> CODEC = MapCodec.unit(INSTANCE);
    private static final Pattern ROLE = Pattern.compile("(?m)^\\s*serverRole\\s*=\\s*\"(\\w+)\"");

    private static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS =
        DeferredRegister.create(NeoForgeRegistries.Keys.CONDITION_CODECS, CoffeesAeroAuth.MOD_ID);

    static {
        CONDITIONS.register("lobby_server", () -> CODEC);
    }

    private LobbyDimensionCondition() {}

    public static void register(IEventBus modBus) {
        CONDITIONS.register(modBus);
    }

    @Override
    public boolean test(IContext context) {
        return isLobbyServer(FMLPaths.CONFIGDIR.get().resolve("coffees_aero_auth-server.toml"));
    }

    static boolean isLobbyServer(Path toml) {
        if (!Files.exists(toml)) return false;
        try {
            Matcher m = ROLE.matcher(Files.readString(toml));
            boolean lobby = m.find() && m.group(1).equalsIgnoreCase("LOBBY");
            CoffeesAeroAuth.LOGGER.info("[Lobby] auth_lobby dimension {} (serverRole from {}).",
                lobby ? "REGISTERED" : "not registered", toml.getFileName());
            return lobby;
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Lobby] Could not read {} ({}) — registering auth_lobby to be safe.",
                toml, e.getMessage());
            return true;
        }
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
