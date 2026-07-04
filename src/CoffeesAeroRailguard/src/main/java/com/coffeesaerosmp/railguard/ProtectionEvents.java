package com.coffeesaerosmp.railguard;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/**
 * Enforcement: recorded railway positions can't be broken by players, blown up, or moved by
 * pistons. Ops (permission level 2+) bypass — and their break UNREGISTERS the position, so admins
 * can permanently remodel a section without fighting the guard.
 */
public final class ProtectionEvents {

    private ProtectionEvents() {}

    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RailguardData data = RailguardData.get(level);
        long packed = event.getPos().asLong();
        if (!data.contains(packed)) return;

        if (event.getPlayer() instanceof ServerPlayer player) {
            if (player.hasPermissions(2)) {
                data.remove(packed);   // admin maintenance: breaking un-protects the position
                return;
            }
            player.displayClientMessage(
                Component.literal("§6⚙ §cThe railway is protected — it belongs to everyone."), true);
        }
        event.setCanceled(true);
    }

    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RailguardData data = RailguardData.get(level);
        event.getAffectedBlocks().removeIf(pos -> data.contains(pos.asLong()));
    }

    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RailguardData data = RailguardData.get(level);
        var resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        for (BlockPos pos : resolver.getToPush()) {
            if (data.contains(pos.asLong())) { event.setCanceled(true); return; }
        }
        for (BlockPos pos : resolver.getToDestroy()) {
            if (data.contains(pos.asLong())) { event.setCanceled(true); return; }
        }
    }
}
