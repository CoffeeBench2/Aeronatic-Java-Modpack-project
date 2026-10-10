package com.coffeesaerosmp.auth.pvp;

import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The post-/back "ghost" (owner 2026-10-10, modelled on Corail Tombstone's ghostly shape): for
 * {@code ghostSeconds} after {@code /back} the player takes no damage — PvP included — and no mob can
 * pick them as a target. They are NOT invisible. The whole point is to let someone walk back to their
 * corpse without dying on the spot again, so anything else they do — attacking, breaking or placing,
 * using any block or entity — ends it at once. The one exemption is the Corpse entity itself.
 *
 * <p>Server-only: the "ghostly shape" is soul particles + a bossbar. A truly translucent body would
 * need a client renderer. In memory only — a logout, death or dimension change ends it.
 *
 * <p>Measured in server TICKS, not wall-clock time: a lagging server must not quietly shorten the
 * protection (and GameTests run faster than real time).
 */
public final class GhostState {

    /** Corpse (henkelmax) — interacting with it opens the grave and must NOT break the ghost. */
    private static final String CORPSE_ENTITY = "corpse:corpse";
    private static final double DEAGGRO_RADIUS = 64;

    private record Ghost(long untilTick, long totalTicks, ServerBossEvent bar, Vec3 safeSpot) {}

    private static final Map<UUID, Ghost> ghosts = new ConcurrentHashMap<>();
    /** Server ticks since boot, advanced every ServerTickEvent.Post. */
    private static volatile long now;

    private GhostState() {}

    public static void register(IEventBus bus) {
        bus.addListener(GhostState::onIncomingDamage);
        bus.addListener(GhostState::onChangeTarget);
        bus.addListener(GhostState::onAttack);
        bus.addListener(GhostState::onBreak);
        bus.addListener(GhostState::onPlace);
        bus.addListener(GhostState::onRightClickBlock);
        bus.addListener(GhostState::onEntityInteract);
        bus.addListener(GhostState::onEntityInteractSpecific);
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> drop(e.getEntity().getUUID()));
        bus.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) end(p, null);
        });
        bus.addListener((ServerTickEvent.Post e) -> onServerTick(e.getServer()));
    }

    public static boolean isGhost(UUID uuid) {
        Ghost g = ghosts.get(uuid);
        return g != null && g.untilTick() > now;
    }

    public static void start(ServerPlayer player, int seconds, Vec3 safeSpot) {
        if (seconds <= 0) return;
        end(player, null);
        long total = seconds * 20L;
        ServerBossEvent bar = new ServerBossEvent(Component.literal("§b👻 Ghost §7— " + seconds + "s"),
            BossEvent.BossBarColor.WHITE, BossEvent.BossBarOverlay.PROGRESS);
        bar.addPlayer(player);
        ghosts.put(player.getUUID(), new Ghost(now + total, total, bar, safeSpot));
        player.clearFire();
        deaggro(player);
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§b👻 You are a ghost for §f" + seconds + "s§b. §7Nothing can hurt you and mobs ignore you —"
            + " but attacking, building or using anything except your corpse breaks the veil."));
    }

    /** Ends the ghost; {@code why} null = silent. */
    public static void end(ServerPlayer player, String why) {
        Ghost g = ghosts.remove(player.getUUID());
        if (g == null) return;
        g.bar().removeAllPlayers();
        if (why != null)
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7👻 The ghostly veil fades §8(" + why + ")§7."));
    }

    private static void drop(UUID uuid) {
        Ghost g = ghosts.remove(uuid);
        if (g != null) g.bar().removeAllPlayers();
    }

    // ── Protection ──────────────────────────────────────────────────────────────

    private static void onIncomingDamage(LivingIncomingDamageEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || !isGhost(p.getUUID())) return;
        if (e.getSource().is(DamageTypes.GENERIC_KILL)) return;   // /kill still works for staff
        e.setCanceled(true);
    }

    private static void onChangeTarget(LivingChangeTargetEvent e) {
        if (e.getNewAboutToBeSetTarget() instanceof ServerPlayer p && isGhost(p.getUUID())) e.setCanceled(true);
    }

    private static void deaggro(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (Mob m : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(DEAGGRO_RADIUS))) {
            if (m.getTarget() == player) m.setTarget(null);
            if (m.getBrain().hasMemoryValue(MemoryModuleType.ATTACK_TARGET)
                    && m.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null) == player)
                m.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        }
    }

    // ── What breaks the veil ────────────────────────────────────────────────────

    private static void onAttack(AttackEntityEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && isGhost(p.getUUID())) end(p, "you attacked");
    }

    private static void onBreak(BlockEvent.BreakEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p && isGhost(p.getUUID())) end(p, "you broke a block");
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && isGhost(p.getUUID())) end(p, "you placed a block");
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock e) {
        if (e.getEntity() instanceof ServerPlayer p && isGhost(p.getUUID())) end(p, "you used a block");
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        entityUse(e.getEntity(), e.getTarget());
    }

    private static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific e) {
        entityUse(e.getEntity(), e.getTarget());
    }

    private static void entityUse(net.minecraft.world.entity.player.Player player, Entity target) {
        if (!(player instanceof ServerPlayer p) || !isGhost(p.getUUID())) return;
        if (CORPSE_ENTITY.equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString())) return;
        end(p, "you interacted with something");
    }

    // ── Tick: visuals, void, expiry ─────────────────────────────────────────────

    private static void onServerTick(MinecraftServer server) {
        long now = ++GhostState.now;
        if (ghosts.isEmpty()) return;
        for (Iterator<Map.Entry<UUID, Ghost>> it = ghosts.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Ghost> en = it.next();
            Ghost g = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null || !p.isAlive()) { g.bar().removeAllPlayers(); it.remove(); continue; }
            if (now >= g.untilTick()) {
                g.bar().removeAllPlayers();
                it.remove();
                p.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§7👻 You are solid again."));
                continue;
            }
            p.clearFire();
            ServerLevel level = p.serverLevel();
            if (p.getY() < level.getMinBuildHeight()) {
                p.teleportTo(level, g.safeSpot().x, g.safeSpot().y, g.safeSpot().z, p.getYRot(), p.getXRot());
                p.resetFallDistance();
            }
            if (now % 4 == 0) {
                level.sendParticles(ParticleTypes.SOUL, p.getX(), p.getY() + 1.0, p.getZ(), 2, 0.3, 0.6, 0.3, 0.01);
                level.sendParticles(ParticleTypes.SCULK_SOUL, p.getX(), p.getY() + 0.2, p.getZ(), 1, 0.25, 0.1, 0.25, 0.0);
            }
            if (now % 20 == 0) {
                long left = g.untilTick() - now;
                g.bar().setProgress(Math.max(0f, Math.min(1f, left / (float) g.totalTicks())));
                g.bar().setName(Component.literal("§b👻 Ghost §7— " + ((left + 19) / 20) + "s"));
            }
        }
    }
}
