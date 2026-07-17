package com.coffeesaerosmp.auth.commands;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.AsyncIo;
import com.coffeesaerosmp.auth.util.TextUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Our own /rtp — replaces FTB Essentials' (disabled via the shipped ftbessentials.snbt), whose
 * synchronous destination chunk-gen froze the server for 20-40s per use on this worldgen stack
 * (Terralith/Tectonic; 2026-07-17: 756 ticks behind, every player disconnected).
 *
 * <p>Flow: pick a random ring target (biome-sampled to avoid oceans — a generator BiomeSource query,
 * no chunks generated), add a region ticket over the whole arrival bubble (default 15×15 chunks —
 * covering the view distance matters: a 5×5-only pregen still froze the LIVE server 36s when the
 * surrounding view-distance worldgen burst hit c2me's sync-load stall), and poll readiness with
 * {@link ServerChunkCache#getChunkNow} — never {@code getChunkFuture}, which secretly
 * {@code managedBlock}s the server thread when called from it. The chunk system + c2me generate
 * everything in the background, center-out, while the player waits in place watching an action-bar
 * progress readout; the teleport happens only when EVERY chunk is ready AND the minimum wait has
 * elapsed. Long cooldown (default 24h) persisted to {@code rtp_cooldowns.json} so relogs/restarts
 * don't reset it; ops exempt. Abort paths (timeout, all-water landing after one re-chart, logout)
 * never charge the cooldown.</p>
 */
public final class RtpCommand {

    /** Keeps the destination area ticketed while we generate; timeout is a leak safety net — the
     *  per-tick driver re-adds it periodically so it never expires while a request is pending. */
    private static final TicketType<ChunkPos> RTP_TICKET =
        TicketType.create("aero_rtp", Comparator.comparingLong(ChunkPos::toLong), 20 * 300);

    private static final int TICKET_REFRESH_TICKS = 200;   // re-add well inside the ticket timeout
    private static final int POLL_INTERVAL_TICKS  = 5;

    private static final class Pending {
        final ServerLevel level;
        final int x, z;                          // block coords of the target center
        final ChunkPos center;
        final int radius;                        // chunk radius fully generated before teleport
        final int totalChunks;
        final long startedAtMs;
        int readyChunks = 0;                     // refreshed by the poll
        int ticksSinceRefresh = 0;
        boolean recharted = false;               // one automatic ocean re-roll allowed

        Pending(ServerLevel level, int x, int z, int radius, long startedAtMs) {
            this.level = level; this.x = x; this.z = z;
            this.center = new ChunkPos(BlockPos.containing(x, 0, z));
            this.radius = radius;
            this.totalChunks = (radius * 2 + 1) * (radius * 2 + 1);
            this.startedAtMs = startedAtMs;
        }
    }

    private static final Map<UUID, Pending> pending   = new ConcurrentHashMap<>();
    private static final Map<UUID, Long>    cooldowns = new ConcurrentHashMap<>();
    private static volatile Path cooldownFile;

    private RtpCommand() {}

    // ── Persistence ───────────────────────────────────────────────────────────

    public static void initialize(Path dataDir) {
        cooldownFile = dataDir.resolve("rtp_cooldowns.json");
        pending.clear();
        cooldowns.clear();
        if (!Files.exists(cooldownFile)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(cooldownFile)).getAsJsonObject();
            o.entrySet().forEach(e -> cooldowns.put(UUID.fromString(e.getKey()), e.getValue().getAsLong()));
            CoffeesAeroAuth.LOGGER.info("[Rtp] Loaded {} cooldown entries.", cooldowns.size());
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.warn("[Rtp] rtp_cooldowns.json load failed: {}", e.getMessage());
        }
    }

    private static void saveCooldowns() {
        Path f = cooldownFile;
        if (f == null) return;
        JsonObject o = new JsonObject();
        cooldowns.forEach((id, t) -> o.addProperty(id.toString(), t));
        String json = o.toString();
        AsyncIo.submit(() -> {
            try { Files.writeString(f, json); }
            catch (Exception e) { CoffeesAeroAuth.LOGGER.warn("[Rtp] cooldown save failed: {}", e.getMessage()); }
        });
    }

    // ── Command ───────────────────────────────────────────────────────────────

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("rtp").executes(ctx -> {
            handle(ctx.getSource().getPlayerOrException());
            return 1;
        }));
    }

    private static void handle(ServerPlayer player) {
        if (!AuthConfig.RTP_ENABLED.get()) {
            msg(player, "§cRandom teleport is disabled.");
            return;
        }
        UUID uuid = player.getUUID();
        if (CoffeesAeroAuth.AUTH_MANAGER == null || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(uuid)) {
            msg(player, "§cFinish logging in first.");
            return;
        }
        if (pending.containsKey(uuid)) {
            msg(player, "§7Your destination is still being charted — hold tight.");
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            msg(player, "§cRandom teleport only works in the overworld.");
            return;
        }
        long cooldownMs = AuthConfig.RTP_COOLDOWN_HOURS.get() * 3_600_000L;
        if (cooldownMs > 0 && !player.hasPermissions(2)) {
            long readyAt = cooldowns.getOrDefault(uuid, 0L) + cooldownMs;
            long left = readyAt - System.currentTimeMillis();
            if (left > 0) {
                msg(player, "§cYou can wander again in §f" + formatDuration(left) + "§c.");
                return;
            }
        }
        start(player, level, false);
    }

    private static void start(ServerPlayer player, ServerLevel level, boolean isRechart) {
        int[] target = pickTarget(level);
        Pending p = new Pending(level, target[0], target[1],
            AuthConfig.RTP_PREGEN_RADIUS.get(), System.currentTimeMillis());
        p.recharted = isRechart;
        pending.put(player.getUUID(), p);

        // One region ticket over the whole bubble — the chunk system generates it in the background,
        // closest-first by ticket level. NO chunk accessors here: getChunkFuture/getChunk from the
        // server thread managedBlock the tick loop (the 1.6.26 mistake); readiness is polled instead.
        level.getChunkSource().addRegionTicket(RTP_TICKET, p.center, p.radius + 1, p.center);
        if (!isRechart) {
            msg(player, "§7✈ Charting a course to somewhere new… stay put while the world takes shape.");
        }
    }

    /**
     * Random ring target around world spawn. Biome-sampled straight from the generator's BiomeSource
     * (climate noise only — generates nothing) to skip ocean/river landings; after 60 wet rolls the
     * last candidate is used anyway and the landing scan deals with it.
     */
    private static int[] pickTarget(ServerLevel level) {
        BlockPos spawn = level.getSharedSpawnPos();
        int min = AuthConfig.RTP_MIN_DISTANCE.get();
        int max = Math.max(AuthConfig.RTP_MAX_DISTANCE.get(), min + 1);
        var random = ThreadLocalRandom.current();
        var biomes  = level.getChunkSource().getGenerator().getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
        int x = spawn.getX(), z = spawn.getZ();
        for (int attempt = 0; attempt < 60; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist  = min + random.nextDouble() * (max - min);
            x = spawn.getX() + (int) (Math.cos(angle) * dist);
            z = spawn.getZ() + (int) (Math.sin(angle) * dist);
            var biome = biomes.getNoiseBiome(x >> 2, 64 >> 2, z >> 2, sampler);
            if (!biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_DEEP_OCEAN)
                    && !biome.is(BiomeTags.IS_RIVER)) {
                break;
            }
        }
        return new int[]{x, z};
    }

    // ── Per-tick driver (called from the mod's server-tick listener) ──────────

    public static void onServerTick(MinecraftServer server) {
        if (pending.isEmpty()) return;
        for (var entry : pending.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Pending p = entry.getValue();
            if (player == null) {                          // logged out — abandon, no cooldown charged
                finish(entry.getKey(), p, true);
                continue;
            }
            // Refresh the region ticket well inside its timeout so a long pregen never loses it.
            if (++p.ticksSinceRefresh >= TICKET_REFRESH_TICKS) {
                p.ticksSinceRefresh = 0;
                p.level.getChunkSource().addRegionTicket(RTP_TICKET, p.center, p.radius + 1, p.center);
            }
            if (player.tickCount % POLL_INTERVAL_TICKS == 0 && p.readyChunks < p.totalChunks) {
                p.readyChunks = countReadyChunks(p);       // getChunkNow — pure map lookups, no blocking
            }
            long elapsedMs = System.currentTimeMillis() - p.startedAtMs;
            boolean generated = p.readyChunks >= p.totalChunks;
            long minWaitMs = AuthConfig.RTP_MIN_WAIT_SECONDS.get() * 1000L;

            if (!generated && elapsedMs > AuthConfig.RTP_TIMEOUT_SECONDS.get() * 1000L) {
                finish(entry.getKey(), p, true);
                msg(player, "§cThe world took too long to shape itself — try /rtp again (no cooldown used).");
                continue;
            }
            if (generated && elapsedMs >= minWaitMs) {
                // Keep the ticket through the arrival (it self-expires): the freshly generated bubble
                // must not unload/regenerate while the client streams in around the player.
                finish(entry.getKey(), p, false);
                land(player, p);
                continue;
            }
            if (player.tickCount % 20 != 0) continue;      // action-bar updates once a second
            if (!generated) {
                int pct = Math.min(99, p.readyChunks * 100 / p.totalChunks);
                player.displayClientMessage(Component.literal(
                    "§6✈ §eCharting course… §f" + pct + "% §8(" + p.readyChunks + "/" + p.totalChunks + " chunks)"), true);
            } else {
                long waitLeft = (minWaitMs - elapsedMs + 999) / 1000;
                player.displayClientMessage(Component.literal(
                    "§6✈ §aDestination ready §7— teleporting in §f" + Math.max(1, waitLeft) + "s"), true);
            }
        }
    }

    /** Non-blocking readiness count: {@code getChunkNow} returns only fully-loaded FULL chunks. */
    private static int countReadyChunks(Pending p) {
        ServerChunkCache chunks = p.level.getChunkSource();
        int ready = 0;
        for (int cx = p.center.x - p.radius; cx <= p.center.x + p.radius; cx++) {
            for (int cz = p.center.z - p.radius; cz <= p.center.z + p.radius; cz++) {
                if (chunks.getChunkNow(cx, cz) != null) ready++;
            }
        }
        return ready;
    }

    /** Ends the pending request. {@code releaseTicket} false = let the arrival keep the area loaded
     *  until the ticket's own timeout (the player's presence takes over from there). */
    private static void finish(UUID uuid, Pending p, boolean releaseTicket) {
        pending.remove(uuid);
        if (!releaseTicket) return;
        try {
            p.level.getChunkSource().removeRegionTicket(RTP_TICKET, p.center, p.radius + 1, p.center);
        } catch (Exception ignored) {}   // ticket may have hit its timeout already
    }

    /** Chunks are generated — find a dry column near the target and teleport. */
    private static void land(ServerPlayer player, Pending p) {
        BlockPos spot = findDryColumn(p.level, p.x, p.z, p.radius);
        if (spot == null) {
            if (!p.recharted) {
                msg(player, "§7Open water below — re-charting…");
                start(player, p.level, true);
            } else {
                msg(player, "§cCouldn't find dry land twice in a row — try /rtp again (no cooldown used).");
            }
            return;
        }
        player.teleportTo(p.level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
            Set.of(), player.getYRot(), player.getXRot());
        cooldowns.put(player.getUUID(), System.currentTimeMillis());
        saveCooldowns();
        player.displayClientMessage(Component.literal("§6✈ §aWelcome to the wilds!"), true);
        msg(player, "§aTeleported to §f" + spot.getX() + ", " + spot.getY() + ", " + spot.getZ()
            + "§a — good luck out there!");
        CoffeesAeroAuth.LOGGER.info("[Rtp] {} teleported to {} {} {} ({}s wait).",
            player.getGameProfile().getName(), spot.getX(), spot.getY(), spot.getZ(),
            (System.currentTimeMillis() - p.startedAtMs) / 1000);
    }

    /** Spiral outward from the target over the pregenerated area looking for a solid, fluid-free top. */
    private static BlockPos findDryColumn(ServerLevel level, int x, int z, int chunkRadius) {
        int reach = chunkRadius * 16 + 8;
        for (int radius = 0; radius <= reach; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;   // ring only
                    int cx = x + dx, cz = z + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz);
                    if (y <= level.getMinBuildHeight() + 1 || y >= level.getMaxBuildHeight() - 2) continue;
                    BlockPos below = new BlockPos(cx, y - 1, cz);
                    BlockState ground = level.getBlockState(below);
                    if (ground.getFluidState().isEmpty() && !ground.isAir()
                            && level.getBlockState(below.above()).getFluidState().isEmpty()) {
                        return below.above();
                    }
                }
            }
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String formatDuration(long ms) {
        long totalMin = (ms + 59_999) / 60_000;
        long h = totalMin / 60, m = totalMin % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private static void msg(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX + text));
    }
}
