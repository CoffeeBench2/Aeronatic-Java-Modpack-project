package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.CosmeticCatalog.Cosmetic;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Try it on": a shop preview of a trail or contrail, shown ONLY to the player trying it, for a few seconds.
 *
 * <p>Built to cost nothing: every packet goes to one connection (the previewer's own), so it never touches the
 * shared packet budget, other players' bandwidth or the lag pause. That is also why a contrail can be previewed
 * while contrails are switched off server-wide — nobody else sees it.
 *
 * <ul>
 *   <li><b>Trail</b>: emitted at your feet while you walk, exactly as it will look when worn.</li>
 *   <li><b>Contrail</b> on a flying ship: emitted behind your ship like the real thing. Not on a ship: a short
 *       demo stream that flies away from you along where you're looking, so you can judge colour and density
 *       from the ground.</li>
 * </ul>
 */
public final class TryOn {

    private record Session(Cosmetic cosmetic, long untilMs, String label) {}

    private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<UUID, Vec3> LAST = new ConcurrentHashMap<>();
    /** Test hook: packets sent by previews (never counted in the shared budget). */
    static volatile long packets;

    public static final int SECONDS = 30;

    private TryOn() {}

    /** Start (or replace) a preview. False when the id isn't a trail/contrail. */
    public static boolean start(ServerPlayer p, String cosmeticId) {
        Cosmetic c = CosmeticCatalog.get(cosmeticId).orElse(null);
        if (c == null) return false;
        ACTIVE.put(p.getUUID(), new Session(c, System.currentTimeMillis() + SECONDS * 1000L, c.label()));
        LAST.remove(p.getUUID());
        return true;
    }

    public static void stop(UUID id) {
        ACTIVE.remove(id);
        LAST.remove(id);
    }

    public static boolean isTrying(UUID id) { return ACTIVE.containsKey(id); }

    public static long packetsSent() { return packets; }

    /** Called by the engine every 2 ticks. */
    static void emit(MinecraftServer server, long tick) {
        if (ACTIVE.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var e : ACTIVE.entrySet()) {
            UUID id = e.getKey();
            Session s = e.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive()) { stop(id); continue; }
            long left = (s.untilMs() - now + 999) / 1000;
            if (left <= 0) {
                stop(id);
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("§7Try-on ended. §f/shop§7 to buy it.")));
                continue;
            }
            if (tick % 20 == 0) {
                p.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(
                    "§6☕ Trying on §f" + s.label() + " §8· §7" + left + "s §8· §f/shop§7 to buy")));
            }
            var opt = CosmeticEngine.options(s.cosmetic());
            if (opt == null) { stop(id); continue; }
            var fx = s.cosmetic().fx();
            if (!Stagger.due(tick, id.hashCode(), fx.everyTicks())) continue;
            if (s.cosmetic().type() == CosmeticCatalog.Type.TRAIL) {
                Vec3 pos = p.position();
                Vec3 last = LAST.put(id, pos);
                if (last != null && last.distanceToSqr(pos) < 0.15 * 0.15) continue;     // standing still: nothing, like the real one
                send(p, new ClientboundLevelParticlesPacket(opt, false, pos.x, pos.y + fx.yOffset(), pos.z,
                    fx.spread(), fx.spread() * 0.5f, fx.spread(), fx.speed(), fx.count()));
            } else {
                ShipTracker.Motion m = ShipTracker.available() ? ShipTracker.motion(p, tick) : null;
                double[] tail = m == null ? null : ContrailMath.tail(m.centre(), m.velocity(), m.min(), m.max());
                if (tail != null) {
                    send(p, new ClientboundLevelParticlesPacket(opt, true, tail[0], tail[1], tail[2],
                        fx.spread(), fx.spread(), fx.spread(), fx.speed(), fx.count()));
                } else {
                    // Demo stream: puffs marching away along the look direction, 3 to 15 blocks out.
                    Vec3 look = p.getLookAngle();
                    double d = 3 + (tick / 2 % 7) * 2;
                    Vec3 at = p.getEyePosition().add(look.scale(d));
                    send(p, new ClientboundLevelParticlesPacket(opt, true, at.x, at.y, at.z,
                        fx.spread(), fx.spread(), fx.spread(), fx.speed(), fx.count()));
                }
            }
        }
    }

    private static void send(ServerPlayer p, ClientboundLevelParticlesPacket pkt) {
        p.connection.send(pkt);
        packets++;
    }
}
