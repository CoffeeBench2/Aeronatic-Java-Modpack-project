package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.config.AuthConfig;
import com.coffeesaerosmp.auth.lobby.LobbyHandoff;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.CosmeticCatalog.Cosmetic;
import com.coffeesaerosmp.auth.store.Entitlements.Selection;
import com.coffeesaerosmp.auth.store.PreviewSelections;
import com.coffeesaerosmp.auth.store.Previewers;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.watchdog.LagMonitor;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws walking trails and airship contrails. Design: Obaa {@code planning/cosmetics-proposal-C-tech.md}.
 *
 * <ul>
 *   <li>ONE {@code ServerTickEvent.Post} listener; on odd ticks the whole cost is one compare.</li>
 *   <li>A 1 Hz refresh rebuilds the emitter list off the hot path (only players wearing something), refreshes the
 *       ops preview set, and feeds {@link CosmeticLoad} from {@link LagMonitor#recentTps}: below 15 TPS everything
 *       pauses, and resumes only after 10 s at 17+.</li>
 *   <li>One {@link ClientboundLevelParticlesPacket} per emit, sent only to players within the view radius who have
 *       not hidden other players' cosmetics. A per-tick packet budget is shared round-robin, so overload slows
 *       everyone's emit rate a little instead of starving one player.</li>
 *   <li>Trails emit only while the player moves; skipped for vanished, spectating, invisible, ghosted, AFK or
 *       sleeping players. Contrails: one per ship, highest-ranked wearer aboard wins, only above ~5 m/s.</li>
 *   <li>No display entities — they would add to entity tracking, already 15–19% of the main thread.</li>
 * </ul>
 */
public final class CosmeticEngine {

    private static final CosmeticLoad LOAD = new CosmeticLoad();
    private static final Map<String, ParticleOptions> OPTIONS = new HashMap<>();
    private static final Set<String> BROKEN = new HashSet<>();
    private static final Map<UUID, Vec3> LAST_EMIT_POS = new HashMap<>();
    private static final Set<UUID> HIDE_OTHERS = ConcurrentHashMap.newKeySet();

    private static List<UUID> trailEmitters = List.of();
    private static List<UUID> contrailEmitters = List.of();
    private static long tick;
    private static int cursor;

    /** Packets sent since boot / in the last emit tick — read by GameTests and /aerostore cosmetics status. */
    static volatile long packetsSent;
    static volatile int lastTickPackets;

    private CosmeticEngine() {}

    public static void register(IEventBus bus) {
        bus.addListener((ServerTickEvent.Post e) -> onTick(e.getServer()));
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> forget(e.getEntity().getUUID()));
    }

    public static void forget(UUID uuid) {
        Previewers.forget(uuid);
        PreviewSelections.forget(uuid);
        LAST_EMIT_POS.remove(uuid);
        HIDE_OTHERS.remove(uuid);
    }

    // ── controls (commands) ────────────────────────────────────────────────────

    public static boolean toggleHideOthers(UUID viewer) {
        if (HIDE_OTHERS.remove(viewer)) return false;
        HIDE_OTHERS.add(viewer);
        return true;
    }

    public static void forcePause(boolean pause) { LOAD.force(pause); }

    public static String status() {
        return String.format(java.util.Locale.ROOT,
            "%s · %d trail / %d contrail wearers · %d packets last emit tick · %,d since boot · TPS %.1f",
            LOAD.paused() ? "§cPAUSED" : "§aRUNNING", trailEmitters.size(), contrailEmitters.size(),
            lastTickPackets, packetsSent, LagMonitor.recentTps);
    }

    static boolean paused() { return LOAD.paused(); }

    // ── tick ───────────────────────────────────────────────────────────────────

    private static boolean on(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v) {
        try { return v.get(); } catch (Throwable t) { return false; }
    }

    private static int num(net.neoforged.neoforge.common.ModConfigSpec.IntValue v, int def) {
        try { return v.get(); } catch (Throwable t) { return def; }
    }

    static void onTick(MinecraftServer server) {
        tick++;
        if (tick % 20 == 0) {
            try { refresh(server); } catch (Throwable t) { CoffeesAeroAuth.LOGGER.debug("[Cosmetics] refresh: {}", t.toString()); }
        }
        if (tick % 2 != 0) return;
        if (!on(AuthConfig.COSMETICS_ENABLED) || LOAD.paused() || LobbyHandoff.isLobbyRole()) { lastTickPackets = 0; return; }
        try {
            emit(server);
        } catch (Throwable t) {
            CoffeesAeroAuth.LOGGER.debug("[Cosmetics] emit: {}", t.toString());
        }
    }

    /** 1 Hz: who previews, who wears something, and whether to pause. Package-private for GameTests. */
    static void refresh(MinecraftServer server) {
        Set<UUID> ops = new HashSet<>();
        List<UUID> trails = new ArrayList<>(), contrails = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) ops.add(p.getUUID());
        }
        Previewers.replaceWith(ops);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Selection sel = StoreState.selectionFor(p.getUUID());
            if (sel.trail() != null) trails.add(p.getUUID());
            if (sel.contrail() != null) contrails.add(p.getUUID());
        }
        trailEmitters = trails;
        contrailEmitters = contrails;
        LOAD.update(LagMonitor.recentTps, LagMonitor.recentWorstGapMs, System.currentTimeMillis(),
            num(AuthConfig.COSMETICS_PAUSE_TPS, 15), num(AuthConfig.COSMETICS_RESUME_TPS, 17), 10_000L);
        ShipTracker.prune(tick);
    }

    private static void emit(MinecraftServer server) {
        int budget = Math.min(128, num(AuthConfig.COSMETICS_MAX_PACKETS_PER_TICK, 64));
        int[] sent = {0};
        if (on(AuthConfig.CONTRAILS_ENABLED)) emitContrails(server, budget, sent);
        if (on(AuthConfig.TRAILS_ENABLED)) emitTrails(server, budget, sent);
        lastTickPackets = sent[0];
        packetsSent += sent[0];
    }

    private static void emitTrails(MinecraftServer server, int budget, int[] sent) {
        List<UUID> list = trailEmitters;
        int n = list.size();
        if (n == 0) return;
        double r = num(AuthConfig.TRAIL_VIEW_RADIUS, 24);
        int start = cursor % n;
        for (int k = 0; k < n && sent[0] < budget; k++) {
            UUID id = list.get((start + k) % n);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || skip(p)) continue;
            Cosmetic c = CosmeticCatalog.get(StoreState.selectionFor(id).trail()).orElse(null);
            if (c == null) continue;
            if ((tick + (id.hashCode() & 0x7fff)) % c.fx().everyTicks() != 0) continue;   // stagger
            Vec3 pos = p.position();
            Vec3 last = LAST_EMIT_POS.get(id);
            if (last != null && last.distanceToSqr(pos) < 0.15 * 0.15) continue;           // standing still
            LAST_EMIT_POS.put(id, pos);
            ParticleOptions opt = options(c);
            if (opt == null) continue;
            var fx = c.fx();
            var pkt = new ClientboundLevelParticlesPacket(opt, false, pos.x, pos.y + fx.yOffset(), pos.z,
                fx.spread(), fx.spread() * 0.5f, fx.spread(), fx.speed(), fx.count());
            sent[0] += sendNear(p.serverLevel(), pos, r, pkt, budget - sent[0]);
            cursor = (start + k + 1) % Math.max(1, n);
        }
    }

    private static void emitContrails(MinecraftServer server, int budget, int[] sent) {
        if (contrailEmitters.isEmpty() || !ShipTracker.available()) return;
        int max = num(AuthConfig.MAX_CONTRAILS, 8);
        double r = num(AuthConfig.CONTRAIL_VIEW_RADIUS, 96);
        // One contrail per ship: the highest-ranked wearer aboard wins, ties by smallest uuid (deterministic).
        Map<UUID, ServerPlayer> captainOf = new HashMap<>();
        Map<UUID, ShipTracker.Motion> motionOf = new HashMap<>();
        for (UUID id : contrailEmitters) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || skipForContrail(p)) continue;
            ShipTracker.Motion m = ShipTracker.motion(p, tick);
            if (m == null) continue;
            ServerPlayer cur = captainOf.get(m.ship());
            if (cur == null || outranks(p, cur)) { captainOf.put(m.ship(), p); motionOf.put(m.ship(), m); }
        }
        int ships = 0;
        for (var e : captainOf.entrySet()) {
            if (ships >= max || sent[0] >= budget) break;
            ServerPlayer p = e.getValue();
            Cosmetic c = CosmeticCatalog.get(StoreState.selectionFor(p.getUUID()).contrail()).orElse(null);
            if (c == null || (tick + (e.getKey().hashCode() & 0x7fff)) % c.fx().everyTicks() != 0) continue;
            ShipTracker.Motion m = motionOf.get(e.getKey());
            double[] tail = ContrailMath.tail(m.centre(), m.velocity(), m.min(), m.max());
            if (tail == null) continue;
            ParticleOptions opt = options(c);
            if (opt == null) continue;
            var fx = c.fx();
            // overrideLimiter = true: without it the client drops particles over 32 blocks from its camera,
            // so a contrail seen from the ground would never render.
            var pkt = new ClientboundLevelParticlesPacket(opt, true, tail[0], tail[1], tail[2],
                fx.spread(), fx.spread(), fx.spread(), fx.speed(), fx.count());
            sent[0] += sendNear(p.serverLevel(), new Vec3(tail[0], tail[1], tail[2]), r, pkt, budget - sent[0]);
            ships++;
        }
    }

    private static boolean outranks(ServerPlayer a, ServerPlayer b) {
        int ra = StoreState.get(a.getUUID()).effectiveRank().ordinal();
        int rb = StoreState.get(b.getUUID()).effectiveRank().ordinal();
        if (ra != rb) return ra > rb;
        return a.getUUID().compareTo(b.getUUID()) < 0;
    }

    /** Players who must not emit a personal trail right now. Package-private for GameTests. */
    static boolean skip(ServerPlayer p) {
        if (skipForContrail(p)) return true;
        if (p.isSleeping()) return true;
        if (ShipTracker.available() && contrailEmitters.contains(p.getUUID()) && ShipTracker.motion(p, tick) != null) {
            return true;                               // on a ship with a contrail: no personal trail on top
        }
        return false;
    }

    private static boolean skipForContrail(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || p.hasEffect(MobEffects.INVISIBILITY)) return true;
        if (CoffeesAeroAuth.AUTH_MANAGER == null || !CoffeesAeroAuth.AUTH_MANAGER.isAuthenticated(p.getUUID())) return true;
        if (com.coffeesaerosmp.auth.display.HiddenOps.isHidden(p.getUUID())) return true;
        if (com.coffeesaerosmp.auth.pvp.GhostState.isGhost(p.getUUID())) return true;
        try { if (com.coffeesaerosmp.auth.afk.AfkTracker.isAfk(p)) return true; } catch (Throwable ignored) { }
        return false;
    }

    /** Send one packet to every eligible viewer near {@code at}, up to {@code room} sends. */
    private static int sendNear(ServerLevel level, Vec3 at, double radius, ClientboundLevelParticlesPacket pkt, int room) {
        double r2 = radius * radius;
        int n = 0;
        for (ServerPlayer viewer : level.players()) {
            if (n >= room) break;
            if (HIDE_OTHERS.contains(viewer.getUUID())) continue;
            if (viewer.position().distanceToSqr(at) > r2) continue;
            viewer.connection.send(pkt);
            n++;
        }
        return n;
    }

    /** Catalog Fx → vanilla ParticleOptions, resolved once and cached; an unknown id disables that entry. */
    static ParticleOptions options(Cosmetic c) {
        String id = c.id();
        if (BROKEN.contains(id)) return null;
        ParticleOptions cached = OPTIONS.get(id);
        if (cached != null) return cached;
        var fx = c.fx();
        ParticleOptions out = null;
        try {
            if (fx.rgb() >= 0 && fx.rgb2() >= 0) {
                out = new DustColorTransitionOptions(rgb(fx.rgb()), rgb(fx.rgb2()), fx.scale());
            } else if (fx.rgb() >= 0) {
                out = new DustParticleOptions(rgb(fx.rgb()), fx.scale());
            } else {
                var type = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse(fx.particleId()));
                if (type instanceof SimpleParticleType simple) out = simple;
            }
        } catch (Throwable ignored) { }
        if (out == null) {
            BROKEN.add(id);
            CoffeesAeroAuth.LOGGER.warn("[Cosmetics] {} uses particle '{}' that this server cannot draw — disabled.", id, fx.particleId());
            return null;
        }
        OPTIONS.put(id, out);
        return out;
    }

    private static Vector3f rgb(int rgb) {
        return new Vector3f(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f);
    }
}
