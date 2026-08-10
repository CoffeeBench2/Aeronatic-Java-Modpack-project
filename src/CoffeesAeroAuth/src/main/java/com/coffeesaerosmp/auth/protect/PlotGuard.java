package com.coffeesaerosmp.auth.protect;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps players out of SABLE PLOT SPACE, where their chunk tickets crash the server.
 *
 * <p>THE CRASH (2026-08-04 14:05, live):
 * <pre>
 *   NullPointerException: Cannot invoke "ChunkHolder.increaseGenerationRefCount()"
 *                         because "chunkholder" is null
 *     at ChunkMap.acquireGeneration(ChunkMap.java:601)
 *     at ChunkGenerationTask.create -> ChunkMap.prepareTickingChunk
 *     -> DistanceManager.runAllUpdates            <- MAIN TICK LOOP
 * </pre>
 * A player was standing at {@code x=20481019 / z=20573191} in Live_Finale — ~20.5M blocks
 * out, inside the region Sable parks sub-level DATA in. Their view-distance tickets asked
 * vanilla to generate chunks there; Sable's {@code plot.ChunkMapMixin} owns that region and
 * the neighbour ChunkHolder simply is not in {@code updatingChunkMap}. Vanilla does not
 * null-check it, so the NPE lands on the tick loop and takes the server down.
 *
 * <p>WHY THE FIX IS HERE AND NOT IN ChunkMap: {@code acquireGeneration} feeds a
 * {@code StaticCache2D<GenerationChunkHolder>} that {@code applyStep} then dereferences.
 * Returning null on a missing holder just moves the same NPE one frame downstream, and
 * fabricating a holder risks corrupting chunk state — strictly worse than a clean crash.
 * The invariant vanilla relies on is "no tickets in plot space", so that is what we enforce.
 *
 * <p>TWO LAYERS, because there are two ways in:
 * <ol>
 *   <li>{@link #clampOnLoad} — from a TAIL mixin on {@code ServerPlayer.readAdditionalSaveData}.
 *       {@code Entity.load} sets Pos (line 1742) and then calls readAdditionalSaveData (1797),
 *       so this runs with the position loaded but BEFORE the player joins a level and before
 *       any ticket exists. This is what breaks the crash LOOP: the bad position lives in
 *       playerdata/&lt;uuid&gt;.dat, so without this every login re-crashes and the only repair
 *       is offline NBT surgery.</li>
 *   <li>{@link #onServerTick} — 1Hz sweep for the mid-session case: a ship disassembly or a
 *       failed sub-level load dropping someone into plot space while already online.</li>
 * </ol>
 *
 * <p>SAFETY: a player on a normal assembled ship has NORMAL world coordinates — plot space
 * holds sub-level data, not players (2026-08-02: "plot space != world space"). So anything
 * past the limit is broken state. That is an assumption about Sable's internals though, so
 * {@code plotGuardRescue=false} ships as the default: it logs offenders and moves nobody
 * until the console output confirms only genuinely stuck players trip it.
 */
public final class PlotGuard {

    /** Rescues per player per boot, so a pathological case logs once instead of 20x/second. */
    private static final Map<UUID, Long> lastActionAt = new ConcurrentHashMap<>();
    /** Names already reported in log-only mode — same anti-spam reason. */
    private static final Set<UUID> reported = ConcurrentHashMap.newKeySet();

    private static final long ACTION_COOLDOWN_MS = 5_000;

    private PlotGuard() {}

    // ── Config accessors ──────────────────────────────────────────────────────
    // Defensive: this runs during player load, and a SERVER config that has not finished
    // loading throws "Cannot get config value before config is loaded" from ConfigValue.get().
    // That exact throw during a login handshake is what crashed every client joining on
    // 2026-07-20 (aero_cam_sync 1.3.5). Never let this class be that bug.

    private static boolean enabled() {
        try { return AuthConfig.PLOTGUARD_ENABLED.get(); } catch (Exception e) { return true; }
    }

    private static boolean rescueMode() {
        try { return AuthConfig.PLOTGUARD_RESCUE.get(); } catch (Exception e) { return false; }
    }

    private static int limit() {
        try { return AuthConfig.PLOTGUARD_LIMIT.get(); } catch (Exception e) { return 1_000_000; }
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    /** Chebyshev test against the limit — plot space is ~20.5M out, survival play never nears 1M. */
    public static boolean isOutOfBounds(double x, double z) {
        int lim = limit();
        return Math.abs(x) > lim || Math.abs(z) > lim;
    }

    // ── Layer 1: pre-ticket clamp on player load ──────────────────────────────

    /**
     * Called from the ServerPlayer mixin with the saved position applied but no level joined
     * and no chunk ticket created yet. Repositions in place — no teleport, no packets, nothing
     * that needs a level — which is the only reason this is safe to do this early.
     *
     * <p>Runs even in log-only mode. The whole point of this layer is that the alternative is a
     * guaranteed server crash plus manual NBT editing, so "observe and let it crash" is not a
     * useful option here; the mode switch only governs the live sweep below.
     */
    public static void clampOnLoad(ServerPlayer player) {
        if (!enabled()) return;
        double x = player.getX(), z = player.getZ();
        if (!isOutOfBounds(x, z)) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;                       // defensive; never seen in practice
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;

        BlockPos spawn = overworld.getSharedSpawnPos();
        String name = player.getGameProfile().getName();

        CoffeesAeroAuth.LOGGER.warn(
            "[PlotGuard] {} loaded at {} / {} / {} — inside Sable plot space. Clamping to spawn {} / {} / {} "
                + "BEFORE any chunk ticket is created (this is the ChunkMap.acquireGeneration crash).",
            name, Math.round(x), Math.round(player.getY()), Math.round(z),
            spawn.getX(), spawn.getY(), spawn.getZ());

        // stopRiding first: if they were seated on a sub-level entity, PlayerList.placeNewPlayer
        // re-mounts from RootVehicle after load and the mount re-asserts the rider position,
        // silently undoing the clamp. Same trap as the manual .dat repair.
        if (player.isPassenger()) {
            try { player.stopRiding(); }
            catch (Exception e) {
                CoffeesAeroAuth.LOGGER.warn("[PlotGuard] stopRiding failed for {}: {}", name, e.toString());
            }
        }
        player.setDeltaMovement(0, 0, 0);
        player.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        lastActionAt.put(player.getUUID(), System.currentTimeMillis());
    }

    // ── Layer 1b: teleport destinations ───────────────────────────────────────

    /**
     * True when a teleport to {@code (x, z)} must be refused because the destination is in plot space.
     *
     * <p>WHY THIS IS SEPARATE FROM THE TICK SWEEP: {@code ServerPlayer.teleportTo(ServerLevel,
     * double, double, double, Set, float, float)} adds a {@code POST_TELEPORT} region ticket at the
     * destination on its FIRST TWO LINES (ServerPlayer.java:1425), before the player has moved.
     * That ticket is what asks the chunk system to generate plot-space chunks, so by the time the
     * 1Hz sweep notices the player is out of bounds the server has already crashed. The only place
     * this can be stopped is at HEAD of the teleport itself.
     *
     * <p>THE WAYSTONE CASE (reported 2026-08-05): a waystone placed inside a ship stores the
     * position of its BLOCK, and ship blocks live in Sable's plot space at ~20,000,000. Warping to
     * it therefore teleports the player to plot space — the same NPE, reached through a completely
     * ordinary player action. Players are never legitimately at plot-space coordinates (blocks are
     * addressed there, players are not), so refusing is safe.
     */
    public static boolean shouldBlockTeleport(ServerPlayer player, double x, double z) {
        if (!enabled()) return false;
        try { if (!AuthConfig.PLOTGUARD_BLOCK_TELEPORTS.get()) return false; }
        catch (Exception ignored) { /* config not loaded yet — guard anyway, a crash is worse */ }
        if (!isOutOfBounds(x, z)) return false;

        String name = player.getGameProfile().getName();
        CoffeesAeroAuth.LOGGER.warn(
            "[PlotGuard] REFUSED teleport of {} to {} / {} — destination is inside Sable plot space. "
                + "Most likely a waystone (or other saved location) placed on a ship: the stored position "
                + "is the BLOCK's plot-space coordinate, not the ship's world position.",
            name, Math.round(x), Math.round(z));

        player.sendSystemMessage(Component.literal(
            "§c[PlotGuard] §7That destination is inside a ship's internal storage, not the world. "
                + "§fWaystones placed on ships can't be warped to§7 — place it on solid ground instead."));
        return true;
    }

    // ── Layer 2: live sweep ───────────────────────────────────────────────────

    /** 1Hz from the mod's ServerTickEvent.Post listener. */
    public static void onServerTick(MinecraftServer server) {
        if (!enabled()) return;
        if (server.getTickCount() % 20 != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (isOutOfBounds(player.getX(), player.getZ())) {
                handleLive(player);
            }
        }
    }

    private static void handleLive(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        long now = System.currentTimeMillis();

        if (!rescueMode()) {
            if (!reported.add(uuid)) return;                 // once per player per boot
            CoffeesAeroAuth.LOGGER.warn(
                "[PlotGuard] {} is at {} / {} (limit {}) — plotGuardRescue=false, NOT moving them. "
                    + "Set plotGuardRescue=true once you have confirmed only stuck players appear here.",
                name, Math.round(player.getX()), Math.round(player.getZ()), limit());
            return;
        }

        Long last = lastActionAt.get(uuid);
        if (last != null && now - last < ACTION_COOLDOWN_MS) return;
        lastActionAt.put(uuid, now);

        ServerLevel overworld = player.getServer().overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();

        CoffeesAeroAuth.LOGGER.warn("[PlotGuard] Rescuing {} from {} / {} to spawn.",
            name, Math.round(player.getX()), Math.round(player.getZ()));

        if (player.isPassenger()) {
            try { player.stopRiding(); } catch (Exception ignored) {}
        }
        player.setDeltaMovement(0, 0, 0);
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
            Set.of(), player.getYRot(), player.getXRot());
        player.sendSystemMessage(Component.literal(
            "§c[PlotGuard] §7You were outside the world and have been returned to spawn."));
    }

    /** Called on logout so a rejoining player is evaluated fresh. */
    public static void forget(UUID uuid) {
        lastActionAt.remove(uuid);
        reported.remove(uuid);
    }
}
