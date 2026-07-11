package com.coffeesaerosmp.auth.protect;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunkManager;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-grief for Create railways. When a player places a rail block (track, station, signal, bogey,
 * girder…), the chunk is auto-claimed to their FTB Chunks team so other players can't break or
 * interact with it — reusing FTB Chunks' battle-tested per-chunk protection instead of re-implementing
 * block ownership. Entirely a no-op if FTB Chunks isn't loaded.
 *
 * <p>Coverage note: this fires on standard block placement, which catches stations/signals/bogeys/
 * girders + the first/anchor track block (plus a configurable radius). Long track segments dragged
 * out across wilderness via Create's connector bypass block-place events, so chunks far from any
 * placement anchor aren't auto-claimed — players claim those manually (or raise the radius).
 */
public final class RailAutoClaim {

    private static volatile String      cachedRaw = null;
    private static volatile Set<String> cachedIds = Set.of();
    private static final Set<UUID> notified = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, long[]> lastTrackClick = new ConcurrentHashMap<>(); // uuid -> {x,y,z,timeMillis}

    /** Two rail clicks further apart than this are unrelated (a Create drag segment is short) —
     *  without the gate, clicking at spawn then 2000 blocks away claimed the whole RECTANGLE between
     *  them: thousands of chunks in one synchronous loop = the 2026-07-10 12:33 watchdog hang-kill. */
    private static final int MAX_CORRIDOR_BLOCKS = 160;
    private static final int MAX_CORRIDOR_CHUNKS = 32;

    private RailAutoClaim() {}

    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        try {
            if (!AuthConfig.RAIL_AUTOCLAIM_ENABLED.get()) return;
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            if (!ModList.get().isLoaded("ftbchunks")) return;

            BlockState state = event.getPlacedBlock();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (!protectedIds().contains(id.toString())) return;

            claimAround(player, player.level().dimension(), event.getPos());
        } catch (Throwable t) {
            // Never let protection break block placement.
            CoffeesAeroAuth.LOGGER.warn("[RailProtect] auto-claim failed (non-fatal)", t);
        }
    }

    private static void claimAround(ServerPlayer player, ResourceKey<Level> dim, BlockPos pos) {
        ClaimedChunkManager mgr = FTBChunksAPI.api().getManager();
        ChunkTeamData data = mgr.getOrCreateData(player);

        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        int r = AuthConfig.RAIL_AUTOCLAIM_RADIUS.get();
        boolean autoGrant = AuthConfig.RAIL_AUTOCLAIM_AUTOGRANT.get();
        int hardCap = AuthConfig.RAIL_AUTOCLAIM_MAX.get();
        boolean claimedAny = false;
        var source = player.createCommandSourceStack();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ChunkDimPos cdp = new ChunkDimPos(dim, cx + dx, cz + dz);
                if (mgr.getChunk(cdp) != null) continue; // already claimed (any team) — leave it alone

                if (autoGrant) {
                    int claimed = data.getClaimedChunks().size();
                    if (claimed >= data.getMaxClaimChunks()) {
                        if (claimed >= hardCap) continue;                 // land-grab safety cap
                        data.setExtraClaimChunks(data.getExtraClaimChunks() + 1);
                    }
                }

                ClaimResult res = data.claim(source, cdp, false);
                if (res != null && res.isSuccess()) claimedAny = true;
            }
        }

        // One-time heads-up so players understand land is being auto-claimed for them.
        if (claimedAny && notified.add(player.getUUID())) {
            player.sendSystemMessage(Component.literal(
                "§b[Rail] §7Your Create rail here is now §aprotected§7 (chunk auto-claimed). "
                + "View/manage claims with the FTB map (§eM§7)."));
        }
    }

    /**
     * Caches the position of the first right-click with a rail item so that when the player
     * right-clicks a second time (laying a drag segment), we can claim every chunk along the
     * corridor between the two points.  Cache entry expires after 60 seconds.
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        try {
            if (!AuthConfig.RAIL_AUTOCLAIM_ENABLED.get()) return;
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            if (!ModList.get().isLoaded("ftbchunks")) return;

            ItemStack held = player.getItemInHand(
                    event.getHand() != null ? event.getHand() : InteractionHand.MAIN_HAND);
            ResourceLocation heldId = BuiltInRegistries.ITEM.getKey(held.getItem());
            if (!protectedIds().contains(heldId.toString())) return;

            BlockPos clicked = event.getPos();
            long now = System.currentTimeMillis();
            long[] prev = lastTrackClick.put(player.getUUID(),
                    new long[]{clicked.getX(), clicked.getY(), clicked.getZ(), now});

            if (prev == null || now - prev[3] > 60_000L) return; // first click or stale

            BlockPos from = new BlockPos((int) prev[0], (int) prev[1], (int) prev[2]);
            if (from.distSqr(clicked) > (double) MAX_CORRIDOR_BLOCKS * MAX_CORRIDOR_BLOCKS) return;

            // Claim the chunks along the drag corridor (line between the two clicks).
            claimCorridor(player, player.level().dimension(), from, clicked);
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.warn("[RailProtect] corridor-claim failed (non-fatal)", t);
        }
    }

    private static void claimCorridor(ServerPlayer player, ResourceKey<Level> dim,
                                      BlockPos from, BlockPos to) {
        ClaimedChunkManager mgr = FTBChunksAPI.api().getManager();
        ChunkTeamData data = mgr.getOrCreateData(player);

        // Chunks along the LINE between the two clicks (was: the whole bounding rectangle — a
        // diagonal drag claimed a huge square, and the loop ran unbounded on the server thread).
        // Sampling every 8 blocks can't skip a chunk on the path; the set caps the total.
        Set<ChunkDimPos> corridor = new LinkedHashSet<>();
        double len = Math.sqrt(from.distSqr(to));
        int steps = Math.max(1, (int) Math.ceil(len / 8.0));
        for (int i = 0; i <= steps && corridor.size() < MAX_CORRIDOR_CHUNKS; i++) {
            double t = (double) i / steps;
            int x = (int) Math.round(from.getX() + (to.getX() - from.getX()) * t);
            int z = (int) Math.round(from.getZ() + (to.getZ() - from.getZ()) * t);
            corridor.add(new ChunkDimPos(dim, x >> 4, z >> 4));
        }

        boolean autoGrant = AuthConfig.RAIL_AUTOCLAIM_AUTOGRANT.get();
        int hardCap = AuthConfig.RAIL_AUTOCLAIM_MAX.get();
        boolean claimedAny = false;
        var source = player.createCommandSourceStack();

        for (ChunkDimPos cdp : corridor) {
            if (mgr.getChunk(cdp) != null) continue;

            if (autoGrant) {
                int claimed = data.getClaimedChunks().size();
                if (claimed >= data.getMaxClaimChunks()) {
                    if (claimed >= hardCap) continue;
                    data.setExtraClaimChunks(data.getExtraClaimChunks() + 1);
                }
            }

            ClaimResult res = data.claim(source, cdp, false);
            if (res != null && res.isSuccess()) claimedAny = true;
        }

        if (claimedAny && notified.add(player.getUUID())) {
            player.sendSystemMessage(Component.literal(
                "§b[Rail] §7Your Create rail here is now §aprotected§7 (chunk auto-claimed). "
                + "View/manage claims with the FTB map (§eM§7)."));
        }
    }

    private static Set<String> protectedIds() {
        String raw = AuthConfig.RAIL_PROTECTED_BLOCKS.get();
        if (raw == null) raw = "";
        if (!raw.equals(cachedRaw)) {
            Set<String> set = new HashSet<>();
            for (String s : raw.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) set.add(s);
            }
            cachedIds = set;
            cachedRaw = raw;
        }
        return cachedIds;
    }
}
