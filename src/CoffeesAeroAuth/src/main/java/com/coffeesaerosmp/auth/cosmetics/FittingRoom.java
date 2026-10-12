package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.cosmetics.fx.FxPatterns;
import com.coffeesaerosmp.auth.store.CosmeticCatalog;
import com.coffeesaerosmp.auth.store.NameRender;
import com.coffeesaerosmp.auth.store.StoreState;
import com.coffeesaerosmp.auth.store.gui.ButtonMenu;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server-side preview: while a player has the shop open, a MANNEQUIN of them (their skin, their name in the
 * previewed colour or gradient) stands beside the window wearing the effect they're looking at. For contrails it
 * becomes a little airship made of display blocks with the wake streaming off its tail. Works on a completely
 * vanilla client: nothing here needs AeroCore.
 *
 * <p>Costs the server nothing: every entity is FAKE. No entity is added to the world; the mannequin, its nameplate
 * and the ship exist only as packets sent to the one viewer, and vanish when the shop closes. A few packets a
 * second to one player while previewing, none otherwise.
 */
public final class FittingRoom {

    private FittingRoom() {}

    enum Mode { PERSON, SHIP }

    private static final class Room {
        final ServerPlayer viewer;
        final ServerLevel level;
        final Vec3 origin;                    // where the viewer stood when it opened
        final Vec3 anchor;                    // where the mannequin / ship stands
        final double faceYaw;                 // mannequin faces the viewer
        ServerPlayer mannequin;               // fake, never in the world
        String teamName;
        Display.TextDisplay plate;
        final List<Display.BlockDisplay> ship = new ArrayList<>();
        Mode mode = Mode.PERSON;
        String cosmetic;                      // trail / contrail being previewed (null = none)
        String nameStyle;                     // colour:/gradient: being previewed (null = what they wear)
        boolean bold;
        long born, step, lastActive;
        boolean infoRemoved;
        float shipYaw;

        Room(ServerPlayer v, Vec3 origin, Vec3 anchor, double faceYaw) {
            this.viewer = v;
            this.level = v.serverLevel();
            this.origin = origin;
            this.anchor = anchor;
            this.faceYaw = faceYaw;
        }
    }

    private static final Map<UUID, Room> ROOMS = new ConcurrentHashMap<>();
    /** Test hook. */
    static volatile long packets;

    // ── public API (server thread) ────────────────────────────────────────────

    /** Make sure the viewer has a mannequin beside their shop window (no effect yet). */
    public static void ensure(ServerPlayer viewer) {
        Room r = ROOMS.get(viewer.getUUID());
        if (r != null && r.level == viewer.serverLevel()) { r.lastActive = now(viewer); return; }
        if (r != null) close(viewer);
        open(viewer);
    }

    /** Preview a trail or contrail on the mannequin. */
    public static void preview(ServerPlayer viewer, String cosmeticId) {
        ensure(viewer);
        Room r = ROOMS.get(viewer.getUUID());
        if (r == null) return;
        var c = CosmeticCatalog.get(cosmeticId).orElse(null);
        if (c == null) return;
        r.cosmetic = c.id();
        r.lastActive = now(viewer);
        setMode(r, c.type() == CosmeticCatalog.Type.CONTRAIL ? Mode.SHIP : Mode.PERSON);
    }

    /** Preview a name style (colour:/gradient:) and/or bold on the mannequin's nameplate. */
    public static void previewName(ServerPlayer viewer, String style, boolean bold) {
        ensure(viewer);
        Room r = ROOMS.get(viewer.getUUID());
        if (r == null) return;
        r.nameStyle = style;
        r.bold = bold;
        r.lastActive = now(viewer);
        setMode(r, Mode.PERSON);
        sendPlate(r);
    }

    public static boolean isOpen(UUID viewer) { return ROOMS.containsKey(viewer); }

    public static void close(ServerPlayer viewer) {
        Room r = ROOMS.remove(viewer.getUUID());
        if (r != null) despawnAll(r);
    }

    public static void forget(UUID id) { ROOMS.remove(id); }

    /** Every tick from the cosmetics engine. */
    static void tick(MinecraftServer server, long tick) {
        if (ROOMS.isEmpty()) return;
        for (Room r : ROOMS.values()) {
            ServerPlayer v = r.viewer;
            boolean gone = v.hasDisconnected() || !v.isAlive() || v.serverLevel() != r.level
                || v.position().distanceToSqr(r.origin) > 9                       // walked away
                || !(v.containerMenu instanceof ButtonMenu) && tick - r.lastActive > 10   // shop closed
                || tick - r.lastActive > 20 * 120;                                 // idle two minutes
            if (gone) { ROOMS.remove(v.getUUID()); if (!v.hasDisconnected()) despawnAll(r); continue; }
            if (v.containerMenu instanceof ButtonMenu) r.lastActive = Math.max(r.lastActive, tick - 5);
            animate(r, tick);
            emitEffect(r, tick);
        }
    }

    // ── spawning ──────────────────────────────────────────────────────────────

    private static long now(ServerPlayer p) { return CosmeticEngine.lastTick; }

    private static void open(ServerPlayer viewer) {
        Vec3 look = viewer.getLookAngle();
        double lx = look.x, lz = look.z, len = Math.sqrt(lx * lx + lz * lz);
        if (len < 1e-3) { lx = 0; lz = 1; len = 1; }
        lx /= len; lz /= len;
        // 5 blocks ahead, 2.6 to the LEFT: beside the chest window rather than behind it, head to feet in view
        // (at 3 blocks the feet, where the trails are, fell off the bottom of the screen). If that spot is inside
        // something (a tree, a wall), try closer, then the right-hand side, then fall back to the first choice.
        double leftX = lz, leftZ = -lx;
        double[][] tries = {{5.0, 2.6}, {4.0, 2.2}, {5.0, -2.6}, {4.0, -2.2}, {3.2, 1.8}, {3.2, -1.8}};
        Vec3 anchor = null;
        for (double[] t : tries) {
            Vec3 c = new Vec3(viewer.getX() + lx * t[0] + leftX * t[1], viewer.getY(), viewer.getZ() + lz * t[0] + leftZ * t[1]);
            if (clear(viewer.serverLevel(), c)) { anchor = c; break; }
        }
        if (anchor == null) anchor = new Vec3(viewer.getX() + lx * 5.0 + leftX * 2.6, viewer.getY(), viewer.getZ() + lz * 5.0 + leftZ * 2.6);
        double dx = viewer.getX() - anchor.x, dz = viewer.getZ() - anchor.z;
        double faceYaw = Math.toDegrees(Math.atan2(-dx, dz));
        Room r = new Room(viewer, viewer.position(), anchor, faceYaw);
        r.born = CosmeticEngine.lastTick;
        r.lastActive = r.born;
        ROOMS.put(viewer.getUUID(), r);
        spawnMannequin(r);
        spawnPlate(r);
    }

    /** Room for a player-sized mannequin: the two blocks it would occupy have no collision. */
    static boolean clear(ServerLevel level, Vec3 at) {
        net.minecraft.core.BlockPos feet = net.minecraft.core.BlockPos.containing(at);
        for (int dy = 0; dy <= 1; dy++) {
            var pos = feet.above(dy);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
        }
        return true;
    }

    private static void setMode(Room r, Mode m) {
        if (r.mode == m) return;
        r.mode = m;
        if (m == Mode.SHIP) {
            despawnMannequin(r);
            despawnPlate(r);
            spawnShip(r);
        } else {
            despawnShip(r);
            spawnMannequin(r);
            spawnPlate(r);
        }
    }

    private static void spawnMannequin(Room r) {
        ServerPlayer v = r.viewer;
        MinecraftServer server = v.getServer();
        GameProfile real = v.getGameProfile();
        String fakeName = "aerofr" + Integer.toHexString(v.getUUID().hashCode() & 0xFFFFFF);
        GameProfile prof = new GameProfile(UUID.randomUUID(), fakeName);
        for (Property tex : real.getProperties().get("textures")) prof.getProperties().put("textures", tex);
        ServerPlayer fake = new ServerPlayer(server, r.level, prof,
            new ClientInformation("en_us", 2, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true));
        // A detached connection: only so the player-info packet can be built. Nothing is ever sent through it.
        new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), fake,
            CommonListenerCookie.createInitial(prof, false));
        fake.moveTo(r.anchor.x, r.anchor.y, r.anchor.z, (float) r.faceYaw, 0);
        fake.setYHeadRot((float) r.faceYaw);
        fake.updateOptions(new ClientInformation("en_us", 2, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true));
        r.mannequin = fake;
        r.infoRemoved = false;

        // Hide the mannequin's own (fake) nametag: our styled nameplate floats above it instead.
        PlayerTeam team = new PlayerTeam(new Scoreboard(), "aerofr" + Integer.toHexString(fake.getId()));
        team.setNameTagVisibility(Team.Visibility.NEVER);
        team.getPlayers().add(fakeName);
        r.teamName = team.getName();
        send(r, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        send(r, new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), List.of(fake)));
        send(r, new ClientboundAddEntityPacket(fake.getId(), prof.getId(), r.anchor.x, r.anchor.y, r.anchor.z, 0,
            (float) r.faceYaw, EntityType.PLAYER, 0, Vec3.ZERO, r.faceYaw));
        var data = fake.getEntityData().getNonDefaultValues();
        if (data != null) send(r, new ClientboundSetEntityDataPacket(fake.getId(), data));
        send(r, new ClientboundRotateHeadPacket(fake, (byte) Math.floor(r.faceYaw * 256 / 360)));
    }

    private static void spawnPlate(Room r) {
        Display.TextDisplay plate = new Display.TextDisplay(EntityType.TEXT_DISPLAY, r.level);
        r.plate = plate;
        loadPlate(r);
        send(r, new ClientboundAddEntityPacket(plate.getId(), plate.getUUID(), plate.getX(), plate.getY(), plate.getZ(), 0, 0,
            EntityType.TEXT_DISPLAY, 0, Vec3.ZERO, 0));
        var data = plate.getEntityData().getNonDefaultValues();
        if (data != null) send(r, new ClientboundSetEntityDataPacket(plate.getId(), data));
    }

    /** The nameplate's text: rank badge + the viewer's name in the previewed style. */
    static Component plateText(ServerPlayer viewer, String style, boolean bold) {
        var snap = StoreState.get(viewer.getUUID());
        var sel = StoreState.selectionFor(viewer.getUUID());
        String s = style != null ? style : sel.nameStyle();
        boolean b = bold || sel.bold();
        MutableComponent out = Component.empty();
        String badge = snap.effectiveRank().badge();
        if (!badge.isEmpty()) out.append(Component.literal(badge));
        out.append(NameRender.preview(viewer.getGameProfile().getName(), s, b));
        return out;
    }

    private static void loadPlate(Room r) {
        CompoundTag t = new CompoundTag();
        t.putString("text", Component.Serializer.toJson(plateText(r.viewer, r.nameStyle, r.bold), r.level.registryAccess()));
        t.putString("billboard", "center");
        t.putString("alignment", "center");          // TextDisplay always decodes it; missing = an error in the log
        t.putInt("background", 0x40000000);
        t.putBoolean("shadow", true);
        r.plate.load(t);
        r.plate.setPos(r.anchor.x, r.anchor.y + 2.15, r.anchor.z);
    }

    private static void sendPlate(Room r) {
        if (r.plate == null) return;
        loadPlate(r);
        var data = r.plate.getEntityData().getNonDefaultValues();
        if (data != null) send(r, new ClientboundSetEntityDataPacket(r.plate.getId(), data));
    }

    // The mini airship: a few stretched display blocks, rotated as one group.
    private record Part(BlockState state, float x, float y, float z, float sx, float sy, float sz) {}

    private static final List<Part> SHIP = List.of(
        new Part(Blocks.SPRUCE_PLANKS.defaultBlockState(), -1.4f, 0.0f, -0.4f, 2.8f, 0.35f, 0.8f),     // deck
        new Part(Blocks.DARK_OAK_PLANKS.defaultBlockState(), -1.0f, -0.3f, -0.35f, 2.0f, 0.3f, 0.7f),  // keel
        new Part(Blocks.SPRUCE_SLAB.defaultBlockState(), 1.4f, 0.0f, -0.4f, 0.4f, 0.35f, 0.8f),        // bow
        new Part(Blocks.WHITE_WOOL.defaultBlockState(), -1.4f, 1.0f, -0.5f, 2.8f, 0.9f, 1.0f),         // balloon
        new Part(Blocks.WHITE_WOOL.defaultBlockState(), -1.0f, 1.9f, -0.4f, 2.0f, 0.3f, 0.8f),         // balloon top
        new Part(Blocks.RED_WOOL.defaultBlockState(), -1.75f, 1.1f, -0.1f, 0.35f, 0.7f, 0.2f),          // tail fin
        new Part(Blocks.CHAIN.defaultBlockState(), -0.9f, 0.35f, -0.05f, 0.1f, 0.65f, 0.1f),            // rigging
        new Part(Blocks.CHAIN.defaultBlockState(), 0.8f, 0.35f, -0.05f, 0.1f, 0.65f, 0.1f),
        new Part(Blocks.IRON_BARS.defaultBlockState(), -1.75f, 0.0f, -0.3f, 0.3f, 0.35f, 0.6f));       // propeller

    /** Height of the ship's pivot above the viewer's feet: about eye level, so you look at it, not up its keel. */
    static final float SHIP_Y = 1.1f;

    /** Ship-local point (blocks, before {@link #SHIP_SCALE}) of the tail, where the contrail starts. */
    static final Vector3f SHIP_TAIL = new Vector3f(-2.0f, 1.4f, 0f);

    private static void spawnShip(Room r) {
        r.shipYaw = (float) r.faceYaw + 90f;
        for (Part p : SHIP) {
            Display.BlockDisplay d = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, r.level);
            r.ship.add(d);
            loadShipPart(r, d, p, false);
            send(r, new ClientboundAddEntityPacket(d.getId(), d.getUUID(), d.getX(), d.getY(), d.getZ(), 0, 0,
                EntityType.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
            var data = d.getEntityData().getNonDefaultValues();
            if (data != null) send(r, new ClientboundSetEntityDataPacket(d.getId(), data));
        }
    }

    /** The model is ~3.6 blocks long; at this scale it reads as a little ship, not a building. */
    static final float SHIP_SCALE = 0.5f;

    private static void loadShipPart(Room r, Display.BlockDisplay d, Part p, boolean interpolate) {
        Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(-r.shipYaw));
        Vector3f t = rot.transform(new Vector3f(p.x(), p.y(), p.z()).mul(SHIP_SCALE));
        CompoundTag tag = new CompoundTag();
        tag.put("block_state", NbtUtils.writeBlockState(p.state()));
        CompoundTag tr = new CompoundTag();
        tr.put("translation", floats(t.x, t.y, t.z));
        tr.put("left_rotation", floats(rot.x, rot.y, rot.z, rot.w));
        tr.put("scale", floats(p.sx() * SHIP_SCALE, p.sy() * SHIP_SCALE, p.sz() * SHIP_SCALE));
        tr.put("right_rotation", floats(0, 0, 0, 1));
        tag.put("transformation", tr);
        if (interpolate) {
            tag.putInt("interpolation_duration", 10);
            tag.putInt("start_interpolation", 0);
        }
        d.load(tag);
        d.setPos(r.anchor.x, r.anchor.y + SHIP_Y, r.anchor.z);
    }

    private static ListTag floats(float... v) {
        ListTag l = new ListTag();
        for (float f : v) l.add(FloatTag.valueOf(f));
        return l;
    }

    // ── animation + effects ───────────────────────────────────────────────────

    private static void animate(Room r, long tick) {
        // Take the mannequin off the tab list once the client has had time to load its skin.
        if (r.mannequin != null && !r.infoRemoved && tick - r.born > 30) {
            send(r, new ClientboundPlayerInfoRemovePacket(List.of(r.mannequin.getUUID())));
            r.infoRemoved = true;
        }
        if (r.mode == Mode.PERSON && r.mannequin != null && tick % 2 == 0) {
            float yaw = (float) (r.faceYaw + Math.sin(tick * 0.05) * 30);       // turns a little, like it's posing
            r.mannequin.setYRot(yaw);
            r.mannequin.setYHeadRot(yaw);
            send(r, new ClientboundTeleportEntityPacket(r.mannequin));
            send(r, new ClientboundRotateHeadPacket(r.mannequin, (byte) Math.floor(yaw * 256 / 360)));
        }
        if (r.mode == Mode.SHIP && tick % 10 == 0) {
            r.shipYaw = (float) (r.faceYaw + 90 + Math.sin(tick * 0.02) * 35);
            for (int i = 0; i < r.ship.size(); i++) {
                Display.BlockDisplay d = r.ship.get(i);
                loadShipPart(r, d, SHIP.get(i), true);
                var data = d.getEntityData().getNonDefaultValues();
                if (data != null) send(r, new ClientboundSetEntityDataPacket(d.getId(), data));
            }
        }
    }

    private static void emitEffect(Room r, long tick) {
        if (r.cosmetic == null) return;
        var c = CosmeticCatalog.get(r.cosmetic).orElse(null);
        if (c == null) return;
        if (!Stagger.due(tick, r.viewer.getUUID().hashCode(), c.fx().everyTicks())) return;
        r.step++;
        FxPatterns.Sink self = (o, lr, x, y, z, dx, dy, dz, sp, n) ->
            send(r, new ClientboundLevelParticlesPacket(o, lr, x, y, z, dx, dy, dz, sp, n));
        FxPatterns.Ctx ctx;
        if (r.mode == Mode.SHIP) {
            Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(-r.shipYaw));
            Vector3f tail = rot.transform(new Vector3f(SHIP_TAIL).mul(SHIP_SCALE));
            Vector3f fwd = rot.transform(new Vector3f(1, 0, 0));
            ctx = new FxPatterns.Ctx(r.anchor.x + tail.x, r.anchor.y + SHIP_Y + tail.y, r.anchor.z + tail.z, fwd.x, fwd.z, 0.6, 0, tick, r.step);
        } else {
            double yaw = Math.toRadians(r.mannequin == null ? r.faceYaw : r.mannequin.getYRot());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            ctx = new FxPatterns.Ctx(r.anchor.x, r.anchor.y, r.anchor.z, fx, fz, 0, 1.8, tick, r.step);
        }
        if (!FxPatterns.emit(c.id(), ctx, self)) {
            var opt = CosmeticEngine.options(c);
            if (opt != null) {
                var fx = c.fx();
                send(r, new ClientboundLevelParticlesPacket(opt, r.mode == Mode.SHIP, ctx.x(), ctx.y() + fx.yOffset(), ctx.z(),
                    fx.spread(), fx.spread(), fx.spread(), fx.speed(), fx.count()));
            }
        }
    }

    // ── despawn ───────────────────────────────────────────────────────────────

    private static void despawnMannequin(Room r) {
        if (r.mannequin == null) return;
        send(r, new ClientboundRemoveEntitiesPacket(r.mannequin.getId()));
        if (!r.infoRemoved) send(r, new ClientboundPlayerInfoRemovePacket(List.of(r.mannequin.getUUID())));
        if (r.teamName != null) {
            PlayerTeam t = new PlayerTeam(new Scoreboard(), r.teamName);
            send(r, ClientboundSetPlayerTeamPacket.createRemovePacket(t));
        }
        r.mannequin = null;
        r.teamName = null;
    }

    private static void despawnPlate(Room r) {
        if (r.plate == null) return;
        send(r, new ClientboundRemoveEntitiesPacket(r.plate.getId()));
        r.plate = null;
    }

    private static void despawnShip(Room r) {
        if (r.ship.isEmpty()) return;
        int[] ids = r.ship.stream().mapToInt(Display.BlockDisplay::getId).toArray();
        send(r, new ClientboundRemoveEntitiesPacket(ids));
        r.ship.clear();
    }

    private static void despawnAll(Room r) {
        try {
            despawnMannequin(r);
            despawnPlate(r);
            despawnShip(r);
        } catch (Exception e) {
            CoffeesAeroAuth.LOGGER.debug("[FittingRoom] despawn: {}", e.toString());
        }
    }

    private static void send(Room r, net.minecraft.network.protocol.Packet<?> pkt) {
        if (r.viewer.connection == null || r.viewer.hasDisconnected()) return;
        r.viewer.connection.send(pkt);
        packets++;
    }

    public static long packetsSent() { return packets; }

    /** Test hook: the fake entity ids of a viewer's room, or empty. */
    static List<Integer> entityIds(UUID viewer) {
        Room r = ROOMS.get(viewer);
        List<Integer> out = new ArrayList<>();
        if (r == null) return out;
        if (r.mannequin != null) out.add(r.mannequin.getId());
        if (r.plate != null) out.add(r.plate.getId());
        for (var d : r.ship) out.add(d.getId());
        return out;
    }

    static String mode(UUID viewer) {
        Room r = ROOMS.get(viewer);
        return r == null ? "none" : r.mode.name();
    }
}
