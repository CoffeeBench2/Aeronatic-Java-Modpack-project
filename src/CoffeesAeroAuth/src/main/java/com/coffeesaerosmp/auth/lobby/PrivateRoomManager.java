package com.coffeesaerosmp.auth.lobby;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.db.PlayerProfile;
import com.coffeesaerosmp.auth.db.ProfileStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
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

    private final Set<Integer> builtRooms = ConcurrentHashMap.newKeySet();
    private final Set<Integer> usedSlots  = ConcurrentHashMap.newKeySet();
    private final MinecraftServer server;

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

    // TODO: replace this procedural build with an NBT structure template load:
    //   StructureTemplateManager mgr = level.getStructureManager();
    //   StructureTemplate tmpl = mgr.getOrCreate(ResourceLocation.fromNamespaceAndPath("coffees_aero_auth","lobby_room"));
    //   tmpl.placeInWorld(level, new BlockPos(bx, by, bz), new BlockPos(bx, by, bz), new StructurePlaceSettings(), level.random, 2);
    // Place the .nbt file at: data/coffees_aero_auth/structures/lobby_room.nbt (build in-game with a structure block)
    private void buildRoom(ServerLevel level, int slot) {
        int bx = ROOM_BASE_X + slot * ROOM_SPACING;
        int by = FLOOR_Y;
        int bz = 0;

        // Admin-authored template (saved via /lobby save) wins; otherwise build procedurally.
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

    /** Places the saved lobby template at this room's foundation corner. False if no template saved. */
    private boolean placeTemplate(ServerLevel level, int bx, int by, int bz) {
        var opt = level.getStructureManager().get(LOBBY_TEMPLATE);
        if (opt.isEmpty()) return false;
        BlockPos origin = new BlockPos(bx - 2, by - 3, bz - 2);
        opt.get().placeInWorld(level, origin, origin,
            new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(),
            level.getRandom(), 2);
        return true;
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
        if (!builtRooms.contains(slot) && level == null) return;
        int bx = ROOM_BASE_X + slot * ROOM_SPACING;
        int by = FLOOR_Y;
        int bz = 0;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = bx - 2; x < bx + ROOM_W + 2; x++)
            for (int y = by - 3; y < by + ROOM_H + 1; y++)
                for (int z = bz - 2; z < bz + ROOM_D + 2; z++)
                    set(level, x, y, z, air);
        releaseSlot(slot);
        CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Deleted room at slot {}", slot);
    }

    // ── Startup cleanup ───────────────────────────────────────────────────────

    /** Runs on server start: re-registers in-use slots, purges expired rooms. */
    public void runStartupCleanup(ProfileStore store) {
        ServerLevel lobby = server.getLevel(LOBBY_DIMENSION);
        long now = System.currentTimeMillis();
        long APPROVED_TTL = 24L * 3600 * 1000;      // 24 hours after approval
        long PENDING_TTL  = 7L * 24 * 3600 * 1000;  // 7 days for unapproved inactive

        for (PlayerProfile p : store.getAll()) {
            if (p.roomSlot < 0) continue;
            usedSlots.add(p.roomSlot); // re-register as in-use

            if (p.roomCreatedAt <= 0) continue;
            long age = now - p.roomCreatedAt;
            boolean expired = (p.nameApproved && age > APPROVED_TTL)
                           || (!p.nameApproved && !p.nameApprovalPending && age > PENDING_TTL);
            if (expired && lobby != null) {
                deleteRoom(lobby, p.roomSlot);
                p.roomSlot      = -1;
                p.roomCreatedAt = 0;
                store.save(p);
            }
        }
        CoffeesAeroAuth.LOGGER.info("[PrivateRoom] Startup cleanup done. Slots in use: {}", usedSlots.size());
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
