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
 * pistons. EVERYONE is blocked by default — including ops, so admin test-breaks behave like a
 * player's (the 1.0.1 silent op bypass made the guard look broken and quietly stripped protection).
 * Admins toggle {@code /railguard bypass} to edit; bypassed breaks un-protect the position.
 */
public final class ProtectionEvents {

    private ProtectionEvents() {}

    /** Players currently in /railguard bypass mode (in-memory; resets on restart). */
    public static final java.util.Set<java.util.UUID> BYPASS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RailguardData data = RailguardData.get(level);
        long packed = event.getPos().asLong();
        if (!data.contains(packed)) return;

        if (event.getPlayer() instanceof ServerPlayer player) {
            if (BYPASS.contains(player.getUUID())) {
                data.remove(packed);   // bypass edit: breaking un-protects the position
                player.displayClientMessage(
                    Component.literal("§6⚙ §7Railguard: position released (bypass on)."), true);
                return;
            }
            player.displayClientMessage(player.hasPermissions(2)
                ? Component.literal("§6⚙ §cProtected railway. §7Use §e/railguard bypass§7 to edit.")
                : Component.literal("§6⚙ §cThe railway is protected — it belongs to everyone."), true);
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
