package com.coffeesaerosmp.auth.admin;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * "Next time they join, put them at the world spawn" — for {@code /authmod resetpos} on an OFFLINE player.
 *
 * <p>Clearing the auth mod's return position is not enough on its own: vanilla loads the player's position
 * from {@code playerdata/<uuid>.dat} on login, so they reappeared exactly where they were (owner,
 * 2026-10-04). Editing that file offline is fiddly and racy; this records the uuid instead, and the join
 * handler teleports them to the world spawn (the configured S3 spawn) and forgets them.
 *
 * <p>Persisted in {@code <world>/coffeesaeroauth/spawn-on-next-join.txt} so a restart in between does not
 * lose it. Server thread only.
 */
public final class SpawnOnNextJoin {

    private static final String FILE = "spawn-on-next-join.txt";

    private SpawnOnNextJoin() {}

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("coffeesaeroauth").resolve(FILE);
    }

    private static Set<String> read(MinecraftServer server) {
        Set<String> out = new LinkedHashSet<>();
        try {
            Path f = file(server);
            if (Files.exists(f)) {
                for (String l : Files.readAllLines(f)) if (!l.isBlank()) out.add(l.trim());
            }
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[ResetPos] could not read {}: {}", FILE, e.toString());
        }
        return out;
    }

    private static void write(MinecraftServer server, Set<String> ids) {
        try {
            Path f = file(server);
            Files.createDirectories(f.getParent());
            if (ids.isEmpty()) Files.deleteIfExists(f);
            else Files.write(f, ids);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[ResetPos] could not write {}: {}", FILE, e.toString());
        }
    }

    public static void mark(MinecraftServer server, UUID id) {
        Set<String> ids = read(server);
        if (ids.add(id.toString())) write(server, ids);
    }

    /** Join hook: if marked, move them to the world spawn (one task later — never from inside the event). */
    public static void onJoin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Set<String> ids = read(server);
        if (!ids.remove(player.getUUID().toString())) return;
        write(server, ids);
        server.execute(() -> {
            if (player.hasDisconnected() || CoffeesAeroAuth.LOBBY_MANAGER == null) return;
            CoffeesAeroAuth.LOBBY_MANAGER.teleportToSpawn(player);
            player.sendSystemMessage(Component.literal("§eAn admin reset your position — you are at spawn."));
            CoffeesAeroAuth.LOGGER.info("[ResetPos] {} placed at the world spawn (reset while offline).",
                player.getGameProfile().getName());
        });
    }
}
