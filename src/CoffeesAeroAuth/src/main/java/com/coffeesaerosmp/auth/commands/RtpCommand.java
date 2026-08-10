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

    /** How long an anchor arrival may take before we stop believing it is generated. Loading 225
     *  chunks off disk was measured at under a second; 20s is slack for a busy server, not for
     *  worldgen (which runs ~2.7-3.4s per chunk here — one single chunk blows this budget). */
    private static final long ANCHOR_GRACE_MS = 20_000L;

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
        /** Non-null when this request targets a pregenerated anchor rather than a fresh random roll. */
        RtpAnchors.Anchor anchor;
        boolean anchorAudited = false;           // anchor already demoted for being slower than a load

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
        // GLOBAL QUEUE: at ~3.4s/chunk (measured live 07-18 without c2me) a virgin pregen is a
        // multi-minute worldgen job — ONE runs at a time server-wide; everyone else waits in FIFO
        // order, standing still (the movement rule applies while queued too).
        if (isBusy() || !queue.isEmpty()) {
            for (Queued q : queue) {
                if (q.uuid.equals(uuid)) {
                    msg(player, "§7You're already §f#" + queuePosition(uuid) + "§7 in the teleport queue — stand still.");
                    return;
                }
            }
            queue.addLast(new Queued(uuid, player.getX(), player.getY(), player.getZ()));
            msg(player, "§7✈ Another pilot's course is being charted — you're §f#" + queue.size()
                + "§7 in the queue. §fStand perfectly still§7; you'll launch automatically.");
            return;
        }
        start(player, level, false);
    }

    // ── Global queue (server thread only) ─────────────────────────────────────

    private record Queued(UUID uuid, double ax, double ay, double az) {}

    private static final java.util.ArrayDeque<Queued> queue = new java.util.ArrayDeque<>();

    private static boolean isBusy() { return !pending.isEmpty() || !choosing.isEmpty(); }

    private static int queuePosition(UUID uuid) {
        int i = 1;
        for (Queued q : queue) { if (q.uuid.equals(uuid)) return i; i++; }
        return -1;
    }

    /** Once a second: drop queued players who moved/logged out, action-bar the rest their position. */
    private static void sweepAndDisplayQueue(MinecraftServer server) {
        int pos = 0;
        for (var it = queue.iterator(); it.hasNext(); ) {
            Queued q = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(q.uuid);
            if (player == null) { it.remove(); continue; }
            if (player.distanceToSqr(q.ax, q.ay, q.az) > MOVE_TOLERANCE_SQ) {
                it.remove();
                msg(player, "§cYou moved while queued — teleport cancelled §7(no cooldown used)§c.");
                continue;
            }
            pos++;
            player.displayClientMessage(Component.literal(
                "§6✈ §eTeleport queue §7— position §f#" + pos + " §8(stand still)"), true);
        }
    }

    /** Server thread, called when the active request finishes: launch the next valid queued player. */
    private static void pumpQueue(MinecraftServer server) {
        while (!isBusy() && !queue.isEmpty()) {
            Queued q = queue.pollFirst();
            ServerPlayer player = server.getPlayerList().getPlayer(q.uuid);
            if (player == null) continue;                          // logged out while queued
            if (!(player.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                    || player.distanceToSqr(q.ax, q.ay, q.az) > MOVE_TOLERANCE_SQ) {
                msg(player, "§cYou moved while queued — teleport cancelled §7(no cooldown used)§c.");
                continue;
            }
            msg(player, "§a✈ Your turn — charting your course now.");
            start(player, level, false);
        }
    }

    private static void start(ServerPlayer player, ServerLevel level, boolean isRechart) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        // Movement rule: the player must stand STILL from the moment they type /rtp until the
        // teleport fires. Anchor here (command time) for the async target-selection window; the
        // Pending re-anchors for the pregen wait. Moving cancels — never charges the cooldown.
        double ax = player.getX(), ay = player.getY(), az = player.getZ();

        // ANCHOR FAST PATH (1.7.5). A warm anchor is already generated on disk, so there is nothing
        // to search for and nothing to build: skip the expensive biome sampling entirely and hand
        // beginPregen the coordinates directly. The readiness poll then completes almost at once
        // because getChunkNow is satisfied by a disk LOAD instead of a multi-minute generation.
        // Re-charts deliberately fall through to the random path — a re-chart means this anchor's
        // landing scan failed, so trying it again would just fail the same way.
        if (!isRechart && RtpAnchors.enabled()) {
            RtpAnchors.Anchor anchor = RtpAnchors.nextWarm();
            if (anchor != null) {
                CoffeesAeroAuth.LOGGER.info("[Rtp] {} routed to anchor {} {} (generated to radius {}, need {}{}).",
                    name, anchor.x, anchor.z, anchor.warmRadius, RtpAnchors.pregenRadius(),
                    RtpAnchors.hardened(anchor) ? "" : " — SOFT RIM, expect the outer ring to generate");
                msg(player, "§7✈ Charting a course to somewhere new… §fstand perfectly still§7 — moving cancels the teleport.");
                beginPregen(player, level, new int[]{anchor.x, anchor.z, 0}, false, anchor);
                return;
            }
        }

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
        beginPregen(player, level, target, isRechart, null);
    }

    private static void beginPregen(ServerPlayer player, ServerLevel level, int[] target,
                                    boolean isRechart, RtpAnchors.Anchor anchor) {
        Pending p = new Pending(level, player, target[0], target[1],
            AuthConfig.RTP_PREGEN_RADIUS.get(), System.currentTimeMillis());
        p.recharted = isRechart;
        p.anchor = anchor;
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
        addRingTickets(p.level, p.center, fromExclusive, toInclusive);
    }

    /** Level/centre form — shared with the anchor warmer, which has no player and no Pending. */
    private static void addRingTickets(ServerLevel level, ChunkPos center, int fromExclusive, int toInclusive) {
        ServerChunkCache chunks = level.getChunkSource();
        for (int cx = center.x - toInclusive; cx <= center.x + toInclusive; cx++) {
            for (int cz = center.z - toInclusive; cz <= center.z + toInclusive; cz++) {
                int dist = Math.max(Math.abs(cx - center.x), Math.abs(cz - center.z));
                if (dist > fromExclusive && dist <= toInclusive) {
                    chunks.addRegionTicket(RTP_TICKET, new ChunkPos(cx, cz), 0, center);
                }
            }
        }
    }

    private static void removeAllTickets(Pending p) {
        removeTickets(p.level, p.center, p.ticketRadius);
    }

    private static void removeTickets(ServerLevel level, ChunkPos center, int radius) {
        ServerChunkCache chunks = level.getChunkSource();
        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                try {
                    chunks.removeRegionTicket(RTP_TICKET, new ChunkPos(cx, cz), 0, center);
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

    /**
     * BACKGROUND THREAD. Same ocean-avoiding sampler as {@link #pickTarget}, but on a supplied
     * bearing so the anchor pool forms a ring around spawn instead of clumping. Jitter is ±half a
     * slice, which keeps the spread even while stopping anchors landing on a perfectly regular
     * (and very obvious) polygon. Distance uses rtpAnchorMaxDistance, not rtpMaxDistance.
     */
    private static int[] pickAnchorTarget(ServerLevel level, double baseAngle) {
        long startedAt = System.currentTimeMillis();
        BlockPos spawn = level.getSharedSpawnPos();
        int min = AuthConfig.RTP_MIN_DISTANCE.get();
        int max = Math.max(AuthConfig.RTP_ANCHOR_MAX_DISTANCE.get(), min + 1);
        int slots = Math.max(RtpAnchors.configuredCount(), 1);
        double slice = Math.PI * 2.0 / slots;
        var random = ThreadLocalRandom.current();
        var biomes  = level.getChunkSource().getGenerator().getBiomeSource();
        var sampler = level.getChunkSource().randomState().sampler();
        int x = spawn.getX(), z = spawn.getZ();
        for (int attempt = 0; attempt < 60; attempt++) {
            if (System.currentTimeMillis() - startedAt > 8_000) break;   // same budget as pickTarget
            double angle = baseAngle + (random.nextDouble() - 0.5) * slice;
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

    // ── Anchor pool upkeep (idle-time work only) ──────────────────────────────

    /** One anchor being generated. No player, so none of the Pending movement/messaging rules apply. */
    private static final class WarmJob {
        final ServerLevel level;
        final RtpAnchors.Anchor anchor;
        final ChunkPos center;
        final int radius, totalChunks;
        final long startedAtMs = System.currentTimeMillis();
        int ticketRadius;
        int ticksSinceRefresh = 0;

        WarmJob(ServerLevel level, RtpAnchors.Anchor anchor, int radius) {
            this.level = level; this.anchor = anchor;
            this.center = new ChunkPos(BlockPos.containing(anchor.x, 0, anchor.z));
            this.radius = radius;
            this.totalChunks = (radius * 2 + 1) * (radius * 2 + 1);
            this.ticketRadius = Math.min(2, radius);
        }
    }

    private static WarmJob warming;
    private static final java.util.concurrent.atomic.AtomicBoolean pickingAnchor =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Generous: this is unattended background work, and a virgin bubble was measured at 3.4s/chunk.
     *  Raised to 45 min in 1.7.8 — the margin makes a bubble 19x19 = 361 chunks, which at that rate
     *  is ~20 min on its own, so the old 20 min ceiling would have timed out every single anchor. */
    private static final long WARM_TIMEOUT_MS = 45 * 60_000L;

    /**
     * Called only when no player request is active, so anchor worldgen can never compete with a
     * player who is standing still waiting on a progress bar — the ONE-AT-A-TIME rule that makes
     * the original /rtp survivable applies to the warmer too.
     */
    private static void tickAnchors(MinecraftServer server) {
        if (!RtpAnchors.enabled()) return;
        ServerLevel level = server.overworld();
        if (level == null) return;

        // PLAYER GATE (1.7.6). Warming generates 6750 chunks across the pool; doing that while
        // players are online is the exact worldgen load anchors exist to remove — it moves the lag
        // instead of deleting it. Pausing is free: generated chunks persist on DISK, so a resumed
        // job re-walks its finished rings at load speed. Tickets are dropped so nothing keeps
        // generating or stays resident after the pause.
        if (server.getPlayerList().getPlayerCount() > AuthConfig.RTP_ANCHOR_WARM_MAX_PLAYERS.get()) {
            if (warming != null) {
                WarmJob w = warming;
                int done = completedRadius(w.level, w.center, w.radius);   // count while still ticketed
                removeTickets(w.level, w.center, w.ticketRadius);
                if (done > w.anchor.warmRadius) RtpAnchors.markWarm(w.anchor, done);
                CoffeesAeroAuth.LOGGER.info("[Rtp] Anchor warming paused ({} online) — anchor {} {} at radius {}, resumes when quiet.",
                    server.getPlayerList().getPlayerCount(), w.anchor.x, w.anchor.z, done);
                warming = null;
            }
            return;
        }

        // 1. A warm job is in flight — drive it exactly like a player pregen, minus the player.
        if (warming != null) {
            WarmJob w = warming;
            if (++w.ticksSinceRefresh >= TICKET_REFRESH_TICKS) {
                w.ticksSinceRefresh = 0;
                addRingTickets(w.level, w.center, -1, w.ticketRadius);
            }
            if (server.getTickCount() % POLL_INTERVAL_TICKS != 0) return;

            int ready = countReady(w.level, w.center, w.radius);
            if (w.ticketRadius < w.radius
                    && ready >= (w.ticketRadius * 2 + 1) * (w.ticketRadius * 2 + 1)) {
                int old = w.ticketRadius;
                // Wider step than a player pregen (which stays at 2): the warmer only runs on a
                // quiet server, so there is nobody to disturb and finishing sooner is strictly better.
                w.ticketRadius = Math.min(w.ticketRadius + AuthConfig.RTP_ANCHOR_WARM_RING_STEP.get(), w.radius);
                addRingTickets(w.level, w.center, old, w.ticketRadius);
            }
            if (ready >= w.totalChunks) {
                removeTickets(w.level, w.center, w.ticketRadius);
                RtpAnchors.markWarm(w.anchor, w.radius);
                warming = null;
            } else if (System.currentTimeMillis() - w.startedAtMs > WARM_TIMEOUT_MS) {
                // Bank whatever fully completed, BEFORE dropping the tickets. A ring is only credited
                // once every chunk inside it is FULL, so this can never over-claim — and crediting a
                // PARTIAL job is what stops a pool on a rarely-empty server making no progress at all.
                int done = completedRadius(w.level, w.center, w.radius);
                removeTickets(w.level, w.center, w.ticketRadius);
                if (done > w.anchor.warmRadius) RtpAnchors.markWarm(w.anchor, done);
                CoffeesAeroAuth.LOGGER.warn(
                    "[Rtp] Anchor {} {} warm timed out at {}/{} chunks — credited radius {}, resumes next idle window.",
                    w.anchor.x, w.anchor.z, ready, w.totalChunks, done);
                warming = null;
            }
            return;
        }

        // 2. Pool short of rtpAnchorCount — pick another. Off-thread: the ocean-avoid biome samples
        //    hit the full uncached Terralith/Tectonic noise stack (the 1.6.29 main-thread lesson).
        if (RtpAnchors.needsMore()) {
            if (pickingAnchor.compareAndSet(false, true)) {
                double angle = RtpAnchors.nextAngle();
                java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> pickAnchorTarget(level, angle), net.minecraft.Util.backgroundExecutor())
                    .whenCompleteAsync((t, err) -> {
                        pickingAnchor.set(false);
                        if (err != null || t == null) {
                            CoffeesAeroAuth.LOGGER.warn("[Rtp] Anchor selection failed: {}",
                                err != null ? err.toString() : "null");
                            return;
                        }
                        RtpAnchors.add(t[0], t[1]);
                        CoffeesAeroAuth.LOGGER.info("[Rtp] Anchor {} of {} picked at {} {} ({}ms).",
                            RtpAnchors.size(), RtpAnchors.configuredCount(), t[0], t[1], t[2]);
                    }, server);
            }
            return;
        }

        // 3. All picked; generate the next cold one.
        // 3. All picked; generate the next one short of the target radius. NOTE the radius: the
        //    warmer deliberately overshoots what a player /rtp requires by rtpAnchorWarmMargin, so
        //    the rim the player lands inside has generated neighbours and loads instead of
        //    generating (the 2026-08-05 152s-rim bug). Rings already on disk re-walk at load speed.
        RtpAnchors.Anchor cold = RtpAnchors.nextCold();
        if (cold == null) return;
        WarmJob w = new WarmJob(level, cold, RtpAnchors.warmTargetRadius());
        warming = w;
        addRingTickets(w.level, w.center, -1, w.ticketRadius);
        CoffeesAeroAuth.LOGGER.info("[Rtp] Warming anchor {} {} from radius {} to {} ({}x{} chunks) in the background.",
            cold.x, cold.z, cold.warmRadius, w.radius, w.radius * 2 + 1, w.radius * 2 + 1);
    }

    /**
     * Largest ring radius (≤ {@code radius}) whose every chunk is FULL right now — the honest
     * "how warm is this really" measure, used to credit interrupted warm jobs. Returns -1 when even
     * the centre chunk is not ready. Pure {@code getChunkNow} map lookups, no blocking.
     */
    private static int completedRadius(ServerLevel level, ChunkPos center, int radius) {
        ServerChunkCache chunks = level.getChunkSource();
        for (int r = 0; r <= radius; r++) {
            for (int cx = center.x - r; cx <= center.x + r; cx++) {
                for (int cz = center.z - r; cz <= center.z + r; cz++) {
                    if (Math.max(Math.abs(cx - center.x), Math.abs(cz - center.z)) != r) continue;
                    if (chunks.getChunkNow(cx, cz) == null) return r - 1;
                }
            }
        }
        return radius;
    }

    // ── Per-tick driver (called from the mod's server-tick listener) ──────────

    /** Tick-gap stall detector: last onServerTick wall time while any rtp is pending. */
    private static long lastTickAtMs = 0;

    public static void onServerTick(MinecraftServer server) {
        // Queue upkeep runs even with no active pregen: launch the next pilot the tick after the
        // previous request ends, sweep out queued players who moved/left, show waiting positions.
        if (!queue.isEmpty()) {
            if (server.getTickCount() % 20 == 0) sweepAndDisplayQueue(server);
            pumpQueue(server);
        }
        // Anchor upkeep gets the idle windows only — never while a player is waiting on a pregen.
        if (queue.isEmpty() && pending.isEmpty() && choosing.isEmpty()) {
            tickAnchors(server);
        }
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

            // ANCHOR TRUTH CHECK (1.7.8). A genuinely generated anchor is a DISK LOAD — the whole
            // 15x15 came back in under a second when it was measured. Still incomplete after the
            // grace window means chunks are being GENERATED, so the stored warmth is a lie however
            // it got there (soft rim, a raised rtpPregenRadius, a hand-edited or restored world).
            // Demote it: this player keeps their destination, the next one is not sent here again,
            // and the warmer regenerates it properly. Self-healing beats trusting the file.
            // ONLY hardened anchors are audited. A known soft-rim one (pre-1.7.8 file, or a job the
            // margin top-up has not reached yet) is EXPECTED to generate its outer ring, and
            // demoting it would trade a 56-chunk wait for the 225-chunk virgin random target that
            // /rtp falls back to once the pool is empty — worse on every axis. Those get fixed by
            // the warmer, not by being thrown away.
            if (p.anchor != null && !p.anchorAudited && !generated && elapsedMs > ANCHOR_GRACE_MS
                    && RtpAnchors.hardened(p.anchor)) {
                p.anchorAudited = true;
                RtpAnchors.demote(p.anchor, p.readyChunks, p.totalChunks, p.elapsedSec());
            }

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
        return countReady(p.level, p.center, p.radius);
    }

    private static int countReady(ServerLevel level, ChunkPos center, int radius) {
        ServerChunkCache chunks = level.getChunkSource();
        int ready = 0;
        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
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
        // Anchor arrivals get scattered inside the pregenerated bubble, otherwise N anchors become
        // N exact blocks that get stripped bare and built over. The offset stays well inside the
        // generated area — landing on the rim would re-introduce the arrival worldgen burst that
        // the whole pregen exists to avoid (the 2026-07-17 36s freeze).
        int tx = p.x, tz = p.z;
        if (p.anchor != null) {
            int spread = Math.min(AuthConfig.RTP_ANCHOR_SPREAD.get(), p.radius * 16 - 16);
            if (spread > 0) {
                var rng = ThreadLocalRandom.current();
                tx += rng.nextInt(-spread, spread + 1);
                tz += rng.nextInt(-spread, spread + 1);
            }
        }
        BlockPos spot = findDryColumn(p.level, tx, tz, p.radius);
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
        if (p.anchor != null) RtpAnchors.markUsed(p.anchor);   // LRU rotation across the pool
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
        TextUtil.msg(player, text);
    }
}
