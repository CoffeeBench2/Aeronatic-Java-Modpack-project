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
 * elapsed. Cooldown (default 15 min) persisted to {@code rtp_cooldowns.json} so relogs/restarts
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

    /** Squared movement tolerance (~1 block): head-turns/sneak-twitches never false-cancel,
     *  any real step or jump breaks the charting. Exact-position equality would insta-cancel on
     *  slab/soul-sand micro-drift, hence the radius. */
    private static final double MOVE_TOLERANCE_SQ = 1.0;

    private static final class Pending {
        final ServerLevel level;
        final String playerName;                 // for console lines after the player object is gone
        final int x, z;                          // block coords of the target center
        final ChunkPos center;
        final int radius;                        // chunk radius fully generated before teleport
        final int totalChunks;
        final long startedAtMs;
        final double anchorX, anchorY, anchorZ;  // where the player must STAY until the teleport
        int ticketRadius;                        // current progressive ring (grows as rings finish)
        int readyChunks = 0;                     // refreshed by the poll
        int ticksSinceRefresh = 0;
        boolean recharted = false;               // one automatic ocean re-roll allowed

        Pending(ServerLevel level, ServerPlayer player, int x, int z, int radius, long startedAtMs) {
            this.level = level; this.playerName = player.getGameProfile().getName();
            this.x = x; this.z = z;
            this.center = new ChunkPos(BlockPos.containing(x, 0, z));
            this.radius = radius;
            this.totalChunks = (radius * 2 + 1) * (radius * 2 + 1);
            this.startedAtMs = startedAtMs;
            this.anchorX = player.getX(); this.anchorY = player.getY(); this.anchorZ = player.getZ();
        }

        long elapsedSec() { return (System.currentTimeMillis() - startedAtMs) / 1000; }

        boolean movedAway(ServerPlayer player) {
            return player.level() != level
                || player.distanceToSqr(anchorX, anchorY, anchorZ) > MOVE_TOLERANCE_SQ;
        }
    }

    private static final Map<UUID, Pending> pending   = new ConcurrentHashMap<>();
    /** Players whose target is being picked on a background thread (guards double-/rtp). */
    private static final Set<UUID>          choosing  = ConcurrentHashMap.newKeySet();
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
        if (pending.containsKey(uuid) || choosing.contains(uuid)) {
            msg(player, "§7Your destination is still being charted — hold tight.");
            return;
        }
        if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            msg(player, "§cRandom teleport only works in the overworld.");
            return;
        }
        long cooldownMs = AuthConfig.RTP_COOLDOWN_MINUTES.get() * 60_000L;
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
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        // Movement rule: the player must stand STILL from the moment they type /rtp until the
        // teleport fires. Anchor here (command time) for the async target-selection window; the
        // Pending re-anchors for the pregen wait. Moving cancels — never charges the cooldown.
        double ax = player.getX(), ay = player.getY(), az = player.getZ();
        choosing.add(uuid);
        if (!isRechart) {
            msg(player, "§7✈ Charting a course to somewhere new… §fstand perfectly still§7 — moving cancels the teleport.");
        }
        // Target selection runs OFF the server thread (the 1.6.29 lesson, 21:02 live log: 0/225
        // chunks after 57s — the freeze began AT /rtp start, not during pregen). Each ocean-avoid
        // biome sample evaluates the full uncached Terralith/Tectonic noise stack; an unlucky
        // all-ocean roll = dozens of expensive samples = a 56s main-thread stall. The sampler is
        // thread-safe (worker threads use it throughout worldgen), so the whole search moves to
        // the background executor and only the ticket/bookkeeping hops back to the server thread.
        java.util.concurrent.CompletableFuture
            .supplyAsync(() -> pickTarget(level), net.minecraft.Util.backgroundExecutor())
            .whenCompleteAsync((target, err) -> {
                if (!choosing.remove(uuid)) return;              // superseded/cleared
                ServerPlayer current = level.getServer().getPlayerList().getPlayer(uuid);
                if (current == null) return;                     // logged out while sampling — nothing charged
                if (err != null || target == null) {
                    CoffeesAeroAuth.LOGGER.warn("[Rtp] {} target selection failed: {}", name,
                        err != null ? err.toString() : "null");
                    msg(current, "§cCouldn't chart a course — try /rtp again (no cooldown used).");
                    return;
                }
                if (current.level() != level || current.distanceToSqr(ax, ay, az) > MOVE_TOLERANCE_SQ) {
                    CoffeesAeroAuth.LOGGER.info("[Rtp] {} moved during target selection — cancelled, no cooldown.", name);
                    msg(current, "§cYou moved — teleport cancelled §7(no cooldown used)§c.");
                    return;
                }
                beginPregen(current, level, target, isRechart);
            }, level.getServer());
    }

    /** Server thread. Target chosen — create the pending request and start the ring pregen. */
    private static void beginPregen(ServerPlayer player, ServerLevel level, int[] target, boolean isRechart) {
        Pending p = new Pending(level, player, target[0], target[1],
            AuthConfig.RTP_PREGEN_RADIUS.get(), System.currentTimeMillis());
        p.recharted = isRechart;
        pending.put(player.getUUID(), p);
        // Console timeline for freeze correlation: every rtp phase logs — this start line is what
        // lets a "server stalled at HH:MM:SS" be matched to whose pregen was running where.
        CoffeesAeroAuth.LOGGER.info("[Rtp] {} charting to {} {} ({}x{} chunks{}, target picked in {}ms).",
            p.playerName, p.x, p.z, p.radius * 2 + 1, p.radius * 2 + 1,
            isRechart ? ", re-chart" : "", target.length > 2 ? target[2] : -1);

        // PROGRESSIVE pregen (the 1.6.27 lesson): small ring first, widened only when the current
        // ring is fully generated — the same trickle rate normal exploration produces. NO chunk
        // accessors here: getChunkFuture/getChunk from the server thread managedBlock the tick loop
        // (the 1.6.26 mistake); readiness is polled with getChunkNow.
        p.ticketRadius = Math.min(2, p.radius);
        addRingTickets(p, -1, p.ticketRadius);
    }

    /**
     * PER-CHUNK distance-0 tickets = ticket level 33 = FULL-but-NON-TICKING (the 1.6.31 lesson):
     * a region ticket over the bubble gave inner chunks aggressive levels, so by ring 6-7 ~169
     * freshly generated chunks — including any brand-new village full of villagers/POIs — were
     * ENTITY-TICKING on the main thread with no player anywhere near them. Level-33 chunks still
     * generate fully; they just don't tick. Adds tickets for chunks with Chebyshev distance in
     * (fromExclusive, toInclusive] around the target.
     */
    private static void addRingTickets(Pending p, int fromExclusive, int toInclusive) {
        ServerChunkCache chunks = p.level.getChunkSource();
        for (int cx = p.center.x - toInclusive; cx <= p.center.x + toInclusive; cx++) {
            for (int cz = p.center.z - toInclusive; cz <= p.center.z + toInclusive; cz++) {
                int dist = Math.max(Math.abs(cx - p.center.x), Math.abs(cz - p.center.z));
                if (dist > fromExclusive && dist <= toInclusive) {
                    chunks.addRegionTicket(RTP_TICKET, new ChunkPos(cx, cz), 0, p.center);
                }
            }
        }
    }

    private static void removeAllTickets(Pending p) {
        ServerChunkCache chunks = p.level.getChunkSource();
        for (int cx = p.center.x - p.ticketRadius; cx <= p.center.x + p.ticketRadius; cx++) {
            for (int cz = p.center.z - p.ticketRadius; cz <= p.center.z + p.ticketRadius; cz++) {
                try {
                    chunks.removeRegionTicket(RTP_TICKET, new ChunkPos(cx, cz), 0, p.center);
                } catch (Exception ignored) {}   // some may have hit the ticket timeout already
            }
        }
    }

    /**
     * BACKGROUND THREAD. Random ring target around world spawn. Biome-sampled straight from the
     * generator's BiomeSource (climate noise only — generates nothing, thread-safe) to skip
     * ocean/river landings. Terralith/Tectonic make each sample expensive, so the search is both
     * off-thread AND time-budgeted: after 60 wet rolls or 8s, the last candidate is used anyway
     * and the landing scan's re-chart deals with it. Returns {x, z, elapsedMs}.
     */
    private static int[] pickTarget(ServerLevel level) {
        long startedAt = System.currentTimeMillis();
        BlockPos spawn = level.getSharedSpawnPos();
        int min = AuthConfig.RTP_MIN_DISTANCE.get();
        int max = Math.max(AuthConfig.RTP_MAX_DISTANCE.get(), min + 1);
        var random = ThreadLocalRandom.current();
        var biomes  = level.getChunkSource().getGenerator().getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
        int x = spawn.getX(), z = spawn.getZ();
        for (int attempt = 0; attempt < 60; attempt++) {
            if (System.currentTimeMillis() - startedAt > 8_000) break;   // budget — take what we have
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
        return new int[]{x, z, (int) (System.currentTimeMillis() - startedAt)};
    }

    // ── Per-tick driver (called from the mod's server-tick listener) ──────────

    /** Tick-gap stall detector: last onServerTick wall time while any rtp is pending. */
    private static long lastTickAtMs = 0;

    public static void onServerTick(MinecraftServer server) {
        if (pending.isEmpty()) { lastTickAtMs = 0; return; }
        // Self-instrumentation (the 03:41 ambiguity killer): if the SERVER thread ever stalls >2s
        // while a pregen is pending, say so with the exact gap — no more inferring freezes from
        // voicechat timeouts.
        long now = System.currentTimeMillis();
        if (lastTickAtMs > 0 && now - lastTickAtMs > 2_000) {
            CoffeesAeroAuth.LOGGER.warn("[Rtp] SERVER THREAD STALLED {}ms while a pregen was pending.",
                now - lastTickAtMs);
        }
        lastTickAtMs = now;
        for (var entry : pending.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            Pending p = entry.getValue();
            if (player == null) {                          // logged out — abandon, no cooldown charged
                finish(entry.getKey(), p, true);
                CoffeesAeroAuth.LOGGER.info("[Rtp] {} logged out mid-charting ({}/{} chunks, {}s) — abandoned, no cooldown.",
                    p.playerName, p.readyChunks, p.totalChunks, p.elapsedSec());
                continue;
            }
            // Re-add tickets well inside their timeout so a long pregen never loses them, and log a
            // progress heartbeat so a silent console is itself diagnostic.
            if (++p.ticksSinceRefresh >= TICKET_REFRESH_TICKS) {
                p.ticksSinceRefresh = 0;
                addRingTickets(p, -1, p.ticketRadius);
                CoffeesAeroAuth.LOGGER.info("[Rtp] {} still generating… {}/{} chunks (ring {}, {}s).",
                    p.playerName, p.readyChunks, p.totalChunks, p.ticketRadius, p.elapsedSec());
            }
            if (player.tickCount % POLL_INTERVAL_TICKS == 0 && p.readyChunks < p.totalChunks) {
                p.readyChunks = countReadyChunks(p);       // getChunkNow — pure map lookups, no blocking
                // Current ring fully generated → widen by 2 (new ring tickets are ADDED; existing
                // inner tickets are untouched, so the bubble never momentarily loses coverage).
                if (p.ticketRadius < p.radius
                        && p.readyChunks >= (p.ticketRadius * 2 + 1) * (p.ticketRadius * 2 + 1)) {
                    int old = p.ticketRadius;
                    p.ticketRadius = Math.min(p.ticketRadius + 2, p.radius);
                    addRingTickets(p, old, p.ticketRadius);
                    CoffeesAeroAuth.LOGGER.info("[Rtp] {} pregen ring {} done — widening to {} ({}/{} chunks, {}s).",
                        p.playerName, old, p.ticketRadius, p.readyChunks, p.totalChunks, p.elapsedSec());
                }
            }
            long elapsedMs = System.currentTimeMillis() - p.startedAtMs;
            boolean generated = p.readyChunks >= p.totalChunks;
            long minWaitMs = AuthConfig.RTP_MIN_WAIT_SECONDS.get() * 1000L;

            if (!generated && elapsedMs > AuthConfig.RTP_TIMEOUT_SECONDS.get() * 1000L) {
                finish(entry.getKey(), p, true);
                CoffeesAeroAuth.LOGGER.warn("[Rtp] {} TIMED OUT after {}s ({}/{} chunks generated, ring {}) — cooldown refunded.",
                    p.playerName, p.elapsedSec(), p.readyChunks, p.totalChunks, p.ticketRadius);
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
        if (!releaseTicket) return;      // arrival keeps its tickets until their timeout
        removeAllTickets(p);
    }

    /** Chunks are generated — find a dry column near the target and teleport. */
    private static void land(ServerPlayer player, Pending p) {
        BlockPos spot = findDryColumn(p.level, p.x, p.z, p.radius);
        if (spot == null) {
            if (!p.recharted) {
                CoffeesAeroAuth.LOGGER.info("[Rtp] {} landed on open water at {} {} — re-charting once.",
                    p.playerName, p.x, p.z);
                msg(player, "§7Open water below — re-charting…");
                start(player, p.level, true);
            } else {
                CoffeesAeroAuth.LOGGER.warn("[Rtp] {} found no dry land twice (last target {} {}) — aborted, cooldown refunded.",
                    p.playerName, p.x, p.z);
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
