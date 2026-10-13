package com.coffeesaerosmp.core.cosmetics.render;

import com.coffeesaerosmp.core.cosmetics.render.CreateParts.Uv;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * The Create-built wings, assembled from the installed mods' own textures and models ({@link CreateParts}) and
 * moved by {@link WingAnimator}. Each returns false when a part it needs is missing (Create absent, a texture
 * renamed), and the caller falls back to AeroCore's flat wing.
 *
 * <p>Pose on entry: the player's back, between the shoulder blades; y DOWN, back = +z, 1 unit = 1 block. Each wing
 * is built for the left side (+x) and mirrored with {@code scale(side, 1, 1)}.
 */
final class CreateWings {

    private CreateWings() {}

    static final String SHAFT = "create:block/axis", CANVAS = "create:block/sail/canvas_white",
        BRASS = "create:block/brass_casing", FRAME = "create:block/sail/frame",
        TEMPERED = "createpropulsion:block/tempered_wing", WING = "createpropulsion:block/wing",
        BLADE = "createpropulsion:block/andesite_blade", ANGLED = "createwingsplus:block/angled_sail";

    static boolean render(String id, PoseStack pose, MultiBufferSource buf, int light, AbstractClientPlayer p, float partial) {
        WingAnimator.State st = WingAnimator.update(p, partial);
        return switch (id) {
            case "wings:ornithopter" -> ornithopter(pose, buf, light, p, st);
            case "wings:windmill" -> windmill(pose, buf, light, p, st);
            case "wings:aeroplane" -> aeroplane(pose, buf, light, st);
            case "wings:rotors" -> rotors(pose, buf, light, st);
            case "wings:glider" -> glider(pose, buf, light, st);
            default -> false;
        };
    }

    // ── Brass Ornithopter: three-jointed Create shaft bones, canvas sails, cogs that turn with the joints ──────────

    private static boolean ornithopter(PoseStack pose, MultiBufferSource buf, int light, AbstractClientPlayer p, WingAnimator.State st) {
        TextureAtlasSprite shaft = CreateParts.sprite(SHAFT), canvas = CreateParts.sprite(CANVAS), brass = CreateParts.sprite(BRASS);
        if (shaft == null || canvas == null || brass == null) return false;
        var cog = CreateParts.item("create:cogwheel");
        // the wing folds in a Z at rest (elbow forward, wrist back) and opens straight in the air
        float elbowFold = Math.max(0, st.fold - 10) * 1.35f, wristFold = -Math.max(0, st.fold - 10) * 1.1f;
        float[] len = {0.5f, 0.46f, 0.42f};
        float[][] depth = {{0.56f, 0.5f}, {0.5f, 0.4f}, {0.4f, 0.1f}};
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * 0.07, -0.02, 0.02);
            pose.mulPose(Axis.YP.rotationDegrees(-side * st.fold * 0.75f));
            pose.mulPose(Axis.ZP.rotationDegrees(-side * (st.shoulder() + side * st.bank)));
            pose.scale(side, 1, 1);
            float[] joint = {st.shoulder(), st.elbow, st.wrist};
            for (int seg = 0; seg < 3; seg++) {
                // the cog at this joint meshes with the stroke: it turns as the joint swings
                if (cog != null) {
                    pose.pushPose();
                    pose.translate(0, 0, 0.05);
                    pose.mulPose(Axis.ZP.rotationDegrees(joint[seg] * 4f + st.phase * 6f * (seg % 2 == 0 ? 1 : -1)));
                    pose.mulPose(Axis.XP.rotationDegrees(90));
                    CreateParts.model(pose, buf, cog, seg == 0 ? 0.42f : 0.3f, light, p.level(), p.getId() * 3 + seg);
                    pose.popPose();
                }
                var vc = buf.getBuffer(CreateParts.atlasType());           // re-fetch: the cog model switched buffers
                float L = len[seg];
                CreateParts.box(pose, vc, shaft, Uv.SHAFT, 0, -0.035f, -0.035f, L, 0.035f, 0.035f, light, 0xFFFFFF);
                CreateParts.panel(pose, vc, canvas, Uv.ALL, 0.04f, 0.035f, L, 0.035f, L, depth[seg][1], 0.04f, depth[seg][0], 0.012f, light, 0xFFFFFF);
                float mid = L * 0.55f;                                     // a rib down the sail
                CreateParts.box(pose, vc, shaft, Uv.SHAFT, mid - 0.018f, 0.035f, -0.018f, mid + 0.018f,
                    depth[seg][0] + (depth[seg][1] - depth[seg][0]) * 0.55f - 0.02f, 0.018f, light, 0xFFFFFF);
                pose.translate(L, 0, 0);
                if (seg == 0) {
                    pose.mulPose(Axis.YP.rotationDegrees(elbowFold));
                    pose.mulPose(Axis.ZP.rotationDegrees(-st.elbow));
                } else if (seg == 1) {
                    pose.mulPose(Axis.YP.rotationDegrees(wristFold));
                    pose.mulPose(Axis.ZP.rotationDegrees(-st.wrist));
                }
            }
            var vc = buf.getBuffer(CreateParts.atlasType());
            CreateParts.box(pose, vc, brass, Uv.ALL, -0.06f, -0.06f, -0.06f, 0.06f, 0.06f, 0.06f, light, 0xFFFFFF);  // brass tip
            pose.popPose();
        }
        return true;
    }

    // ── Windmill Sails: Create's sail stock, canvas and frame, turning with inertia ──────────────────────────────

    private static boolean windmill(PoseStack pose, MultiBufferSource buf, int light, AbstractClientPlayer p, WingAnimator.State st) {
        TextureAtlasSprite shaft = CreateParts.sprite(SHAFT), canvas = CreateParts.sprite(CANVAS), frame = CreateParts.sprite(FRAME);
        if (shaft == null || canvas == null || frame == null) return false;
        pose.pushPose();
        pose.translate(0, 0.06, 0.1);
        var bearing = CreateParts.item("create:windmill_bearing");
        if (bearing != null) {
            pose.pushPose();
            pose.translate(0, 0, 0.02);
            pose.mulPose(Axis.XP.rotationDegrees(-90));                   // the bearing's face looks backwards
            CreateParts.model(pose, buf, bearing, 0.34f, light, p.level(), p.getId());
            pose.popPose();
        }
        pose.translate(0, 0, 0.13);
        pose.mulPose(Axis.ZP.rotationDegrees(st.spin + st.bank * 0.5f));
        var vc = buf.getBuffer(CreateParts.atlasType());
        CreateParts.box(pose, vc, shaft, Uv.SHAFT, -0.04f, -0.04f, -0.12f, 0.04f, 0.04f, 0.02f, light, 0xFFFFFF);  // the shaft
        for (int k = 0; k < 4; k++) {
            pose.pushPose();
            pose.mulPose(Axis.ZP.rotationDegrees(k * 90));
            // the stock, then a sail set to one side of it with a frame border behind, like Create's sail blocks
            CreateParts.box(pose, vc, shaft, Uv.SHAFT, 0.04f, -0.025f, -0.02f, 1.0f, 0.025f, 0.02f, light, 0xFFFFFF);
            CreateParts.panel(pose, vc, frame, Uv.ALL, 0.22f, 0.025f, 1.0f, 0.025f, 1.0f, 0.36f, 0.22f, 0.36f, 0.03f, light, 0xFFFFFF);
            CreateParts.panel(pose, vc, canvas, Uv.ALL, 0.25f, 0.04f, 0.97f, 0.04f, 0.97f, 0.34f, 0.25f, 0.34f, 0.022f, light, 0xFFFFFF);
            pose.popPose();
        }
        pose.popPose();
        return true;
    }

    // ── Aeroplane Wings: Create Propulsion's tempered wing panels, banking ailerons ──────────────────────────────

    private static boolean aeroplane(PoseStack pose, MultiBufferSource buf, int light, WingAnimator.State st) {
        TextureAtlasSprite tempered = CreateParts.sprite(TEMPERED), wing = CreateParts.sprite(WING), shaft = CreateParts.sprite(SHAFT);
        if (tempered == null || wing == null || shaft == null) return false;
        var vc = buf.getBuffer(CreateParts.atlasType());
        float sweep = 8 + st.fold * 0.5f;                                   // swept back at rest, straight out in flight
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * 0.1, 0.02, 0.06);
            pose.mulPose(Axis.YP.rotationDegrees(-side * sweep));
            float dihedral = 5 + st.lift * 0.35f + side * st.bank * 0.6f + (float) Math.sin(st.phase) * 1.5f * st.power;
            pose.mulPose(Axis.ZP.rotationDegrees(-side * dihedral));
            pose.scale(side, 1, 1);
            // main wing: span along x, chord along z, tapering toward the tip
            CreateParts.box(pose, vc, tempered, Uv.ALL, 0, -0.025f, 0, 0.55f, 0.025f, 0.46f, light, 0xFFFFFF);
            CreateParts.box(pose, vc, tempered, Uv.ALL, 0.55f, -0.02f, 0.03f, 1.05f, 0.02f, 0.38f, light, 0xFFFFFF);
            CreateParts.box(pose, vc, shaft, Uv.SHAFT, 0, -0.03f, 0.04f, 1.08f, 0.03f, 0.1f, light, 0xFFFFFF);    // spar
            // aileron on the outer trailing edge: deflects into the turn and with the climb
            pose.pushPose();
            pose.translate(0, 0, 0.38f);
            float aileron = side * st.bank * 1.6f + (st.lift - 8) * 0.8f;
            pose.mulPose(Axis.XP.rotationDegrees(Math.max(-35, Math.min(35, aileron))));
            CreateParts.box(pose, vc, wing, Uv.ALL, 0.6f, -0.015f, 0, 1.02f, 0.015f, 0.12f, light, 0xFFFFFF);
            pose.popPose();
            // winglet
            CreateParts.box(pose, vc, wing, Uv.ALL, 1.02f, -0.2f, 0.06f, 1.06f, 0.02f, 0.34f, light, 0xFFFFFF);
            pose.popPose();
        }
        return true;
    }

    // ── Twin Rotors: Create Propulsion blades on shoulder masts, counter-rotating ──────────────────────────────

    private static boolean rotors(PoseStack pose, MultiBufferSource buf, int light, WingAnimator.State st) {
        TextureAtlasSprite blade = CreateParts.sprite(BLADE), shaft = CreateParts.sprite(SHAFT), brass = CreateParts.sprite(BRASS);
        if (blade == null || shaft == null || brass == null) return false;
        var vc = buf.getBuffer(CreateParts.atlasType());
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * 0.22, 0, 0.06);
            pose.mulPose(Axis.ZP.rotationDegrees(-side * (18 + st.bank * 0.4f)));          // masts lean out
            CreateParts.box(pose, vc, shaft, Uv.SHAFT, -0.03f, -0.62f, -0.03f, 0.03f, 0f, 0.03f, light, 0xFFFFFF);
            pose.translate(0, -0.64, 0);
            CreateParts.box(pose, vc, brass, Uv.ALL, -0.06f, -0.05f, -0.06f, 0.06f, 0.05f, 0.06f, light, 0xFFFFFF);
            pose.mulPose(Axis.YP.rotationDegrees(side * st.spin * 6f));                    // counter-rotating, fast
            for (int b = 0; b < 3; b++) {
                pose.pushPose();
                pose.mulPose(Axis.YP.rotationDegrees(b * 120));
                pose.mulPose(Axis.XP.rotationDegrees(90));                                 // blades lie flat...
                pose.mulPose(Axis.XP.rotationDegrees(12));                                 // ...with a little pitch
                CreateParts.panel(pose, vc, blade, Uv.ALL, 0.05f, -0.07f, 0.62f, -0.07f, 0.62f, 0.07f, 0.05f, 0.07f, 0, light, 0xFFFFFF);
                pose.popPose();
            }
            pose.popPose();
        }
        return true;
    }

    // ── Sky Glider: Create Wings Plus angled sails on a shaft frame, spreading into a delta in flight ────────────

    private static boolean glider(PoseStack pose, MultiBufferSource buf, int light, WingAnimator.State st) {
        TextureAtlasSprite sail = CreateParts.sprite(ANGLED), shaft = CreateParts.sprite(SHAFT);
        if (sail == null || shaft == null) return false;
        var vc = buf.getBuffer(CreateParts.atlasType());
        float open = 1 - Math.max(0, Math.min(1, (st.fold - 8) / 50f));          // 1 = spread delta, 0 = furled upright
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * 0.05, -0.05, 0.05);
            pose.mulPose(Axis.YP.rotationDegrees(-side * (20 + 45 * (1 - open))));
            pose.mulPose(Axis.ZP.rotationDegrees(-side * (60 * (1 - open) + 4 + st.bank * side * 0.5f
                + (float) Math.sin(st.phase) * 3 * st.power)));
            pose.scale(side, 1, 1);
            // the leading-edge spar and a triangular sail behind it
            CreateParts.box(pose, vc, shaft, Uv.SHAFT, 0, -0.03f, -0.03f, 1.15f, 0.03f, 0.03f, light, 0xFFFFFF);
            CreateParts.panel(pose, vc, sail, Uv.ALL, 0.02f, 0.03f, 1.12f, 0.03f, 0.2f, 0.75f, 0.02f, 0.75f, 0.01f, light, 0xFFFFFF);
            CreateParts.box(pose, vc, shaft, Uv.SHAFT, 0, 0.03f, -0.02f, 0.035f, 0.75f, 0.02f, light, 0xFFFFFF);  // keel
            pose.popPose();
        }
        return true;
    }
}
