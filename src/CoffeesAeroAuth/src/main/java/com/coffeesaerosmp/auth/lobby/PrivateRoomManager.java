package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.db.ProfileStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the SINGLE shared public login lobby. All login-flow players share one room near origin in
 * the {@code coffees_aero_auth:auth_lobby} void dimension; the region is permanently force-loaded at
 * startup so it never goes cold. This replaced the old per-player rooms scattered from X=1,000,000 to
 * 3,000,000 — each login into a cold far region was synchronously loading chunks (amplified by Sable),
 * which stalled the server 9–75 s per join. The real (Middle Ages) build is pasted into the dimension
 * once and persisted by the force-load; a small platform is placed here as anti-void fallback.
 */
public class PrivateRoomManager {

    public static final ResourceKey<Level> LOBBY_DIMENSION = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("coffees_aero_auth", "auth_lobby")
    );

    // Single shared lobby anchor near origin (forceloaded 24/7).
    private static final int    ANCHOR_X = 0;
    private static final int    ANCHOR_Z = 0;
    private static final int    FLOOR_Y  = 100;
    private static final int    ROOM_W   = 15;   // used only by the small-template /lobby save box
    private static final int    ROOM_D   = 10;
    private static final int    ROOM_H   = 10;
    private static final ResourceLocation LOBBY_TEMPLATE =
        ResourceLocation.fromNamespaceAndPath("coffees_aero_auth", "lobby_room");
    private static final String BUNDLED_TEMPLATE_RESOURCE = "/coffees_aero_auth/lobby_room.nbt";

    // Standing ring: frozen login-flow players get distinct pads so they don't stack.
    private static final int    RING_SIZE   = 12;
    private static final double RING_RADIUS = 3.0;

    private volatile boolean sharedBuilt = false;                  // shared room placed this run
    private final Set<Integer>       claimedRingSpots = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> ringSpotByUuid   = new ConcurrentHashMap<>();

    private final MinecraftServer server;
    private StructureTemplate bundledTemplate;   // cached after first successful load
    private boolean bundledTemplateMissing;      // true once load has failed, to stop retrying

    public PrivateRoomManager(MinecraftServer server) {
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

    // ── Slot management (roomSlot is now dead data; kept for call-site compatibility) ──

    /** Returns a stable pseudo-slot for legacy {@code profile.roomSlot} persistence. Unused for layout. */
    public int assignSlot(UUID uuid) {
        return Math.floorMod(uuid.hashCode() & 0x7FFF_FFFF, 10_000);
    }

    // ── Teleportation ─────────────────────────────────────────────────────────

    /** Teleports a player into the single shared lobby (slot ignored — the lobby is shared). */
    public void teleportToRoom(ServerPlayer player, int slot) {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        if (lobby == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c[Auth] Lobby dimension failed to load — contact an admin. UUID: " + player.getUUID()));
            CoffeesAeroAuth.LOGGER.error("Auth lobby dimension not loaded for player {}!", player.getGameProfile().getName());
            return;
        }
        ensureSharedRoom(lobby);
        double[] pad = spawnPad();
        ensureSpawnPlatform(lobby, (int) Math.floor(pad[0]), (int) Math.floor(pad[1]), (int) Math.floor(pad[2]));
        player.teleportTo(lobby, pad[0], pad[1], pad[2], Set.of(), 180.0f, 0.0f);
    }

    /** Teleports player to the overworld world spawn. */
    public void teleportToSpawn(ServerPlayer player) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld,
            spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
            Set.of(), 0.0f, 0.0f);
    }

    /** Admin /lobby: drop into the shared lobby, re-placing the bundled template so design changes show. */
    public void teleportToPreview(ServerPlayer player) {
        sharedBuilt = false;
        teleportToRoom(player, 0);
    }

    /** Compatibility: the shared lobby spawn pad (old per-slot signature; slot ignored). */
    public double[] getRoomSpawnPos(int slot) {
        return spawnPad();
    }

    // ── Startup / force-load ──────────────────────────────────────────────────

    /** Permanently force-loads the lobby region around the anchor so it never goes cold (the freeze fix),
     *  then ensures a safe shared room exists. */
    public void initSharedLobby() {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        if (lobby == null) {
            CoffeesAeroAuth.LOGGER.error("[Lobby] auth_lobby dimension missing at startup — cannot force-load.");
            return;
        }
        int r = AuthConfig.LOBBY_FORCELOAD_RADIUS_CHUNKS.get();
        int cx = ANCHOR_X >> 4, cz = ANCHOR_Z >> 4;
        int n = 0;
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) { lobby.setChunkForced(x, z, true); n++; }
        ensureSharedRoom(lobby);
        CoffeesAeroAuth.LOGGER.info("[Lobby] Shared lobby ready — force-loaded {} chunks around ({}, {}).", n, ANCHOR_X, ANCHOR_Z);
    }

    /** Runs on server start: force-load + ensure the single shared lobby, and set up the overworld spawn. */
    public void runStartupCleanup(ProfileStore store) {
        initSharedLobby();
        initSpawnArea();
        CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Shared lobby startup done.");
    }

    /** Sets the overworld world-spawn to the configured coords (where /spawn + the lobby paper land players)
     *  and permanently force-loads the surrounding chunks so joining/spawning there is instant. */
    public void initSpawnArea() {
        ServerLevel ow = server.overworld();
        if (ow == null) return;
        int sx = AuthConfig.OVERWORLD_SPAWN_X.get();
        int sy = AuthConfig.OVERWORLD_SPAWN_Y.get();
        int sz = AuthConfig.OVERWORLD_SPAWN_Z.get();
        ow.setDefaultSpawnPos(new BlockPos(sx, sy, sz), 0.0f);
        int r = AuthConfig.SPAWN_FORCELOAD_RADIUS_CHUNKS.get();
        int cx = sx >> 4, cz = sz >> 4, n = 0;
        for (int x = cx - r; x <= cx + r; x++)
            for (int z = cz - r; z <= cz + r; z++) { ow.setChunkForced(x, z, true); n++; }
        CoffeesAeroAuth.LOGGER.info("[Spawn] Overworld spawn set to ({}, {}, {}) — force-loaded {} chunks.", sx, sy, sz, n);
    }

    /** Places the shared lobby build once per server run (bundled template if present) and guarantees
     *  footing at the spawn pad. Idempotent; the op pastes the real build over this and forceload persists it. */
    private void ensureSharedRoom(ServerLevel lobby) {
        if (sharedBuilt) return;
        // When the lobby build is a pre-placed map (dropped into the dimension's region folder), place NO
        // template or platform — that would carve into the map. Force-loading alone (initSharedLobby) is enough.
        if (AuthConfig.LOBBY_PREPLACED_BUILD.get()) { sharedBuilt = true; return; }
        placeTemplate(lobby, ANCHOR_X, FLOOR_Y, ANCHOR_Z);
        double[] pad = spawnPad();
        ensureSpawnPlatform(lobby, (int) Math.floor(pad[0]), (int) Math.floor(pad[1]), (int) Math.floor(pad[2]));
        sharedBuilt = true;
    }

    // ── Template build / anti-void ────────────────────────────────────────────

    /** Places the bundled lobby template at the given corner. False if it can't be loaded. */
    private boolean placeTemplate(ServerLevel level, int bx, int by, int bz) {
        StructureTemplate tmpl = loadBundledTemplate(level);
        if (tmpl == null) return false;
        net.minecraft.core.Vec3i size = tmpl.getSize();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            CoffeesAeroAuth.LOGGER.warn("[PrivateRoom] Bundled lobby template is empty ({}×{}×{}) — platform only.",
                size.getX(), size.getY(), size.getZ());
            return false;
        }
        BlockPos origin = new BlockPos(bx - 2, by - 3, bz - 2);
        tmpl.placeInWorld(level, origin, origin,
            new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
            level.getRandom(), 2);
        return true;
    }

    /** Loads the bundled lobby design from the jar; cached after first success, failure remembered. */
    private StructureTemplate loadBundledTemplate(ServerLevel level) {
        if (bundledTemplate != null)  return bundledTemplate;
        if (bundledTemplateMissing)   return null;
        try (InputStream in = PrivateRoomManager.class.getResourceAsStream(BUNDLED_TEMPLATE_RESOURCE)) {
            if (in == null) {
                CoffeesAeroAuth.LOGGER.error("[PrivateRoom] Bundled lobby template not found on classpath at {} — platform only.",
                    BUNDLED_TEMPLATE_RESOURCE);
                bundledTemplateMissing = true;
                return null;
            }
            CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            bundledTemplate = level.getStructureManager().readStructure(tag);
            net.minecraft.core.Vec3i s = bundledTemplate.getSize();
            CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Loaded bundled lobby template ({}×{}×{}).", s.getX(), s.getY(), s.getZ());
            return bundledTemplate;
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.error("[PrivateRoom] Failed to load bundled lobby template — platform only.", e);
            bundledTemplateMissing = true;
            return null;
        }
    }

    /** Stamps a guaranteed solid 7×7 platform (with 3 blocks headroom) under the spawn pad so a player
     *  can NEVER fall into the void. Idempotent: only fills air, never overwrites an existing floor. */
    private void ensureSpawnPlatform(ServerLevel level, int sx, int py, int sz) {
        int floorY = py - 1;
        for (int x = sx - 3; x <= sx + 3; x++) {
            for (int z = sz - 3; z <= sz + 3; z++) {
                BlockPos floor = new BlockPos(x, floorY, z);
                if (level.getBlockState(floor).isAir()) {
                    set(level, x, floorY, z, Blocks.SMOOTH_STONE.defaultBlockState());
                }
            }
        }
        for (int y = py; y <= py + 2; y++) {
            BlockPos head = new BlockPos(sx, y, sz);
            if (!level.getBlockState(head).isAir()) set(level, sx, y, sz, Blocks.AIR.defaultBlockState());
        }
    }

    /** Emergency anti-void net for an already-authenticated player sitting over void in the lobby. */
    public void ensureSafeFooting(ServerPlayer player) {
        if (player.level().dimension() != LOBBY_DIMENSION) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        int px = (int) Math.floor(player.getX());
        int pz = (int) Math.floor(player.getZ());
        int py = (int) Math.floor(player.getY());
        for (int dy = 0; dy <= 4; dy++) {
            if (!level.getBlockState(new BlockPos(px, py - dy, pz)).isAir()) return;   // already grounded
        }
        int floorY = py - 1;
        for (int x = px - 2; x <= px + 2; x++)
            for (int z = pz - 2; z <= pz + 2; z++)
                set(level, x, floorY, z, Blocks.SMOOTH_STONE.defaultBlockState());
        CoffeesAeroAuth.LOGGER.warn("[PrivateRoom] Emergency platform placed under {} (was over void in lobby).",
            player.getGameProfile().getName());
    }

    /** Admin /lobby save: captures a small box at the anchor as the bundled template (small edits only —
     *  large pasted builds exceed a structure template and are persisted via the force-load instead). */
    public boolean saveTemplate() {
        ServerLevel level = server.getLevel(LOBBY_DIMENSION);
        if (level == null) return false;
        BlockPos start = new BlockPos(ANCHOR_X - 2, FLOOR_Y - 3, ANCHOR_Z - 2);
        net.minecraft.core.Vec3i size = new net.minecraft.core.Vec3i(ROOM_W + 4, ROOM_H + 4, ROOM_D + 4);
        var mgr = level.getStructureManager();
        var t = mgr.getOrCreate(LOBBY_TEMPLATE);
        t.fillFromWorld(level, start, size, true, null);
        boolean ok = mgr.save(LOBBY_TEMPLATE);
        if (ok) {
            sharedBuilt = false;   // re-place from the new template on next visit
            CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Saved lobby template from shared room (X={}).", ANCHOR_X);
        }
        return ok;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, 2);
    }
}
