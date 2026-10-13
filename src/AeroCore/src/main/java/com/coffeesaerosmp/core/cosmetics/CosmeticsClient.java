package com.coffeesaerosmp.core.cosmetics;

import com.coffeesaerosmp.core.config.AeroConfig;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of the cosmetics (AeroCore 1.4.0): says hello to the server with this Core's version, keeps the
 * table of who wears what, and every client tick draws each visible player's trail, aura, contrail and wing
 * accents ({@link ParticleFx}). Wings and the model auras are drawn by {@code render.CosmeticLayer}.
 */
public final class CosmeticsClient {

    private CosmeticsClient() {}

    private static final Map<UUID, ParticleFx.Acc> TRAIL = new HashMap<>(), AURA = new HashMap<>(), WAKE = new HashMap<>();
    private static final Map<UUID, double[]> LAST_STERN = new HashMap<>();
    private static final RandomSource RNG = RandomSource.create();
    private static long tick;

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> sayHello(e));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> reset());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            try { onTick(); } catch (Throwable t) { warnOnce(t); }
        });
    }

    public static void handle(CosmeticsPayload p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CosmeticsState.apply(p));
    }

    /** This Core's version: the jar's, or the build's in a dev run (no jar version there). */
    public static String version() {
        String v = "0.0.0";
        try {
            v = net.neoforged.fml.ModList.get().getModContainerById(com.coffeesaerosmp.core.CoffeesAeroCore.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse(v);
        } catch (Throwable ignored) { }
        if (!Character.isDigit(v.isEmpty() ? 'x' : v.charAt(0)) || v.startsWith("0.0")) v = System.getProperty("aerocore.devVersion", v);
        return v;
    }

    private static void sayHello(ClientPlayerNetworkEvent.LoggingIn e) {
        reset();
        try {
            if (e.getPlayer().connection.hasChannel(CoreHelloPayload.TYPE)) {
                PacketDistributor.sendToServer(new CoreHelloPayload(version()));
            }
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("[Cosmetics] hello failed: {}", t.toString());
        }
    }

    private static void reset() {
        CosmeticsState.clear();
        TRAIL.clear();
        AURA.clear();
        WAKE.clear();
        LAST_STERN.clear();
        ShipProbe.clear();
    }

    // ── per tick ─────────────────────────────────────────────────────────────

    private static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lvl = mc.level;
        if (lvl == null || mc.player == null || mc.isPaused()) return;
        tick++;
        AeroConfig.CosmeticsShow show = AeroConfig.COSMETICS_SHOW.get();
        if (show == AeroConfig.CosmeticsShow.NONE) return;
        float density = AeroConfig.COSMETICS_DENSITY.get().floatValue();
        double range = AeroConfig.COSMETICS_RANGE.get();
        boolean firstPersonView = mc.options.getCameraType() == CameraType.FIRST_PERSON;

        // Ships with a contrail fitted by their owner: drawn whenever they fly, whoever (if anyone) is aboard.
        java.util.Set<UUID> shipsDone = new java.util.HashSet<>();
        if (CosmeticsState.anyShipFits() && CosmeticsState.contrailsOn() && show == AeroConfig.CosmeticsShow.EVERYONE) {
            var cam = mc.gameRenderer.getMainCamera().getPosition();
            for (ShipProbe.Stern s : ShipProbe.moving(lvl, cam.x, cam.y, cam.z, range * 4, tick)) {
                String fit = CosmeticsState.shipFit(s.ship());
                if (fit == null) continue;
                shipsDone.add(s.ship());
                drawWake(lvl, s.ship(), fit, s, density);
            }
        }

        for (AbstractClientPlayer p : lvl.players()) {
            boolean local = p == mc.player;
            if (!local && show == AeroConfig.CosmeticsShow.MINE) continue;
            CosmeticsState.Look look = CosmeticsState.get(p.getUUID(), local);
            if (look == null || !visible(p)) continue;
            double dist = p.distanceTo(mc.player);
            boolean near = dist <= range;
            UUID id = p.getUUID();

            // contrail first: a player on a flying ship with one shows it instead of a footstep trail
            boolean onShip = false;
            if (look.contrail() != null && (CosmeticsState.contrailsOn() || local) && dist <= range * 4) {
                onShip = contrail(lvl, p, look.contrail(), density, local, shipsDone);
            } else if (look.trail() != null) {
                // on a ship with a fitted contrail, the ship's wake replaces the footstep trail too
                UUID ship = ShipProbe.shipOf(lvl, p);
                onShip = ship != null && shipsDone.contains(ship);
            }
            if (near && !onShip && look.trail() != null && (CosmeticsState.trailsOn() || local)) {
                double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
                double moved = Math.hypot(dx, dz);
                if (moved > 0.02 && !p.isPassenger()) {
                    double fx = dx / moved, fz = dz / moved;
                    ParticleFx.trail(look.trail(), lvl, RNG, TRAIL.computeIfAbsent(id, k -> new ParticleFx.Acc()), density,
                        p.xo, p.yo, p.zo, p.getX(), p.getY(), p.getZ(), fx, fz, tick);
                }
            }
            if (near && look.aura() != null) {
                ParticleFx.aura(look.aura(), lvl, RNG, AURA.computeIfAbsent(id, k -> new ParticleFx.Acc()), density,
                    p.getX(), p.getY(), p.getZ(), p.getBbHeight(), tick, local && firstPersonView);
            }
            if (near && look.wings() != null && showsWings(p)) wingAccents(lvl, p, look.wings(), density);
        }
        if (tick % 40 == 0) {
            ShipProbe.prune(tick);
            TRAIL.keySet().removeIf(u -> lvl.getPlayerByUUID(u) == null);
            AURA.keySet().removeIf(u -> lvl.getPlayerByUUID(u) == null);
            // (WAKE / LAST_STERN are keyed by ship id as well as player id: pruning them by "is this a player"
            // would restart every ship's wake every two seconds. They're small and cleared on leaving the server.)
            if (WAKE.size() > 512) { WAKE.clear(); LAST_STERN.clear(); }
        }
    }

    /** Draw this tick's contrail; true when the player is on a ship moving fast enough to have one. */
    private static boolean contrail(ClientLevel lvl, AbstractClientPlayer p, String id, float density, boolean local,
                                    java.util.Set<UUID> shipsDone) {
        UUID uid = p.getUUID();
        ShipProbe.Stern s = ShipProbe.stern(lvl, p, tick);
        if (s != null) {
            // one contrail per ship: a fitted one already drew, or another rider claimed this ship this tick
            if (!shipsDone.add(s.ship())) return true;
            drawWake(lvl, s.ship(), id, s, density);
            return true;
        }
        if (local && CosmeticsState.isTryingOn()) {
            // Trying one on from the ground: a demo wake that marches away along where you look.
            var look = p.getLookAngle();
            double d = 3 + (tick % 30) * 0.5;
            var eye = p.getEyePosition();
            double[] now = {eye.x + look.x * d, eye.y + look.y * d, eye.z + look.z * d};
            double[] a = {now[0] - look.x * 0.5, now[1] - look.y * 0.5, now[2] - look.z * 0.5};
            ParticleFx.contrail(id, lvl, RNG, WAKE.computeIfAbsent(uid, k -> new ParticleFx.Acc()), density,
                a[0], a[1], a[2], now[0], now[1], now[2], -look.x, -look.y, -look.z, 2, tick);
        }
        return false;
    }

    /** This tick's piece of a ship's wake, continuous from where its stern was last tick (keyed by ship). */
    private static void drawWake(ClientLevel lvl, UUID ship, String id, ShipProbe.Stern s, float density) {
        double[] now = {s.x(), s.y(), s.z()};
        double[] prev = LAST_STERN.put(ship, now);
        if (prev == null || Math.sqrt(sq(now[0] - prev[0]) + sq(now[1] - prev[1]) + sq(now[2] - prev[2])) > 12) {
            prev = new double[]{now[0] + s.fx() * 0.5, now[1] + s.fy() * 0.5, now[2] + s.fz() * 0.5};
        }
        ParticleFx.contrail(id, lvl, RNG, WAKE.computeIfAbsent(ship, k -> new ParticleFx.Acc()), density,
            prev[0], prev[1], prev[2], now[0], now[1], now[2], s.fx(), s.fy(), s.fz(), s.beam(), tick);
    }

    /** Embers / glints / flakes from the wingtips, where WingPose puts them this tick. */
    private static void wingAccents(ClientLevel lvl, AbstractClientPlayer p, String id, float density) {
        WingPose pose = WingPose.of(p, 0f);
        double yaw = Math.toRadians(p.yBodyRot);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);                 // body forward
        double sx = -fz, sz = fx;                                        // body right
        for (int side = -1; side <= 1; side += 2) {
            double out = pose.span() * Math.cos(Math.toRadians(pose.fold())) * 0.95;
            double back = 0.25 + pose.span() * Math.sin(Math.toRadians(pose.fold())) * 0.6;
            double up = (p.isCrouching() ? 1.05 : 1.35) + pose.span() * 0.35 * Math.sin(Math.toRadians(pose.lift()));
            double x = p.getX() + sx * out * side - fx * back;
            double z = p.getZ() + sz * out * side - fz * back;
            ParticleFx.wingTip(id, lvl, RNG, density, x, p.getY() + up, z, pose.power());
        }
    }

    /** Never draw on invisible or spectating players. */
    static boolean visible(AbstractClientPlayer p) {
        return !p.isInvisible() && !p.isSpectator() && p.isAlive();
    }

    /** Wings hide under an elytra (it is drawn exactly there). */
    public static boolean showsWings(AbstractClientPlayer p) {
        return !p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private static double sq(double v) { return v * v; }

    private static boolean warned;

    private static void warnOnce(Throwable t) {
        if (warned) return;
        warned = true;
        com.mojang.logging.LogUtils.getLogger().warn("[Cosmetics] client effects failed (once)", t);
    }
}
