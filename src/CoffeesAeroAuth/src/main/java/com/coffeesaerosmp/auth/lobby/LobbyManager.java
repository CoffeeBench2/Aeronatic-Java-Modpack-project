package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.ProfileStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the single shared login lobby in the {@code coffees_aero_auth:auth_lobby} void dimension.
 *
 * <h2>Replaces {@code PrivateRoomManager} — private rooms are gone (2026-09-09)</h2>
 * The original design gave every player their own room scattered from X=1,000,000 to 3,000,000, and
 * each login into a cold far region synchronously loaded chunks (amplified by Sable), stalling the
 * server 9–75 s per join. That was replaced by one shared room near origin, permanently force-loaded.
 * The private-room remnants — per-slot lookups, the bundled room template, the DB columns — were then
 * removed in stages, and this class completes it: **the room concept, the template system, and every
 * block-placing path are deleted outright** at the owner's instruction.
 *
 * <h2>🔴 This class NEVER writes blocks into the lobby</h2>
 * That is the whole point, and it is a behavioural guarantee rather than a config default. Three
 * separate code paths used to stamp smooth stone into a hand-built map:
 * <ol>
 *   <li>{@code ensureSpawnPlatform} from {@code teleportToRoom} — on EVERY teleport into the lobby;</li>
 *   <li>{@code ensureSpawnPlatform} from {@code ensureSharedRoom} — once per server run;</li>
 *   <li>{@code ensureSafeFooting} — a 5×5 slab under any authenticating player with 5 blocks of air
 *       below them, which <b>overwrote existing blocks unconditionally</b> (no air check, unlike the
 *       other two).</li>
 * </ol>
 * (1) was guarded behind {@code lobbyPreplacedBuild} on 2026-09-08 and (2) already was — but (3) was
 * not, so platforms kept appearing. An op flying in creative over the lobby is exactly the case that
 * trips it: 5 blocks of air below, so it fires and stamps stone into the build.
 *
 * <p>All three are now deleted rather than gated, because a config flag that must stay set to avoid
 * damaging the map is a worse guarantee than not having the code. The anti-void protection that (3)
 * genuinely provided is preserved by {@link #rescueFromVoid} — which <b>teleports the player</b> to
 * the spawn pad instead of building them a floor.
 *
 * <p>{@link #clearSpawnPlatform} is kept: it removes platforms already written, which deleting the
 * placement code cannot undo.
 */
public class LobbyManager {

    public static final ResourceKey<Level> LOBBY_DIMENSION = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("coffees_aero_auth", "auth_lobby")
    );

    /** Single shared lobby anchor near origin (force-loaded 24/7). */
    private static final int ANCHOR_X = 0;
    private static final int ANCHOR_Z = 0;

    /** Standing ring: frozen login-flow players get distinct pads so they do not stack inside each other. */
    private static final int    RING_SIZE   = 12;
    private static final double RING_RADIUS = 3.0;

    private final Set<Integer>       claimedRingSpots = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> ringSpotByUuid   = new ConcurrentHashMap<>();

    private final MinecraftServer server;

    public LobbyManager(MinecraftServer server) {
        this.server = server;
    }

    // ── Spawn pad + standing ring ─────────────────────────────────────────────

    /** The configured lobby spawn pad (double coords). */
    public static double[] spawnPad() {
        return new double[]{
            AuthConfig.LOBBY_SPAWN_X.get(),
            AuthConfig.LOBBY_SPAWN_Y.get(),
            AuthConfig.LOBBY_SPAWN_Z.get()
        };
    }

    /**
     * Which way a player faces when placed on the spawn pad.
     *
     * <p>Minecraft's yaw: <b>0 = +Z (south), 90 = −X (west), 180 = −Z (north), −90 = +X (east)</b>.
     * Pitch is <b>positive DOWN</b>. To face a point, from the pad:
     * <pre>
     *   yaw   = -atan2(dx, dz) in degrees
     *   pitch = -atan2(dy, sqrt(dx² + dz²)) in degrees      // dy measured from EYE height, +1.62
     * </pre>
     */
    public static float spawnYaw() {
        try { return AuthConfig.LOBBY_SPAWN_YAW.get().floatValue(); } catch (Exception e) { return 180.0f; }
    }

    public static float spawnPitch() {
        try { return AuthConfig.LOBBY_SPAWN_PITCH.get().floatValue(); } catch (Exception e) { return 0.0f; }
    }

    /** A distinct standing pad on the ring for index i. */
    private static double[] ringSpot(int i) {
        double[] c = spawnPad();
        double ang = (2 * Math.PI * i) / RING_SIZE;
        return new double[]{ c[0] + RING_RADIUS * Math.cos(ang), c[1], c[2] + RING_RADIUS * Math.sin(ang) };
    }

    /** Transient standing spot for a frozen player; assigned once per join, released on leave. */
    public double[] getFrozenSpotFor(UUID uuid) {
        Integer i = ringSpotByUuid.get(uuid);
        if (i == null) {
            int pick = 0;
            while (pick < RING_SIZE && !claimedRingSpots.add(pick)) pick++;
            if (pick >= RING_SIZE) pick = Math.floorMod(uuid.hashCode(), RING_SIZE); // overflow: allow stacking
            i = pick;
            ringSpotByUuid.put(uuid, i);
        }
        return ringSpot(i);
    }

    /** Release a player's ring spot on logout. */
    public void releaseFrozenSpot(UUID uuid) {
        Integer i = ringSpotByUuid.remove(uuid);
        if (i != null) claimedRingSpots.remove(i);
    }

    // ── Teleportation ─────────────────────────────────────────────────────────

    /** Teleports a player onto the shared lobby spawn pad. Places nothing. */
    public void teleportToLobby(ServerPlayer player) {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        if (lobby == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§cLobby dimension unavailable — tell an admin."));
            return;
        }
        double[] pad = spawnPad();
        player.teleportTo(lobby, pad[0], pad[1], pad[2], Set.of(), spawnYaw(), spawnPitch());
    }

    /** Teleports player to the overworld world spawn. */
    public void teleportToSpawn(ServerPlayer player) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld,
            spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
            Set.of(), overworld.getSharedSpawnAngle(), 0.0f);
    }

    /**
     * Anti-void net for an authenticating player sitting over nothing in the lobby.
     *
     * <p>🔴 Replaces {@code ensureSafeFooting}, which solved this by stamping a 5×5 smooth stone slab
     * under the player — overwriting whatever was there, in a hand-built map, on any login with 5
     * blocks of air below. Moving the player costs nothing and touches no blocks, so it is correct on
     * a pre-placed build and a generated one alike.
     *
     * <p>Safe to teleport from here: this runs on the authentication-complete path, not from a tick
     * handler. Teleporting a player from {@code PlayerTickEvent} is the trap that froze the whole
     * lobby on 2026-09-07 — {@code absMoveTo} reverts it and {@code awaitingPositionFromClient} stays
     * armed forever.
     */
    public void rescueFromVoid(ServerPlayer player) {
        if (player.level().dimension() != LOBBY_DIMENSION) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        int py = (int) Math.floor(player.getY());
        for (int dy = 0; dy <= 4; dy++) {
            if (!level.getBlockState(new BlockPos(px, py - dy, pz)).isAir()) return;   // already grounded
        }
        // 🔴 The pad is not automatically safe. On the SMP, lobbySpawnX/Y/Z are still the unset
        // defaults (0,0,0), and in a VOID dimension that is itself void — so teleporting a
        // void-stuck player to the pad would drop them from one endless fall into another. The old
        // block-stamping version accidentally handled this by building a floor wherever they were;
        // moving them instead has to check where it is moving them TO.
        //
        // Only reachable on the SMP's legacy auth_lobby (unused since the lobby split, and confirmed
        // empty on 2026-09-09), but an unreachable path that strands a player is still worth closing.
        double[] pad = spawnPad();
        if (hasFooting(level, (int) Math.floor(pad[0]), (int) Math.floor(pad[1]), (int) Math.floor(pad[2]))) {
            player.teleportTo(level, pad[0], pad[1], pad[2], Set.of(), spawnYaw(), spawnPitch());
            CoffeesAeroAuth.LOGGER.warn("[Lobby] Moved {} to the spawn pad (was over void in the lobby).",
                player.getGameProfile().getName());
            return;
        }

        // Pad is void too — the overworld spawn is force-loaded and known solid, so it is the only
        // landing spot we can actually vouch for.
        teleportToSpawn(player);
        CoffeesAeroAuth.LOGGER.warn("[Lobby] {} was over void in the lobby AND the lobby pad ({}, {}, {}) "
            + "has no footing — sent to the overworld spawn instead. Check lobbySpawnX/Y/Z.",
            player.getGameProfile().getName(), pad[0], pad[1], pad[2]);
    }

    /** True if any of the 5 blocks below {@code y} is solid enough to stand on. */
    private static boolean hasFooting(ServerLevel level, int x, int y, int z) {
        for (int dy = 1; dy <= 5; dy++) {
            if (!level.getBlockState(new BlockPos(x, y - dy, z)).isAir()) return true;
        }
        return false;
    }

    // ── Cleanup of platforms written by older builds ──────────────────────────

    /**
     * Removes smooth stone on the spawn-pad floor layer, left behind by the placement code that used
     * to run here.
     *
     * <p>Deleting the placement stops NEW platforms; it cannot undo what is already written, and on a
     * hand-built lobby that leaves a slab sitting in the middle of someone's build.
     *
     * <p>⚠️ It can only match on the block type. The original spawn-pad placement only ever filled
     * AIR, but the emergency 5×5 overwrote whatever was there — so if the build's own floor at that
     * layer is also smooth stone, this takes those blocks too. There is no way to tell them apart
     * after the fact. That is why it is an explicit op command and not something that runs at boot:
     * a few dozen blocks is a short rebuild, a silent boot-time wipe of someone's floor is not.
     *
     * <p>⚠️ Only sweeps around the SPAWN PAD. The emergency platform was placed wherever the player
     * happened to be floating, so a slab somewhere else in the lobby will not be found by this —
     * widen {@code radius} to sweep further, or remove those by hand.
     *
     * @return how many blocks were removed, or -1 if the lobby dimension is unavailable.
     */
    public int clearSpawnPlatform(MinecraftServer srv, int radius) {
        ServerLevel lobby = srv.getLevel(LOBBY_DIMENSION);
        if (lobby == null) return -1;
        double[] pad = spawnPad();
        int sx = (int) Math.floor(pad[0]);
        int sz = (int) Math.floor(pad[2]);
        int y  = (int) Math.floor(pad[1]) - 1;
        int removed = 0;
        for (int x = sx - radius; x <= sx + radius; x++) {
            for (int z = sz - radius; z <= sz + radius; z++) {
                BlockPos p = new BlockPos(x, y, z);
                if (lobby.getBlockState(p).is(Blocks.SMOOTH_STONE)) {
                    lobby.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
                    removed++;
                }
            }
        }
        return removed;
    }

    // ── Startup / force-load ──────────────────────────────────────────────────

    /**
     * 🔴 NO FORCE-LOADING (owner, 2026-10-04: "remove the forceloading done by authmod, any coding").
     *
     * <p>This used to permanently force-load 17×17 = 289 chunks of {@code auth_lobby} on EVERY server —
     * the survival servers included, which have not used that dimension since the standalone lobby
     * replaced it. Now it RELEASES whatever is still forced there instead. Nothing but this mod ever
     * forced chunks in {@code auth_lobby}, so releasing all of them cannot undo anyone else's work.
     * The lobby server keeps its pad loaded the ordinary way: players are standing on it.
     */
    public void initSharedLobby() {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        if (lobby == null) return;
        int held = lobby.getForcedChunks().size();
        if (held > 0) {
            int released = ForceloadManager.clearAround(lobby, ANCHOR_X, ANCHOR_Z, ForceloadManager.MAX_CLEAR_RADIUS);
            CoffeesAeroAuth.LOGGER.info("[Forceload] auth_lobby: released {} of {} chunk(s) this mod used to force-load.",
                released, held);
        }
    }

    /** Runs on server start: force-load the lobby and set up the overworld spawn. */
    public void runStartup(ProfileStore store) {
        initSharedLobby();
        initSpawnArea();
    }

    /**
     * Sets the overworld world-spawn (where new players appear and /spawn lands) to the configured
     * coordinates and facing. NO force-loading any more — see {@link #initSharedLobby}.
     *
     * <p>⚠️ This overwrites the world's own spawn every boot, so the config IS the spawn. The default
     * (0, 112, -1) moved Season 3's spawn to the origin on its first boot with this mod (2026-10-04);
     * set overworldSpawnX/Y/Z/Yaw for every new world.
     *
     * <p>One-time cleanup: the spawn ring this mod used to force-load is released — around the origin
     * (where the default config put it) and around the configured spawn — then a stamp stops it running
     * again, so a /forceload an admin adds later near spawn is never undone.
     */
    public void initSpawnArea() {
        ServerLevel ow = server.overworld();
        if (ow == null) return;
        int sx = AuthConfig.OVERWORLD_SPAWN_X.get();
        int sy = AuthConfig.OVERWORLD_SPAWN_Y.get();
        int sz = AuthConfig.OVERWORLD_SPAWN_Z.get();
        float yaw = (float) (double) AuthConfig.OVERWORLD_SPAWN_YAW.get();
        ow.setDefaultSpawnPos(new BlockPos(sx, sy, sz), yaw);
        CoffeesAeroAuth.LOGGER.info("[Spawn] Overworld spawn set to ({}, {}, {}) facing yaw {}.", sx, sy, sz, yaw);
        releaseLegacySpawnForceload(ow, sx, sz);
    }

    private static final String FORCELOAD_STAMP = "forceload_released_v1.txt";

    private void releaseLegacySpawnForceload(ServerLevel ow, int sx, int sz) {
        java.nio.file.Path stamp = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("coffeesaeroauth").resolve(FORCELOAD_STAMP);
        if (java.nio.file.Files.exists(stamp)) return;
        int before = ow.getForcedChunks().size();
        // 16 = the old spawnForceloadRadiusChunks maximum, so every ring the old code could have placed.
        int released = ForceloadManager.clearAround(ow, 0, 0, 16);
        if (Math.abs(sx) > 16 * 16 || Math.abs(sz) > 16 * 16) released += ForceloadManager.clearAround(ow, sx, sz, 16);
        int left = ow.getForcedChunks().size();
        CoffeesAeroAuth.LOGGER.warn("[Forceload] One-time cleanup: released {} overworld chunk(s) the old spawn "
            + "force-load held ({} before, {} still forced by others — see /authmod forceload).", released, before, left);
        try {
            java.nio.file.Files.createDirectories(stamp.getParent());
            java.nio.file.Files.writeString(stamp, "released " + released + " overworld chunk(s), " + left
                + " left forced by other sources, " + java.time.LocalDateTime.now() + System.lineSeparator());
        } catch (java.io.IOException e) {
            CoffeesAeroAuth.LOGGER.warn("[Forceload] could not write {}: {}", stamp, e.getMessage());
        }
    }
}
