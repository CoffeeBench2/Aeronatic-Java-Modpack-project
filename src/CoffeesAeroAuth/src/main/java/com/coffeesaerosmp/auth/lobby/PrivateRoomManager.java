package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PrivateRoomManager {

    public static final ResourceKey<Level> LOBBY_DIMENSION = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath("coffees_aero_auth", "auth_lobby")
    );

    // Room grid constants
    private static final int ROOM_BASE_X  = 1_000_000;
    private static final int ROOM_SPACING = 200;
    private static final int PREVIEW_SLOT  = 9999;   // reserved admin /lobby preview room
    private static final ResourceLocation LOBBY_TEMPLATE =
        ResourceLocation.fromNamespaceAndPath("coffees_aero_auth", "lobby_room");
    private static final int FLOOR_Y      = 100;
    private static final int ROOM_W       = 15;
    private static final int ROOM_D       = 10;
    private static final int ROOM_H       = 10;

    // Spawn offset from room base corner (player lands here on teleport)
    private static final double SPAWN_OX = 7.5;
    private static final double SPAWN_OY = 1.0;
    private static final double SPAWN_OZ = 5.5;

    // Bundled lobby design shipped inside the mod jar (a vanilla structure-template NBT, exported via
    // /lobby save then committed to resources). Loaded once and placed automatically on every room
    // build — no admin /lobby save needed, and immune to the unreliable world-folder save/load round
    // trip that left the on-disk template empty before.
    private static final String BUNDLED_TEMPLATE_RESOURCE = "/coffees_aero_auth/lobby_room.nbt";

    private final Set<Integer> builtRooms = ConcurrentHashMap.newKeySet();
    private final Set<Integer> usedSlots  = ConcurrentHashMap.newKeySet();
    private final MinecraftServer server;

    private StructureTemplate bundledTemplate;   // cached after first successful load
    private boolean bundledTemplateMissing;      // true once load has failed, to stop retrying

    public PrivateRoomManager(MinecraftServer server) {
        this.server = server;
    }

    // ── Slot management ───────────────────────────────────────────────────────

    public int assignSlot(UUID uuid) {
        int base = (uuid.hashCode() & 0x7FFF_FFFF) % 10_000;
        int slot = base;
        while (!usedSlots.add(slot)) {
            slot = (slot + 1) % 10_000;
        }
        return slot;
    }

    public void releaseSlot(int slot) {
        usedSlots.remove(slot);
        builtRooms.remove(slot);
    }

    // ── Teleportation ─────────────────────────────────────────────────────────

    /** Teleports player to their private room, building it first if needed. */
    public void teleportToRoom(ServerPlayer player, int slot) {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        if (lobby == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§c[Auth] Lobby dimension failed to load — contact an admin. UUID: "
                + player.getUUID()));
            CoffeesAeroAuth.LOGGER.error("Auth lobby dimension not loaded for player {}!", player.getGameProfile().getName());
            return;
        }
        if (!builtRooms.contains(slot)) {
            buildRoom(lobby, slot);
            builtRooms.add(slot);
        }
        int baseX = ROOM_BASE_X + slot * ROOM_SPACING;
        // Bulletproof anti-void: guarantee solid footing at the spawn point on EVERY teleport, even
        // if the cached room's blocks never persisted, were edited away, or a bad template placed
        // nothing. Cheap + idempotent — the lobby dimension is pure void, so this is the safety net.
        ensureSpawnPlatform(lobby, baseX, FLOOR_Y, 0);
        player.teleportTo(lobby,
            baseX + SPAWN_OX, FLOOR_Y + SPAWN_OY, SPAWN_OZ,
            Set.of(), 180.0f, 0.0f);
    }

    /** Teleports player to the overworld spawn point. */
    public void teleportToSpawn(ServerPlayer player) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld,
            spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
            Set.of(), 0.0f, 0.0f);
    }

    /** Admin /lobby: drop into a reserved preview room, always rebuilt so design changes show. */
    public void teleportToPreview(ServerPlayer player) {
        builtRooms.remove(PREVIEW_SLOT);
        usedSlots.add(PREVIEW_SLOT);
        teleportToRoom(player, PREVIEW_SLOT);
    }

    /** Returns the expected frozen position for a player in this slot (in the lobby dimension). */
    public double[] getRoomSpawnPos(int slot) {
        int baseX = ROOM_BASE_X + slot * ROOM_SPACING;
        return new double[]{baseX + SPAWN_OX, FLOOR_Y + SPAWN_OY, SPAWN_OZ};
    }

    // ── Room building ─────────────────────────────────────────────────────────

    // The lobby design is the structure NBT bundled in the jar (see loadBundledTemplate); it is placed
    // automatically on every room build. The procedural build below is the fallback used only if the
    // bundled template can't be loaded, so a room is always produced.
    private void buildRoom(ServerLevel level, int slot) {
        int bx = ROOM_BASE_X + slot * ROOM_SPACING;
        int by = FLOOR_Y;
        int bz = 0;

        // Bundled lobby design wins; otherwise build procedurally so the room is never empty void.
        if (placeTemplate(level, bx, by, bz)) return;

        // Solid foundation (3 layers below floor — prevents void gap)
        for (int x = bx - 2; x < bx + ROOM_W + 2; x++) {
            for (int z = bz - 2; z < bz + ROOM_D + 2; z++) {
                set(level, x, by - 1, z, Blocks.SMOOTH_STONE.defaultBlockState());
                set(level, x, by - 2, z, Blocks.SMOOTH_STONE.defaultBlockState());
                set(level, x, by - 3, z, Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }

        // Shell: stone brick walls + floor + ceiling
        for (int x = bx; x < bx + ROOM_W; x++) {
            for (int y = by; y < by + ROOM_H; y++) {
                for (int z = bz; z < bz + ROOM_D; z++) {
                    boolean shell = x == bx || x == bx + ROOM_W - 1
                                 || y == by || y == by + ROOM_H - 1
                                 || z == bz || z == bz + ROOM_D - 1;
                    set(level, x, y, z, shell ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }

        // Floor interior: polished andesite
        for (int x = bx + 1; x < bx + ROOM_W - 1; x++)
            for (int z = bz + 1; z < bz + ROOM_D - 1; z++)
                set(level, x, by, z, Blocks.POLISHED_ANDESITE.defaultBlockState());

        // Dark oak floor trim rows (industrial aesthetic)
        for (int x = bx + 1; x < bx + ROOM_W - 1; x++) {
            if (x % 3 == 0) {
                set(level, x, by, bz + 1, Blocks.DARK_OAK_PLANKS.defaultBlockState());
                set(level, x, by, bz + ROOM_D - 2, Blocks.DARK_OAK_PLANKS.defaultBlockState());
            }
        }

        // Copper block wall accents (east/west walls, mid-height)
        for (int y = by + 2; y <= by + 5; y++) {
            set(level, bx,               y, bz + 4, Blocks.COPPER_BLOCK.defaultBlockState());
            set(level, bx,               y, bz + 5, Blocks.COPPER_BLOCK.defaultBlockState());
            set(level, bx + ROOM_W - 1, y, bz + 4, Blocks.COPPER_BLOCK.defaultBlockState());
            set(level, bx + ROOM_W - 1, y, bz + 5, Blocks.COPPER_BLOCK.defaultBlockState());
        }

        // Iron bar window: north wall (z=bz) mid-section
        for (int x = bx + 4; x <= bx + 10; x++)
            for (int y = by + 2; y <= by + 3; y++)
                set(level, x, y, bz, Blocks.IRON_BARS.defaultBlockState());

        // Vista TV: framed 3×3 screen on the south wall (z = bz + ROOM_D - 1).
        // Screen stays BLACK_WOOL as a placeholder until the 360° spawn-pan video is wired into a Vista TV.
        int tvZ = bz + ROOM_D - 1;
        for (int dx = -1; dx <= 3; dx++)            // deepslate-tile frame border
            for (int dy = 2; dy <= 6; dy++)
                set(level, bx + 6 + dx, by + dy, tvZ, Blocks.DEEPSLATE_TILES.defaultBlockState());
        for (int dx = 0; dx < 3; dx++)              // 3×3 screen
            for (int dy = 0; dy < 3; dy++)
                set(level, bx + 6 + dx, by + 3 + dy, tvZ, Blocks.BLACK_WOOL.defaultBlockState());
        set(level, bx + 5, by + 4, tvZ, Blocks.SEA_LANTERN.defaultBlockState());   // power lights
        set(level, bx + 9, by + 4, tvZ, Blocks.SEA_LANTERN.defaultBlockState());

        // Chains hanging from ceiling (two symmetrical positions)
        set(level, bx + 5, by + ROOM_H - 2, bz + 5, Blocks.CHAIN.defaultBlockState());
        set(level, bx + 9, by + ROOM_H - 2, bz + 5, Blocks.CHAIN.defaultBlockState());

        // Hanging lanterns below chains
        set(level, bx + 5, by + ROOM_H - 3, bz + 5,
            Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true));
        set(level, bx + 9, by + ROOM_H - 3, bz + 5,
            Blocks.LANTERN.defaultBlockState().setValue(BlockStateProperties.HANGING, true));

        // Floor lanterns (corners)
        set(level, bx + 2, by + 1, bz + 2, Blocks.LANTERN.defaultBlockState());
        set(level, bx + 12, by + 1, bz + 2, Blocks.LANTERN.defaultBlockState());

        // Lectern at center, facing player (south = toward iron bar window)
        set(level, bx + 7, by + 1, bz + 4,
            Blocks.LECTERN.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));

        // Decorative trapped chests (locked aesthetic, near back corners)
        set(level, bx + 2, by + 1, bz + 8, Blocks.TRAPPED_CHEST.defaultBlockState());
        set(level, bx + 12, by + 1, bz + 8, Blocks.TRAPPED_CHEST.defaultBlockState());

        // Music station beneath the Vista TV — jukebox flanked by note blocks.
        // (The Create: Aeronautics disc / looping chill track is the media piece, added later.)
        set(level, bx + 7, by + 1, tvZ - 1, Blocks.JUKEBOX.defaultBlockState());
        set(level, bx + 6, by + 1, tvZ - 1, Blocks.NOTE_BLOCK.defaultBlockState());
        set(level, bx + 8, by + 1, tvZ - 1, Blocks.NOTE_BLOCK.defaultBlockState());

        // Create-themed decor — graceful vanilla fallback if Create isn't present.
        BlockState casing = createBlock("andesite_casing", Blocks.POLISHED_ANDESITE.defaultBlockState());
        BlockState brass  = createBlock("brass_block",     Blocks.WAXED_COPPER_BLOCK.defaultBlockState());
        for (int y = by + 1; y <= by + 4; y++) {          // andesite-casing corner pillars
            set(level, bx + 1,          y, bz + 1,          casing);
            set(level, bx + ROOM_W - 2, y, bz + 1,          casing);
            set(level, bx + 1,          y, bz + ROOM_D - 2, casing);
            set(level, bx + ROOM_W - 2, y, bz + ROOM_D - 2, casing);
        }
        for (int x = bx + 3; x <= bx + ROOM_W - 4; x += 3) // brass ceiling-beam accents
            set(level, x, by + ROOM_H - 2, bz + 5, brass);

        CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Built room for slot {} at X={}", slot, bx);
    }

    /** Places the bundled lobby template at this room's foundation corner. False if it can't be loaded. */
    private boolean placeTemplate(ServerLevel level, int bx, int by, int bz) {
        StructureTemplate tmpl = loadBundledTemplate(level);
        if (tmpl == null) return false;
        // Guard against an empty/corrupt template silently placing nothing → every player falls. Treat a
        // zero-size template as "no template" so the procedural build runs instead.
        net.minecraft.core.Vec3i size = tmpl.getSize();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            CoffeesAeroAuth.LOGGER.warn("[PrivateRoom] Bundled lobby template is empty ({}×{}×{}) — building procedurally.",
                size.getX(), size.getY(), size.getZ());
            return false;
        }
        BlockPos origin = new BlockPos(bx - 2, by - 3, bz - 2);
        // The bundled NBT already carries the correct vista:television connection grid (top_left/top/…/
        // center/…), so the screen renders merged straight from the blockstate models — no post-placement
        // relink needed (and none that could re-break it after a flag-2 placement).
        tmpl.placeInWorld(level, origin, origin,
            new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
            level.getRandom(), 2);
        return true;
    }

    /**
     * Loads the lobby design bundled in the mod jar at {@value #BUNDLED_TEMPLATE_RESOURCE}, parsing it
     * through the server's structure manager (applies data-fixing). Cached after the first success; a
     * failure is remembered so we don't re-attempt every build. Returns null if unavailable, in which
     * case {@link #buildRoom} falls back to the procedural room.
     */
    private StructureTemplate loadBundledTemplate(ServerLevel level) {
        if (bundledTemplate != null)  return bundledTemplate;
        if (bundledTemplateMissing)   return null;
        try (InputStream in = PrivateRoomManager.class.getResourceAsStream(BUNDLED_TEMPLATE_RESOURCE)) {
            if (in == null) {
                CoffeesAeroAuth.LOGGER.error("[PrivateRoom] Bundled lobby template not found on classpath at {} — using procedural room.",
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
            CoffeesAeroAuth.LOGGER.error("[PrivateRoom] Failed to load bundled lobby template — using procedural room.", e);
            bundledTemplateMissing = true;
            return null;
        }
    }

    /**
     * Stamps a guaranteed solid platform with headroom under the room spawn point so a player can
     * NEVER fall into the void, independent of template/procedural build state. Idempotent: only fills
     * blocks that are currently air, so it never overwrites an existing floor or decoration.
     */
    private void ensureSpawnPlatform(ServerLevel level, int bx, int by, int bz) {
        int sx = bx + (int) Math.floor(SPAWN_OX);   // spawn block X (player feet land here)
        int sz = bz + (int) Math.floor(SPAWN_OZ);   // spawn block Z
        for (int x = sx - 3; x <= sx + 3; x++) {
            for (int z = sz - 3; z <= sz + 3; z++) {
                BlockPos floor = new BlockPos(x, by, z);
                if (level.getBlockState(floor).isAir()) {
                    set(level, x, by, z, Blocks.SMOOTH_STONE.defaultBlockState());
                }
            }
        }
        // Clear 3 blocks of headroom directly above the spawn point so the player never suffocates.
        for (int y = by + 1; y <= by + 3; y++) {
            BlockPos head = new BlockPos(sx, y, sz);
            if (!level.getBlockState(head).isAir()) set(level, sx, y, sz, Blocks.AIR.defaultBlockState());
        }
    }

    /**
     * Emergency anti-void net for an already-authenticated player: if they are sitting over void in the
     * lobby (e.g. a returning player whose persisted position is an un-rebuilt room), drop a small solid
     * platform directly beneath them so they don't fall before they type {@code /spawn}. No-op if grounded.
     */
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

    /**
     * Admin /lobby save: captures the current preview room as the template applied to EVERY player's
     * lobby. Clears the built-room cache so existing rooms rebuild from the new template on next visit.
     */
    public boolean saveTemplate() {
        ServerLevel level = server.getLevel(LOBBY_DIMENSION);
        if (level == null) return false;
        int bx = ROOM_BASE_X + PREVIEW_SLOT * ROOM_SPACING;
        BlockPos start = new BlockPos(bx - 2, FLOOR_Y - 3, -2);
        net.minecraft.core.Vec3i size = new net.minecraft.core.Vec3i(ROOM_W + 4, ROOM_H + 4, ROOM_D + 4);
        var mgr = level.getStructureManager();
        var t = mgr.getOrCreate(LOBBY_TEMPLATE);
        t.fillFromWorld(level, start, size, true, null);
        boolean ok = mgr.save(LOBBY_TEMPLATE);
        if (ok) {
            builtRooms.clear();   // force all rooms to rebuild from the new template
            CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Saved lobby template from preview room (X={}).", bx);
        }
        return ok;
    }

    // ── Room deletion ─────────────────────────────────────────────────────────

    public void deleteRoom(ServerLevel level, int slot) {
        if (level == null) { if (builtRooms.contains(slot)) releaseSlot(slot); return; }
        int bx = ROOM_BASE_X + slot * ROOM_SPACING;
        clearVolume(level, bx);
        releaseSlot(slot);
        CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Deleted room at slot {}", slot);
    }

    /**
     * Clears a room's whole volume and rebuilds it from the current bundled design. Used on startup so
     * every deploy refreshes existing lobby rooms — otherwise the blocks saved on disk (e.g. an older
     * TV layout) stay until the player happens to revisit. Clearing first also removes any stale blocks
     * an older design left outside the new template's footprint.
     */
    private void forceRebuildRoom(ServerLevel level, int slot) {
        int bx = ROOM_BASE_X + slot * ROOM_SPACING;
        clearVolume(level, bx);
        builtRooms.remove(slot);
        buildRoom(level, slot);
        builtRooms.add(slot);
    }

    /**
     * Empties a room's whole volume WITHOUT spilling contents into the lobby. Replacing a container
     * block (Numismatics vendor, jukebox with a cassette, chests, lamps that hold items) with a plain
     * {@code setBlock(air)} runs the block's {@code onRemove}, which drops its inventory as item
     * entities — so a rebuild would flood the lobby with loose items. Here we (1) clear each block
     * entity's inventory first, (2) set air with {@code UPDATE_SUPPRESS_DROPS} as a belt-and-braces,
     * and (3) sweep every non-player entity in the volume (loose items, XP orbs, AND Create contraption
     * entities, which are entities — never cleared by a block wipe). The bundled template re-adds its
     * own decor entities (item frames / armor stands) on rebuild, so this stays consistent.
     */
    private void clearVolume(ServerLevel level, int bx) {
        if (level == null) return;
        int by = FLOOR_Y;
        int bz = 0;
        int x0 = bx - 2,          y0 = by - 3,          z0 = bz - 2;
        int x1 = bx + ROOM_W + 2, y1 = by + ROOM_H + 1, z1 = bz + ROOM_D + 2;
        BlockState air = Blocks.AIR.defaultBlockState();
        int flags = net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                  | net.minecraft.world.level.block.Block.UPDATE_SUPPRESS_DROPS;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int x = x0; x < x1; x++)
            for (int y = y0; y < y1; y++)
                for (int z = z0; z < z1; z++) {
                    mp.set(x, y, z);
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(mp);
                    if (be != null) net.minecraft.world.Clearable.tryClear(be); // empty container → nothing to drop
                    level.setBlock(mp, air, flags);
                }
        // Sweep loose entities the old contents/contraptions left behind (players are never present at
        // startup cleanup, but guard anyway so an admin standing in the room is never removed).
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(x0, y0, z0, x1, y1, z1);
        for (net.minecraft.world.entity.Entity e : level.getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class, box,
                e -> !(e instanceof net.minecraft.world.entity.player.Player))) {
            e.discard();
        }
    }

    // ── Startup cleanup ───────────────────────────────────────────────────────

    /** Runs on server start: re-registers in-use slots, purges expired rooms, and rebuilds the rest from
     *  the current bundled design so each deploy refreshes the lobby. */
    public void runStartupCleanup(ProfileStore store) {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        long now = System.currentTimeMillis();
        long APPROVED_TTL = 24L * 3600 * 1000;      // 24 hours after approval
        long PENDING_TTL  = 7L * 24 * 3600 * 1000;  // 7 days for unapproved inactive

        int rebuilt = 0;
        for (PlayerProfile p : store.getAll()) {
            if (p.roomSlot < 0) continue;
            usedSlots.add(p.roomSlot); // re-register as in-use

            long age = p.roomCreatedAt > 0 ? now - p.roomCreatedAt : 0;
            boolean expired = p.roomCreatedAt > 0
                && ((p.nameApproved && age > APPROVED_TTL)
                 || (!p.nameApproved && !p.nameApprovalPending && age > PENDING_TTL));

            if (lobby == null) continue;
            if (expired) {
                deleteRoom(lobby, p.roomSlot);
                p.roomSlot      = -1;
                p.roomCreatedAt = 0;
                store.save(p);
            } else {
                // Active room → refresh it from the current bundled lobby design.
                forceRebuildRoom(lobby, p.roomSlot);
                rebuilt++;
            }
        }
        CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Startup cleanup done. Slots in use: {}, rooms refreshed: {}.",
            usedSlots.size(), rebuilt);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static void set(ServerLevel level, int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), state, 2);
    }

    /** Resolves a Create block by path, falling back to a vanilla block if Create isn't installed. */
    private static BlockState createBlock(String path, BlockState fallback) {
        net.minecraft.world.level.block.Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
            ResourceLocation.fromNamespaceAndPath("create", path));
        return (b == null || b == Blocks.AIR) ? fallback : b.defaultBlockState();
    }
}
