package com.coffeesaerosmp.auth.pvp;

import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.util.TextUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The /back warm-up (owner 2026-10-10): {@code backCountdownSeconds} of standing still before the
 * teleport, then {@link GhostState}. Players were dying again the instant they landed; the countdown
 * stops /back being an instant panic button and the ghost lets them reach the corpse.
 *
 * <p>Driven from {@link ServerTickEvent.Post} — a teleport from PlayerTickEvent reverts (see memory
 * "never teleport from PlayerTickEvent"). Cancelling never spends the cooldown or the death point.
 */
public final class BackCountdown {

    private static final class Pending {
        final Vec3 start;
        final CombatGuard.DeathPoint death;
        final long fireAtMs;
        long lastShown = -1;
        Pending(Vec3 start, CombatGuard.DeathPoint death, long fireAtMs) {
            this.start = start; this.death = death; this.fireAtMs = fireAtMs;
        }
    }

    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private BackCountdown() {}

    public static void register(IEventBus bus) {
        bus.addListener((ServerTickEvent.Post e) -> onServerTick(e.getServer()));
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> pending.remove(e.getEntity().getUUID()));
    }

    public static boolean isRunning(UUID uuid) { return pending.containsKey(uuid); }

    static void start(ServerPlayer player, CombatGuard.DeathPoint death) {
        int secs = AuthConfig.BACK_COUNTDOWN_SECONDS.get();
        if (secs <= 0) { complete(player, death); return; }
        pending.put(player.getUUID(), new Pending(player.position(), death,
            System.currentTimeMillis() + secs * 1000L));
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§b↩ Returning to your death spot in §f" + secs + "s§b. §7Stand still."));
    }

    /** Cancels a running countdown, telling the player why. No-op if none is running. */
    public static void cancel(ServerPlayer player, String why) {
        if (pending.remove(player.getUUID()) == null) return;
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§c↩ Cancelled")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7" + why)));
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§c/back cancelled §7— " + why + ". §7Your /back is still available."));
    }

    private static void onServerTick(MinecraftServer server) {
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> en = it.next();
            Pending pd = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());
            if (p == null) { it.remove(); continue; }
            if (!p.isAlive())                         { cancel(p, "you died"); continue; }
            if (p.position().distanceToSqr(pd.start) > 1.0) { cancel(p, "you moved"); continue; }
            if (CombatGuard.isTagged(p.getUUID()))    { cancel(p, "you're in combat"); continue; }

            if (now >= pd.fireAtMs) {
                it.remove();
                complete(p, pd.death);
                continue;
            }
            long left = (pd.fireAtMs - now + 999) / 1000;
            if (left != pd.lastShown) {
                pd.lastShown = left;
                p.connection.send(new ClientboundSetTitlesAnimationPacket(0, 25, 5));
                p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§b↩ " + left)));
                p.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7Returning to your death spot…")));
                p.serverLevel().sendParticles(ParticleTypes.SOUL, p.getX(), p.getY() + 0.1, p.getZ(),
                    12, 0.6, 0.05, 0.6, 0.02);
            }
        }
    }

    private static void complete(ServerPlayer player, CombatGuard.DeathPoint death) {
        ServerLevel target = player.getServer() == null ? null : player.getServer().getLevel(death.dim());
        if (target == null) {
            player.sendSystemMessage(Component.literal(TextUtil.PREFIX + "§cThat dimension isn't loaded."));
            return;
        }
        player.teleportTo(target, death.x(), death.y(), death.z(), Set.of(), player.getYRot(), player.getXRot());
        player.resetFallDistance();
        CombatGuard.consumeBack(player.getUUID());
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§b👻")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7Back at your death spot")));
        player.sendSystemMessage(Component.literal(TextUtil.PREFIX
            + "§a↩ Back at your death spot. §7(Recharges in "
            + Math.max(1, AuthConfig.BACK_COOLDOWN_SECONDS.get() / 60) + " min.)"));
        GhostState.start(player, AuthConfig.GHOST_SECONDS.get(), new Vec3(death.x(), death.y(), death.z()));
    }
}
