package com.coffeesaerosmp.core.cosmetics.render;

import com.coffeesaerosmp.core.config.AeroConfig;
import com.coffeesaerosmp.core.cosmetics.CosmeticsClient;
import com.coffeesaerosmp.core.cosmetics.CosmeticsState;
import com.coffeesaerosmp.core.cosmetics.ParticleFx;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Draws the 3D cosmetics on a player (AeroCore 1.4.0): wings on the back, and the auras that are objects rather
 * than particles (halo, propeller cap, orbiting cogs, raincloud). Runs for every player model, including the
 * Wardrobe's preview doll, which shows {@link #preview} instead of what the server says.
 *
 * <p>Coordinates are the player model's: origin at the neck, y DOWN, 1 unit = 1 block, the back is +z.
 */
public final class CosmeticLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    /** Set by the Wardrobe while it draws its doll: the look to show on the LOCAL player instead. Render thread. */
    public static CosmeticsState.Look preview;

    private static final String NS = com.coffeesaerosmp.core.CoffeesAeroCore.MOD_ID;

    public CosmeticLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    static ResourceLocation tex(String name) {
        return ResourceLocation.fromNamespaceAndPath(NS, "textures/cosmetics/" + name + ".png");
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer p, float limbSwing,
                       float limbSwingAmount, float partial, float ageInTicks, float netHeadYaw, float headPitch) {
        Minecraft mc = Minecraft.getInstance();
        boolean local = p == mc.player;
        CosmeticsState.Look look = local && preview != null ? preview : CosmeticsState.get(p.getUUID(), local);
        if (look == null) return;
        if (preview == null || !local) {
            if (p.isInvisible() || p.isSpectator()) return;
            AeroConfig.CosmeticsShow show = AeroConfig.COSMETICS_SHOW.get();
            if (show == AeroConfig.CosmeticsShow.NONE || (show == AeroConfig.CosmeticsShow.MINE && !local)) return;
        }
        // Draw on a private copy of the pose: if anything below throws, the game's own stack is untouched (an
        // unbalanced push would otherwise break the whole frame: "Pose stack not empty").
        PoseStack own = new PoseStack();
        own.last().pose().set(pose.last().pose());
        own.last().normal().set(pose.last().normal());
        try {
            if (look.wings() != null && CosmeticsClient.showsWings(p)) wings(own, buffers, light, p, look.wings(), partial, ageInTicks);
            if (look.aura() != null) aura(own, buffers, light, p, look.aura(), ageInTicks);
        } catch (Throwable t) {
            if (!warned) { warned = true; com.mojang.logging.LogUtils.getLogger().warn("[Cosmetics] render failed (once)", t); }
        }
    }

    private static boolean warned;

    // ── wings ────────────────────────────────────────────────────────────────

    private void wings(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer p, String id,
                       float partial, float age) {
        pose.pushPose();
        getParentModel().body.translateAndRotate(pose);                 // follow the body (crouch lean)
        pose.translate(0, 0.16, 0.135);                                   // between the shoulder blades, just off the back
        // Create-built wings first: assembled from Create's own textures and models when they're installed.
        if (CreateWings.render(id, pose, buffers, light, p, partial)) {
            pose.popPose();
            return;
        }
        if (id.equals("wings:windmill")) {
            windmill(pose, buffers, light, p, age);
            pose.popPose();
            return;
        }
        WingAnimator.State st = WingAnimator.update(p, partial);
        String name = id.substring(id.indexOf(':') + 1);
        // Create wings without Create (or a missing texture): AeroCore's own mechanical wing stands in.
        if (name.equals("aeroplane") || name.equals("rotors") || name.equals("glider")) name = "ornithopter";
        boolean glow = name.equals("phoenix") || name.equals("prism") || name.equals("seraph") || name.equals("frost");
        int lit = glow ? LightTexture.FULL_BRIGHT : light;
        int rgb = 0xFFFFFF;
        if (name.equals("prism")) rgb = ParticleFx.hsv((age * 0.006f) % 1f, 0.35f, 1f);
        RenderType wingType = RenderType.entityTranslucent(tex("wings_" + name));
        float size = name.equals("monarch") ? 1.2f : 1.5f;
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * 0.06, 0, 0);
            pose.mulPose(Axis.YP.rotationDegrees(-side * st.fold));
            pose.mulPose(Axis.ZP.rotationDegrees(-side * (st.shoulder() + side * st.bank)));
            // Inner half of the wing...
            // (A buffer is fetched right before each draw: asking for another render type in between, like the
            // cog below, ends the previous one's batch, and writing to it after that throws "Not building!".)
            quad(pose, buffers.getBuffer(wingType), side, 0, 0.5f, size, lit, rgb);
            // ...and the outer half, hinged at the middle so the tip trails the beat (a real wing bends).
            pose.translate(side * size * 0.5, 0, 0);
            // the outer half trails the stroke (follow-through) and sweeps back a little more when folded
            pose.mulPose(Axis.ZP.rotationDegrees(-side * st.elbow));
            pose.mulPose(Axis.YP.rotationDegrees(-side * (Math.max(0, st.fold - 20) * 0.35f + 4 * st.power)));
            pose.translate(-side * size * 0.5, 0, 0);
            quad(pose, buffers.getBuffer(wingType), side, 0.5f, 1f, size, lit, rgb);
            if (name.equals("ornithopter")) {                            // a turning cog at the root of each wing
                pose.popPose();
                pose.pushPose();
                pose.translate(side * 0.1, -0.02, 0.03);
                pose.mulPose(Axis.ZP.rotationDegrees(side * age * 9));
                flat(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(tex("cog"))), 0.24f, light, 0xFFFFFF);
            }
            pose.popPose();
        }
        pose.popPose();
    }

    /**
     * One span [u0, u1] of a wing: x from the root outward (mirrored by side), y from above the shoulder (-0.7) to
     * below it (+0.3) of the wing's size, matching the texture's ROOT_Y of 70%.
     */
    private static void quad(PoseStack pose, VertexConsumer vc, int side, float u0, float u1, float size, int light, int rgb) {
        var m = pose.last();
        float x0 = side * u0 * size, x1 = side * u1 * size;
        float yTop = -0.7f * size, yBot = 0.3f * size;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        vertex(vc, m, x0, yTop, 0, u0, 0, r, g, b, light);
        vertex(vc, m, x0, yBot, 0, u0, 1, r, g, b, light);
        vertex(vc, m, x1, yBot, 0, u1, 1, r, g, b, light);
        vertex(vc, m, x1, yTop, 0, u1, 0, r, g, b, light);
    }

    /** Four Create sails turning on the back like a windmill; faster the faster you go. */
    private static void windmill(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer p, float age) {
        double speed = Math.hypot(p.getX() - p.xo, p.getZ() - p.zo);
        float spin = age * (2.5f + (float) Math.min(1, speed * 4) * 9f);
        pose.translate(0, 0.05, 0.08);
        pose.mulPose(Axis.ZP.rotationDegrees(spin));
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(tex("sail")));
        var m = pose.last();
        for (int k = 0; k < 4; k++) {
            double a = Math.toRadians(k * 90);
            float cx = (float) Math.cos(a), cy = (float) Math.sin(a), px = -cy, py = cx;
            float in = 0.12f, out = 0.95f, w = 0.16f;
            // a blade: from the hub outward along (cx, cy), w wide, offset to one side like a real sail on its stock
            float ax = cx * in, ay = cy * in, bx = cx * out, by = cy * out;
            vertex(vc, m, ax, ay, 0, 0, 1, 255, 255, 255, light);
            vertex(vc, m, bx, by, 0, 0, 0, 255, 255, 255, light);
            vertex(vc, m, bx + px * w * 2, by + py * w * 2, 0, 1, 0, 255, 255, 255, light);
            vertex(vc, m, ax + px * w * 2, ay + py * w * 2, 0, 1, 1, 255, 255, 255, light);
        }
        flat(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(tex("cog"))), 0.22f, light, 0xFFFFFF);  // the hub
    }

    // ── auras that are objects ──────────────────────────────────────────────

    private void aura(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer p, String id, float age) {
        switch (id) {
            case "aura:halo" -> {
                pose.pushPose();
                getParentModel().head.translateAndRotate(pose);
                pose.translate(0, -0.66 - 0.03 * Math.sin(age * 0.08), 0);
                pose.mulPose(Axis.XP.rotationDegrees(-14));               // tipped back a touch, so it reads at eye level
                pose.mulPose(Axis.YP.rotationDegrees(age * 1.5f));
                pose.mulPose(Axis.XP.rotationDegrees(90));                // lie flat
                flat(pose, buffers.getBuffer(RenderType.entityTranslucent(tex("halo"))), 0.7f, LightTexture.FULL_BRIGHT, 0xFFFFFF);
                pose.popPose();
            }
            case "aura:propeller" -> {
                pose.pushPose();
                getParentModel().head.translateAndRotate(pose);
                pose.translate(0, -0.56, 0);
                box(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(tex("cog"))), 0.025f, 0.08f, 0.025f, light);  // the stick
                pose.translate(0, -0.06, 0);
                pose.mulPose(Axis.YP.rotationDegrees(age * 38));
                // Aeronautics' real wooden propeller model when it's installed, else AeroCore's flat one
                var prop = CreateParts.item("aeronautics:wooden_propeller");
                if (prop != null) {
                    CreateParts.model(pose, buffers, prop, 0.55f, light, p.level(), p.getId());
                } else {
                    pose.mulPose(Axis.XP.rotationDegrees(90));
                    flat(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(tex("propeller"))), 0.7f, light, 0xFFFFFF);
                }
                pose.popPose();
            }
            case "aura:orbit" -> {
                // Three cogs circling the waist, each turning on its own axle. Create's real cogwheel if it's here.
                ItemStack stack = cogStack();
                for (int k = 0; k < 3; k++) {
                    pose.pushPose();
                    double a = Math.toRadians(age * 2.2 + k * 120);
                    pose.translate(Math.cos(a) * 0.85, 0.42 + 0.06 * Math.sin(age * 0.1 + k * 2), Math.sin(a) * 0.85);
                    pose.mulPose(Axis.YP.rotationDegrees((float) -Math.toDegrees(a)));
                    pose.mulPose(Axis.XP.rotationDegrees(age * 6));
                    if (stack != null) {
                        pose.scale(0.42f, 0.42f, 0.42f);
                        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light,
                            OverlayTexture.NO_OVERLAY, pose, buffers, p.level(), p.getId() + k);
                    } else {
                        flat(pose, buffers.getBuffer(RenderType.entityCutoutNoCull(tex("cog"))), 0.3f, light, 0xFFFFFF);
                    }
                    pose.popPose();
                }
            }
            case "aura:raincloud" -> {
                pose.pushPose();
                pose.translate(0, -1.0 - 0.04 * Math.sin(age * 0.05), 0);
                VertexConsumer vc = buffers.getBuffer(RenderType.entityTranslucent(tex("cloud")));
                box(pose, vc, 0.34f, 0.12f, 0.24f, light);
                pose.translate(0.18, -0.08, 0.04);
                box(pose, vc, 0.18f, 0.12f, 0.18f, light);
                pose.translate(-0.36, 0.02, -0.06);
                box(pose, vc, 0.16f, 0.1f, 0.16f, light);
                pose.popPose();
            }
            default -> { }
        }
    }

    private static ItemStack cogCache;
    private static boolean cogLooked;

    private static ItemStack cogStack() {
        if (!cogLooked) {
            cogLooked = true;
            var rl = ResourceLocation.fromNamespaceAndPath("create", "cogwheel");
            if (BuiltInRegistries.ITEM.containsKey(rl)) {
                var item = BuiltInRegistries.ITEM.get(rl);
                if (item != Items.AIR) cogCache = new ItemStack(item);
            }
        }
        return cogCache;
    }

    // ── primitives ───────────────────────────────────────────────────────────

    /** A square of side {@code s} centred on the origin in the x–y plane. */
    private static void flat(PoseStack pose, VertexConsumer vc, float s, int light, int rgb) {
        var m = pose.last();
        float h = s / 2;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        vertex(vc, m, -h, -h, 0, 0, 0, r, g, b, light);
        vertex(vc, m, -h, h, 0, 0, 1, r, g, b, light);
        vertex(vc, m, h, h, 0, 1, 1, r, g, b, light);
        vertex(vc, m, h, -h, 0, 1, 0, r, g, b, light);
    }

    /** An axis-aligned box of half-sizes (hx, hy, hz) centred on the origin, whole texture on every face. */
    private static void box(PoseStack pose, VertexConsumer vc, float hx, float hy, float hz, int light) {
        var m = pose.last();
        float[][] f = {
            {-hx, -hy, -hz, hx, -hy, -hz, hx, hy, -hz, -hx, hy, -hz},
            {-hx, -hy, hz, -hx, hy, hz, hx, hy, hz, hx, -hy, hz},
            {-hx, -hy, -hz, -hx, hy, -hz, -hx, hy, hz, -hx, -hy, hz},
            {hx, -hy, -hz, hx, -hy, hz, hx, hy, hz, hx, hy, -hz},
            {-hx, -hy, -hz, -hx, -hy, hz, hx, -hy, hz, hx, -hy, -hz},
            {-hx, hy, -hz, hx, hy, -hz, hx, hy, hz, -hx, hy, hz},
        };
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[] q : f) {
            for (int i = 0; i < 4; i++) vertex(vc, m, q[i * 3], q[i * 3 + 1], q[i * 3 + 2], uv[i][0], uv[i][1], 255, 255, 255, light);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose m, float x, float y, float z, float u, float v,
                               int r, int g, int b, int light) {
        vc.addVertex(m, x, y, z).setColor(r, g, b, 255).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light).setNormal(m, 0, -1, 0);
    }
}
