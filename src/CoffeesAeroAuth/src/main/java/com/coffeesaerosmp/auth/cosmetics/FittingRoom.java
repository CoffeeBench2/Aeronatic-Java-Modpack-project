package com.coffeesaerosmp.auth.cosmetics;

import com.coffeesaerosmp.auth.CoffeesAeroAuth;
import com.coffeesaerosmp.auth.cosmetics.fx.FxPatterns;
import com.coffeesaerosmp.auth.cosmetics.fx.SpriteChoreo;
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
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
 * <p>With the server resource pack ({@link RpModels#enabled()}) it becomes a lit stage: a glowing platform and a
 * backdrop behind a larger mannequin, a modelled airship, and the effects drawn as SPRITES (display entities with
 * pack models) whose keyframes the client interpolates, so they glide instead of flickering like particles.
 * Without the pack it falls back to block displays and particles.
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
        double lookX, lookZ = 1;              // the viewer's horizontal look when it opened (unit)
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
        final boolean rp;                     // the resource pack's models are available
        Display.ItemDisplay stage, backdrop, shipModel;
        final List<Display.ItemDisplay> sprites = new ArrayList<>();
        String spritesFor;                    // the cosmetic the sprites were spawned for

        Room(ServerPlayer v, Vec3 origin, Vec3 anchor, double faceYaw) {
            this.viewer = v;
            this.level = v.serverLevel();
            this.origin = origin;
            this.anchor = anchor;
            this.faceYaw = faceYaw;
            this.rp = RpModels.enabled();
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
        if (!c.id().equals(r.spritesFor)) despawnSprites(r);
        setMode(r, c.type() == CosmeticCatalog.Type.CONTRAIL ? Mode.SHIP : Mode.PERSON);
    }

    /** Preview a name style (colour:/gradient:) and/or bold on the mannequin's nameplate. */
    public static void previewName(ServerPlayer viewer, String style, boolean bold) {
        ensure(viewer);
        Room r = ROOMS.get(viewer.getUUID());
        if (r == null) return;
        r.nameStyle = style;
        r.bold = bold;
        // A name preview is shown on the mannequin: a contrail being previewed has no ship to leave any more.
        var cur = r.cosmetic == null ? null : CosmeticCatalog.get(r.cosmetic).orElse(null);
        if (cur != null && cur.type() == CosmeticCatalog.Type.CONTRAIL) {
            r.cosmetic = null;
            despawnSprites(r);
        }
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
        if (anchor == null) anchor = new Vec3(viewer.getX() + lx * tries[0][0] + leftX * tries[0][1], viewer.getY(),
            viewer.getZ() + lz * tries[0][0] + leftZ * tries[0][1]);
        anchor = ground(viewer.serverLevel(), anchor);
        double dx = viewer.getX() - anchor.x, dz = viewer.getZ() - anchor.z;
        double faceYaw = Math.toDegrees(Math.atan2(-dx, dz));
        Room r = new Room(viewer, viewer.position(), anchor, faceYaw);
        r.lookX = lx;
        r.lookZ = lz;
        r.born = CosmeticEngine.lastTick;
        r.lastActive = r.born;
        ROOMS.put(viewer.getUUID(), r);
        if (r.rp) spawnSet(r);
        spawnMannequin(r);
        spawnPlate(r);
    }

    /**
     * Stand it on the ground: the spot is picked at the viewer's height, and on a slope that left it floating in
     * mid-air (or, with the stage, a platform hanging off a hillside). Drops up to 4 blocks to the first solid top.
     */
    static Vec3 ground(ServerLevel level, Vec3 at) {
        net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.containing(at);
        for (int i = 0; i < 4; i++) {
            var below = p.below();
            var shape = level.getBlockState(below).getCollisionShape(level, below);
            if (!shape.isEmpty()) return new Vec3(at.x, below.getY() + shape.max(net.minecraft.core.Direction.Axis.Y), at.z);
            p = below;
        }
        return at;
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
        despawnSprites(r);
        if (m == Mode.SHIP) {
            despawnMannequin(r);
            despawnPlate(r);
            despawnBackdrop(r);                       // the wake streams behind the ship: keep the view open
            spawnShip(r);
        } else {
            despawnShip(r);
            if (r.rp) spawnBackdrop(r);
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
        if (r.rp) {
            var scale = fake.getAttribute(Attributes.SCALE);
            if (scale != null) {
                scale.setBaseValue(MANNEQUIN_SCALE);
                send(r, new ClientboundUpdateAttributesPacket(fake.getId(), List.of(scale)));
            }
        }
    }

    /** With the pack the mannequin stands a quarter bigger: you're looking at the effect, so make it big. */
    static final float MANNEQUIN_SCALE = 1.25f;

    private static float figureScale(Room r) { return r.rp ? MANNEQUIN_SCALE : 1f; }

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
        r.plate.setPos(r.anchor.x, r.anchor.y + 2.15 * figureScale(r) + (r.rp ? STAGE_TOP : 0), r.anchor.z);
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
        r.shipYaw = shipYawAt(r, CosmeticEngine.lastTick);
        if (r.rp) {
            r.shipModel = spawnItem(r, RpModels.AIRSHIP, r.anchor.x, r.anchor.y + SHIP_RP_Y, r.anchor.z);
            shipModelPose(r, CosmeticEngine.lastTick, 0);
            return;
        }
        for (Part p : SHIP) {
            Display.BlockDisplay d = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, r.level);
            r.ship.add(d);
            loadShipPart(r, d, p, false);
            send(r, new ClientboundAddEntityPacket(d.getId(), d.getUUID(), d.getX(), d.getY(), d.getZ(), 0, 0,
                EntityType.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
            var data = d.getEntityData().getNonDefaultValues();
            if (data != null) send(r, new ClientboundSetEntityDataPacket(d.getId(), data));
            d.getEntityData().packDirty();
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
            tag.putInt("interpolation_duration", KEY);
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
        if (r.mode == Mode.PERSON && r.mannequin != null) {             // every tick: the client lerps between them
            float yaw = (float) (r.faceYaw + Math.sin(tick * 0.04) * 32);       // turns a little, like it's posing
            r.mannequin.setYRot(yaw);
            r.mannequin.setYHeadRot(yaw);
            send(r, new ClientboundTeleportEntityPacket(r.mannequin));
            send(r, new ClientboundRotateHeadPacket(r.mannequin, (byte) Math.floor(yaw * 256 / 360)));
        }
        if (r.mode == Mode.SHIP && tick % KEY == 0) {
            r.shipYaw = shipYawAt(r, tick);
            if (r.shipModel != null) {
                shipModelPose(r, tick, KEY);
            }
            for (int i = 0; i < r.ship.size(); i++) {
                Display.BlockDisplay d = r.ship.get(i);
                loadShipPart(r, d, SHIP.get(i), true);
                sendDirty(r, d);
            }
        }
    }

    /** Keyframe spacing in ticks: positions are sent this often and the client interpolates in between. */
    static final int KEY = 3;

    /**
     * The ship flies three-quarters on: bow towards the viewer and off to their LEFT, so its wake streams away and
     * to the right, across the open sky between the ship and the shop window. Bow-on hid the wake behind the ship;
     * broadside sent it at the camera or off the screen edge.
     */
    static float shipYawAt(Room r, long tick) {
        double vx = r.origin.x - r.anchor.x, vz = r.origin.z - r.anchor.z, len = Math.sqrt(vx * vx + vz * vz);
        if (len < 1e-3) { vx = 0; vz = 1; len = 1; }
        double fx = vx / len + r.lookZ, fz = vz / len - r.lookX;        // toward viewer minus the viewer's right
        double base = Math.toDegrees(Math.atan2(fz, fx));               // yaw with (cos, sin) = forward
        return (float) (base + Math.sin(tick * 0.02) * 20);
    }

    /**
     * Send only what changed since the last packet. NOT getNonDefaultValues(): that drops start_interpolation=0
     * (it equals the default), and without that key the client never starts an interpolation, so every keyframe
     * snapped instead of gliding. Display's setter forces it dirty, so packDirty() always carries it.
     */
    private static void sendDirty(Room r, net.minecraft.world.entity.Entity e) {
        var dirty = e.getEntityData().packDirty();
        if (dirty != null && !dirty.isEmpty()) send(r, new ClientboundSetEntityDataPacket(e.getId(), dirty));
    }

    private static void emitEffect(Room r, long tick) {
        if (r.cosmetic == null) return;
        var c = CosmeticCatalog.get(r.cosmetic).orElse(null);
        if (c == null) return;
        if (r.rp && SpriteChoreo.count(c.id()) > 0) {
            if (!c.id().equals(r.spritesFor)) respawnSprites(r, c.id());
            if (tick % KEY == 0) animateSprites(r, tick);
            return;
        }
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

    // ── resource-pack visuals: stage, airship model, sprites ──────────────────

    /** Height of the stage's lit top above the ground; the mannequin's nameplate rides above it. */
    static final double STAGE_TOP = 0.0;
    static final float STAGE_SIZE = 2.6f, BACKDROP_SIZE = 4.2f;
    /** Airship model: the pack model is one block long; this makes it ~2.6 blocks, centred at eye level. */
    static final float SHIP_RP_SCALE = 2.6f, SHIP_RP_Y = 1.6f;
    /** Model-space point of the propeller, where the contrail leaves the ship (model is centred on the origin). */
    static final Vector3f SHIP_RP_TAIL = new Vector3f(-0.5f, -0.34f, 0f);

    /** ItemDisplay draws its model turned 180° about Y; this cancels it so the transform means what it says. */
    private static final Quaternionf UNFLIP = new Quaternionf().rotateY((float) Math.PI);

    /** A fake item display with a pack model, full-bright (no shading from the time of day or a cave). */
    private static Display.ItemDisplay spawnItem(Room r, int model, double x, double y, double z) {
        Display.ItemDisplay d = new Display.ItemDisplay(EntityType.ITEM_DISPLAY, r.level);
        CompoundTag tag = new CompoundTag();
        tag.put("item", item(model));
        tag.putString("item_display", "none");
        tag.put("brightness", fullBright());
        tag.putFloat("view_range", 2.0f);
        d.load(tag);
        d.setPos(x, y, z);
        send(r, new ClientboundAddEntityPacket(d.getId(), d.getUUID(), x, y, z, 0, 0, EntityType.ITEM_DISPLAY, 0, Vec3.ZERO, 0));
        var data = d.getEntityData().getNonDefaultValues();
        if (data != null) send(r, new ClientboundSetEntityDataPacket(d.getId(), data));
        d.getEntityData().packDirty();
        return d;
    }

    private static CompoundTag fullBright() {
        CompoundTag light = new CompoundTag();
        light.putInt("block", 15);
        light.putInt("sky", 15);
        return light;
    }

    private static CompoundTag item(int model) {
        CompoundTag it = new CompoundTag();
        it.putString("id", "minecraft:paper");
        it.putInt("count", 1);
        CompoundTag comps = new CompoundTag();
        comps.putInt("minecraft:custom_model_data", model);
        it.put("components", comps);
        return it;
    }

    /**
     * Re-pose a display: transformation, interpolated over {@code ticks} (0 = jump), showing {@code model}.
     * {@code billboard} null leaves it as it is. Sends only the changed values.
     *
     * <p>The item and brightness go in EVERY time: Display.load resets a missing "brightness" to none and
     * ItemDisplay resets a missing "item" to EMPTY, so a keyframe without them blanked the model (2026-10-12:
     * the stage, backdrop, ship and every sprite were invisible; only the aurora showed, because its colour
     * changes re-sent the item).
     */
    private static void pose(Room r, Display d, Vector3f translation, Quaternionf left, Vector3f scale, int ticks,
                             String billboard, int model) {
        CompoundTag tag = new CompoundTag();
        CompoundTag tr = new CompoundTag();
        tr.put("translation", floats(translation.x, translation.y, translation.z));
        tr.put("left_rotation", floats(left.x, left.y, left.z, left.w));
        tr.put("scale", floats(scale.x, scale.y, scale.z));
        tr.put("right_rotation", floats(UNFLIP.x, UNFLIP.y, UNFLIP.z, UNFLIP.w));
        tag.put("transformation", tr);
        tag.putInt("interpolation_duration", ticks);
        tag.putInt("start_interpolation", 0);
        if (billboard != null) tag.putString("billboard", billboard);
        tag.put("item", item(model));
        tag.put("brightness", fullBright());
        double x = d.getX(), y = d.getY(), z = d.getZ();
        d.load(tag);                                   // Entity.load zeroes the position; it is not synced, but keep it
        d.setPos(x, y, z);
        sendDirty(r, d);
    }

    /** Unit vector from the anchor towards the viewer (horizontal). */
    private static double[] towardViewer(Room r) {
        double dx = r.origin.x - r.anchor.x, dz = r.origin.z - r.anchor.z, len = Math.sqrt(dx * dx + dz * dz);
        return len < 1e-3 ? new double[]{0, 1} : new double[]{dx / len, dz / len};
    }

    /** The lit platform under the mannequin and the backdrop behind it, facing the viewer. */
    private static void spawnSet(Room r) {
        double[] v = towardViewer(r);
        r.stage = spawnItem(r, RpModels.STAGE, r.anchor.x, r.anchor.y, r.anchor.z);
        // The model is a 16x1x16 slab centred on the origin: lift it so its top sits just above the ground.
        float lift = (0.5f - 1f / 16f) * STAGE_SIZE + 0.015f;
        pose(r, r.stage, new Vector3f(0, lift, 0), new Quaternionf().rotateY((float) Math.atan2(-v[0], -v[1])),
            new Vector3f(STAGE_SIZE), 0, "fixed", RpModels.STAGE);
        spawnBackdrop(r);
    }

    private static void spawnBackdrop(Room r) {
        if (r.backdrop != null) return;
        double[] v = towardViewer(r);
        r.backdrop = spawnItem(r, RpModels.BACKDROP, r.anchor.x, r.anchor.y, r.anchor.z);
        // A flat quad whose face points -Z: turn it to face the viewer, stand it 1.5 blocks behind the mannequin.
        Quaternionf face = new Quaternionf().rotateY((float) Math.atan2(-v[0], -v[1]));
        pose(r, r.backdrop, new Vector3f((float) (-v[0] * 1.5), BACKDROP_SIZE / 2f, (float) (-v[1] * 1.5)), face,
            new Vector3f(BACKDROP_SIZE, BACKDROP_SIZE, 1), 0, "fixed", RpModels.BACKDROP);
    }

    /** Ship pose at a keyframe: yaw swing plus a gentle bob and roll, interpolated over {@code ticks}. */
    private static void shipModelPose(Room r, long tick, int ticks) {
        Quaternionf rot = shipRotation(r, tick);
        float bob = (float) (Math.sin(tick * 0.07) * 0.08);
        pose(r, r.shipModel, new Vector3f(0, bob, 0), rot, new Vector3f(SHIP_RP_SCALE), ticks, "fixed", RpModels.AIRSHIP);
    }

    static Quaternionf shipRotation(Room r, long tick) {
        return new Quaternionf().rotateY((float) Math.toRadians(-shipYawAt(r, tick)))
            .rotateX((float) (Math.sin(tick * 0.05) * 0.06));             // a slight roll, like it's riding air
    }

    /** Rotation that turns a quad (facing +Z) at (x,y,z) towards {@code eye}. */
    static Quaternionf facing(double x, double y, double z, Vec3 eye) {
        double dx = eye.x - x, dy = eye.y - y, dz = eye.z - z, h = Math.sqrt(dx * dx + dz * dz);
        return new Quaternionf().rotateY((float) Math.atan2(dx, dz)).rotateX((float) -Math.atan2(dy, h));
    }

    private static void respawnSprites(Room r, String id) {
        despawnSprites(r);
        int n = SpriteChoreo.count(id);
        Vec3 o = spriteOrigin(r, CosmeticEngine.lastTick);
        var first = SpriteChoreo.frames(id, seconds(r, CosmeticEngine.lastTick), KEY / 20.0, 1, 0, 1.8);
        for (int k = 0; k < n; k++) {
            int model = first.get(k).model();
            r.sprites.add(spawnItem(r, model, o.x, o.y, o.z));
        }
        r.spritesFor = id;
        animateSprites(r, CosmeticEngine.lastTick, true);
    }

    private static double seconds(Room r, long tick) { return (tick - r.born) / 20.0; }

    /** Where sprite offsets are measured from: the mannequin's feet, or the airship's propeller. */
    private static Vec3 spriteOrigin(Room r, long tick) {
        if (r.mode == Mode.SHIP) return new Vec3(r.anchor.x, r.anchor.y + SHIP_RP_Y, r.anchor.z);
        return new Vec3(r.anchor.x, r.anchor.y + STAGE_TOP, r.anchor.z);
    }

    private static void animateSprites(Room r, long tick) { animateSprites(r, tick, false); }

    private static void animateSprites(Room r, long tick, boolean jump) {
        if (r.sprites.isEmpty() || r.spritesFor == null) return;
        double fx, fz, height = 1.8 * figureScale(r);
        float k = 1f;
        Vector3f base = new Vector3f();
        if (r.mode == Mode.SHIP) {
            Quaternionf rot = shipRotation(r, tick);
            Vector3f fwd = rot.transform(new Vector3f(1, 0, 0));
            fx = fwd.x;
            fz = fwd.z;
            double len = Math.sqrt(fx * fx + fz * fz);
            fx /= len;
            fz /= len;
            base = rot.transform(new Vector3f(SHIP_RP_TAIL).mul(SHIP_RP_SCALE));
        } else {
            double yaw = Math.toRadians(r.mannequin == null ? r.faceYaw : r.mannequin.getYRot());
            fx = -Math.sin(yaw);
            fz = Math.cos(yaw);
            k = figureScale(r);
        }
        var frames = SpriteChoreo.frames(r.spritesFor, seconds(r, tick), KEY / 20.0, fx, fz, height / k);
        Vec3 origin = spriteOrigin(r, tick);
        Vec3 eye = r.viewer.getEyePosition();
        for (int i = 0; i < frames.size() && i < r.sprites.size(); i++) {
            var f = frames.get(i);
            Vector3f t = new Vector3f((float) f.x() * k, (float) f.y() * k, (float) f.z() * k).add(base);
            Quaternionf left = f.flat()
                ? new Quaternionf().rotateX((float) (-Math.PI / 2)).rotateZ(-f.spin())
                : facing(origin.x + t.x, origin.y + t.y, origin.z + t.z, eye).rotateZ(f.spin());
            // Billboard FIXED, never CENTER: a camera-facing billboard applies the camera rotation BEFORE the
            // translation, so the offsets came out in camera space and the wakes streamed at the camera. Instead
            // each keyframe turns the sprite to the viewer's eye; they stand still in a menu, so it reads the same.
            pose(r, r.sprites.get(i), t, left, new Vector3f(f.scale() * k), jump || f.wrap() ? 0 : KEY,
                "fixed", f.model());
        }
    }

    // ── despawn ───────────────────────────────────────────────────────────────

    private static void despawnSprites(Room r) {
        if (r.sprites.isEmpty()) return;
        send(r, new ClientboundRemoveEntitiesPacket(r.sprites.stream().mapToInt(Display.ItemDisplay::getId).toArray()));
        r.sprites.clear();
        r.spritesFor = null;
    }

    private static void despawnBackdrop(Room r) {
        if (r.backdrop == null) return;
        send(r, new ClientboundRemoveEntitiesPacket(r.backdrop.getId()));
        r.backdrop = null;
    }

    private static void despawnSet(Room r) {
        if (r.stage != null) send(r, new ClientboundRemoveEntitiesPacket(r.stage.getId()));
        if (r.backdrop != null) send(r, new ClientboundRemoveEntitiesPacket(r.backdrop.getId()));
        r.stage = null;
        r.backdrop = null;
    }

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
        if (r.shipModel != null) {
            send(r, new ClientboundRemoveEntitiesPacket(r.shipModel.getId()));
            r.shipModel = null;
        }
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
            despawnSprites(r);
            despawnSet(r);
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
        if (r.shipModel != null) out.add(r.shipModel.getId());
        if (r.stage != null) out.add(r.stage.getId());
        if (r.backdrop != null) out.add(r.backdrop.getId());
        for (var d : r.sprites) out.add(d.getId());
        return out;
    }

    /** Test hook: every pack-model display in the room still carries its item and full-bright override. */
    static List<String> blankDisplays(UUID viewer) {
        Room r = ROOMS.get(viewer);
        List<String> out = new ArrayList<>();
        if (r == null) return out;
        List<Display.ItemDisplay> all = new ArrayList<>(r.sprites);
        if (r.stage != null) all.add(r.stage);
        if (r.backdrop != null) all.add(r.backdrop);
        if (r.shipModel != null) all.add(r.shipModel);
        for (var d : all) {
            boolean item = false, light = false;
            var vals = d.getEntityData().getNonDefaultValues();
            if (vals != null) for (var v : vals) {
                if (v.value() instanceof net.minecraft.world.item.ItemStack st && !st.isEmpty()) item = true;
                if (v.value() instanceof Integer i && i == ((15 << 20) | (15 << 4))) light = true;   // packed 15/15
            }
            if (!item || !light) out.add(d.getId() + (item ? "" : " no-item") + (light ? "" : " no-light"));
        }
        return out;
    }

    static String mode(UUID viewer) {
        Room r = ROOMS.get(viewer);
        return r == null ? "none" : r.mode.name();
    }
}
