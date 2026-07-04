package com.coffeesaerosmp.railguard;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;

/**
 * Enforcement, two layers:
 * <ul>
 *   <li><b>Positions</b> — every block the generator placed (exact, provenance-tracked).</li>
 *   <li><b>Chunks</b> — every chunk the railway touches is SERVER PROPERTY: no breaking, no
 *       placing, no wrenching, no explosions/pistons for anyone without {@code /railguard bypass}.</li>
 * </ul>
 * Event coverage here handles player actions; the {@code Level.destroyBlock} mixin catches the
 * event-less paths (Create wrench removal, contraption drills, fluid washouts, support pops).
 * {@code /railguard debug} logs every denial with actor + path.
 */
public final class ProtectionEvents {

    private ProtectionEvents() {}

    /** Players currently in /railguard bypass mode (in-memory; resets on restart). */
    public static final java.util.Set<java.util.UUID> BYPASS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** /railguard debug — log every denial at INFO with actor and path. */
    public static volatile boolean DEBUG = false;

    private static final Component MSG_PROTECTED =
        Component.literal("§6⚙ §cThe railway is server property — it belongs to everyone.");
    private static final Component MSG_PROTECTED_OP =
        Component.literal("§6⚙ §cProtected railway. §7Use §e/railguard bypass§7 to edit.");

    /** Called by the CurvedTrackDestroyPacket mixin — Create deletes BEZIER curve spans (the long
     *  angled tracks, which are graph edges, not blocks) through its own packet, invisible to every
     *  block-level hook. Returns true when the deletion must be blocked. */
    public static boolean guardCurveDestroy(ServerLevel level, BlockPos anchorPos, ServerPlayer player) {
        RailguardData data = RailguardData.get(level);
        if (!data.isProtected(anchorPos)) return false;
        if (BYPASS.contains(player.getUUID())) return false;
        deny("curve-destroy", player.getGameProfile().getName(), anchorPos, level, data);
        player.displayClientMessage(player.hasPermissions(2) ? MSG_PROTECTED_OP : MSG_PROTECTED, true);
        return true;
    }

    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RailguardData data = RailguardData.get(level);
        BlockPos pos = event.getPos();
        if (!data.isProtected(pos)) return;

        if (event.getPlayer() instanceof ServerPlayer player) {
            if (BYPASS.contains(player.getUUID())) {
                data.releasePosition(pos);
                player.displayClientMessage(
                    Component.literal("§6⚙ §7Railguard: position released (bypass on)."), true);
                return;
            }
            deny("break", player.getGameProfile().getName(), pos, level, data);
            player.displayClientMessage(player.hasPermissions(2) ? MSG_PROTECTED_OP : MSG_PROTECTED, true);
        }
        event.setCanceled(true);
    }

    /** Railway chunks are server property — players can't place blocks in them either. Outside
     *  them, a player's own track placement rolls back any tentative machine-path auto-record
     *  (the setBlock hook can't see WHO placed; this event can). */
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        RailguardData data = RailguardData.get(level);
        if (BYPASS.contains(player.getUUID()) || !data.isChunkProtected(event.getPos())) {
            data.rollbackAuto(event.getPos());   // player track stays player property
            return;
        }
        deny("place", player.getGameProfile().getName(), event.getPos(), level, data);
        player.displayClientMessage(player.hasPermissions(2) ? MSG_PROTECTED_OP : MSG_PROTECTED, true);
        event.setCanceled(true);
    }

    /** Blocks Create-wrench interactions on protected blocks/chunks — the wrench removes blocks and
     *  deletes track connections WITHOUT any break event, so it must be stopped at the click. */
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (BYPASS.contains(player.getUUID())) return;
        ResourceLocation held = BuiltInRegistries.ITEM.getKey(event.getItemStack().getItem());
        if (!held.getPath().contains("wrench")) return;
        RailguardData data = RailguardData.get(level);
        if (!data.isProtected(event.getPos())) return;
        deny("wrench", player.getGameProfile().getName(), event.getPos(), level, data);
        player.displayClientMessage(player.hasPermissions(2) ? MSG_PROTECTED_OP : MSG_PROTECTED, true);
        event.setCanceled(true);
    }

    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RailguardData data = RailguardData.get(level);
        int before = event.getAffectedBlocks().size();
        event.getAffectedBlocks().removeIf(data::isProtected);
        int removed = before - event.getAffectedBlocks().size();
        if (removed > 0 && DEBUG) {
            CoffeesAeroRailguard.LOGGER.info("[Railguard] DENY explosion — shielded {} block(s) in {}.",
                removed, level.dimension().location());
        }
    }

    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        RailguardData data = RailguardData.get(level);
        var resolver = event.getStructureHelper();
        if (resolver == null || !resolver.resolve()) return;
        for (BlockPos pos : resolver.getToPush()) {
            if (data.isProtected(pos)) { denyPiston(event, pos, level, data); return; }
        }
        for (BlockPos pos : resolver.getToDestroy()) {
            if (data.isProtected(pos)) { denyPiston(event, pos, level, data); return; }
        }
    }

    /** Wither / dragon block destruction. */
    public static void onLivingDestroy(LivingDestroyBlockEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        RailguardData data = RailguardData.get(level);
        if (!data.isProtected(event.getPos())) return;
        deny("mob", event.getEntity().getType().toShortString(), event.getPos(), level, data);
        event.setCanceled(true);
    }

    /** Shared with the destroyBlock mixin. Returns true when the destruction must be blocked. */
    public static boolean guardDestroy(ServerLevel level, BlockPos pos, Entity breaker) {
        if (PlacementTracker.active()) return false;         // the generator's own work
        RailguardData data = RailguardData.get(level);
        if (!data.isProtected(pos)) return false;
        if (breaker instanceof ServerPlayer player && BYPASS.contains(player.getUUID())) {
            data.releasePosition(pos);
            return false;
        }
        deny("destroyBlock", breaker != null ? breaker.getType().toShortString() : "world", pos, level, data);
        return true;
    }

    private static void denyPiston(PistonEvent.Pre event, BlockPos pos, ServerLevel level, RailguardData data) {
        deny("piston", "piston@" + event.getPos().toShortString(), pos, level, data);
        event.setCanceled(true);
    }

    private static void deny(String path, String actor, BlockPos pos, ServerLevel level, RailguardData data) {
        if (!DEBUG) return;
        String reason = data.isPositionProtected(pos) ? "position" : "chunk";
        CoffeesAeroRailguard.LOGGER.info("[Railguard] DENY {} by {} at {} in {} ({}-protected).",
            path, actor, pos.toShortString(), level.dimension().location(), reason);
    }
}
