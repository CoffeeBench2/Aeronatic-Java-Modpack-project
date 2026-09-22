package com.coffeesaerosmp.auth.tracking;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.util.AsyncIo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Samples vanilla stats periodically and on logout, and upserts one row per player.
 *
 * <h2>Sampled, never event-hooked — on purpose</h2>
 * A per-event listener on inventory or world activity is exactly the shape that cost this server
 * 7.89% of the server thread on recipe-advancement listeners (measured 2026-09-08). Sampling costs
 * one stats read per online player per interval.
 *
 * <p>Chat and command counts are our own, incremented from handlers that ALREADY run on every
 * message and every command — so they add a {@code long++}, not a new subscription.
 *
 * <h2>🔴 The ordering hazard in the counters</h2>
 * The chat/command counters are DELTAS since the last sample, and the SQL adds them to the stored
 * value. They are therefore cleared only after the row is queued, and a sample that never reaches
 * the database loses that delta rather than double-counting it. Losing a few chat counts is
 * unimportant; inventing them is worse than not having them.
 */
public final class ActivitySampler {

    private static final Map<UUID, AtomicLong> CHAT     = new ConcurrentHashMap<>();
    private static final Map<UUID, AtomicLong> COMMANDS = new ConcurrentHashMap<>();

    private ActivitySampler() {}

    public static void onChat(UUID uuid) {
        if (uuid != null) CHAT.computeIfAbsent(uuid, k -> new AtomicLong()).incrementAndGet();
    }

    public static void onCommand(UUID uuid) {
        if (uuid != null) COMMANDS.computeIfAbsent(uuid, k -> new AtomicLong()).incrementAndGet();
    }

    /** Samples every online player. Server thread — it reads the live stats map. */
    public static void sampleAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) sample(p);
    }

    /** Samples one player. Server thread only. */
    public static void sample(ServerPlayer player) {
        if (player == null) return;
        UUID uuid = player.getUUID();
        try {
            var stats = player.getStats();

            long mined = 0;
            for (var block : BuiltInRegistries.BLOCK) mined += stats.getValue(Stats.BLOCK_MINED.get(block));
            long used = 0;
            for (var item : BuiltInRegistries.ITEM) used += stats.getValue(Stats.ITEM_USED.get(item));

            int deaths      = stats.getValue(Stats.CUSTOM, Stats.DEATHS);
            int mobKills    = stats.getValue(Stats.CUSTOM, Stats.MOB_KILLS);
            int playerKills = stats.getValue(Stats.CUSTOM, Stats.PLAYER_KILLS);
            long distance   = (long) stats.getValue(Stats.CUSTOM, Stats.WALK_ONE_CM)
                            + stats.getValue(Stats.CUSTOM, Stats.SPRINT_ONE_CM)
                            + stats.getValue(Stats.CUSTOM, Stats.FLY_ONE_CM);

            // Take the deltas out of the maps BEFORE queueing, so a message arriving mid-write is
            // counted against the NEXT sample rather than being silently dropped or doubled.
            AtomicLong chatCtr = CHAT.remove(uuid);
            AtomicLong cmdCtr  = COMMANDS.remove(uuid);
            long chat = chatCtr == null ? 0 : chatCtr.get();
            long cmds = cmdCtr  == null ? 0 : cmdCtr.get();
            long now  = System.currentTimeMillis();

            // The registry loops above mutate these, so they are not effectively final and the
            // lambda cannot capture them directly.
            final long fMined = mined, fUsed = used, fDistance = distance;

            AsyncIo.submit(() -> {
                if (CoffeesAeroAuth.DB_MANAGER == null || !CoffeesAeroAuth.DB_MANAGER.isAvailable()) return;
                try (Connection c = CoffeesAeroAuth.DB_MANAGER.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO player_stats (uuid, blocks_mined, items_used, deaths, " +
                         "mob_kills, player_kills, distance_cm, chat_messages, commands_run, " +
                         "sampled_epoch) VALUES (?,?,?,?,?,?,?,?,?,?) " +
                         // Vanilla stats OVERWRITE — they are absolute totals, and after a
                         // /authmod freshstart they legitimately drop to zero. A GREATEST() merge
                         // would keep ghost numbers for a wiped player forever.
                         // Chat/commands ACCUMULATE — they are deltas we counted ourselves.
                         "ON DUPLICATE KEY UPDATE blocks_mined=VALUES(blocks_mined), " +
                         "items_used=VALUES(items_used), deaths=VALUES(deaths), " +
                         "mob_kills=VALUES(mob_kills), player_kills=VALUES(player_kills), " +
                         "distance_cm=VALUES(distance_cm), " +
                         "chat_messages=chat_messages+VALUES(chat_messages), " +
                         "commands_run=commands_run+VALUES(commands_run), " +
                         "sampled_epoch=VALUES(sampled_epoch)")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, fMined);
                    ps.setLong(3, fUsed);
                    ps.setInt(4, deaths);
                    ps.setInt(5, mobKills);
                    ps.setInt(6, playerKills);
                    ps.setLong(7, fDistance);
                    ps.setLong(8, chat);
                    ps.setLong(9, cmds);
                    ps.setLong(10, now);
                    ps.executeUpdate();
                } catch (Exception e) {
                    CoffeesAeroAuth.LOGGER.error("[Tracking] stats write failed for {}: {}",
                        uuid, e.toString());
                }
            });
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[Tracking] sample failed for {}: {}", uuid, e.toString());
        }
    }

    /** Drops counters for a player nobody will sample again. */
    public static void forget(UUID uuid) {
        CHAT.remove(uuid);
        COMMANDS.remove(uuid);
    }

    /** True on the lobby, where activity sampling is pointless — nothing is played there. */
    public static boolean disabledHere() {
        return LobbyHandoff.isLobbyRole();
    }
}
