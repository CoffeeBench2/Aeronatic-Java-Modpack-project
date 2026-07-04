package com.coffeesaerosmp.railguard;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

/** Blocks the railway is built from — used by /railguard trace and the machine-placement
 *  auto-recorder (Create places RU's big curves through its OWN code, outside RU's handlers). */
public final class RailwayPalette {

    private RailwayPalette() {}

    public static boolean isRailwayBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String ns = id.getNamespace(), path = id.getPath();
        if (ns.equals("railwaysuntold")) return true;
        if (ns.equals("create") && (path.contains("track") || path.contains("girder"))) return true;
        if (ns.equals("railways") && path.contains("track")) return true;   // Steam 'n' Rails
        return ns.equals("minecraft") && path.endsWith("rail");
    }
}
