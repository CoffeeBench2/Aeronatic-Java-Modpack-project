package com.coffeesaerosmp.auth.watchdog;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * "Who owns this chunk?" for the lag census — a hotspot is only actionable once staff know whose build it is.
 * FTB classes are touched only inside {@link Ftb}, which is loaded only when FTB Chunks is present, so servers
 * (and the GameTest server) without it never link against the API.
 */
final class ClaimOwner {

    private ClaimOwner() {}

    /** Team name owning the chunk, "wilderness" when unclaimed, null when FTB Chunks is absent or not ready. */
    static String of(MinecraftServer server, String shortDim, int cx, int cz) {
        try {
            if (!ModList.get().isLoaded("ftbchunks")) return null;
            String id = shortDim.contains(":") ? shortDim : "minecraft:" + shortDim;
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(id));
            return Ftb.owner(dim, cx, cz);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class Ftb {
        static String owner(ResourceKey<Level> dim, int cx, int cz) {
            var api = dev.ftb.mods.ftbchunks.api.FTBChunksAPI.api();
            if (!api.isManagerLoaded()) return null;
            var chunk = api.getManager().getChunk(new dev.ftb.mods.ftblibrary.math.ChunkDimPos(dim, cx, cz));
            if (chunk == null) return "wilderness";
            return chunk.getTeamData().getTeam().getShortName();
        }
    }
}
