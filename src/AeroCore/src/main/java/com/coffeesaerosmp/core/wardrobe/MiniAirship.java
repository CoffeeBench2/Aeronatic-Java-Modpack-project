package com.coffeesaerosmp.core.wardrobe;

import com.coffeesaerosmp.core.cosmetics.render.CreateParts;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * The little airship in the Wardrobe's contrail preview (owner 2026-10-13: "make it look like a Create airship").
 * Built from the installed mods' own models: Aeronautics envelope blocks with a red tail fin, a spruce deck on an
 * andesite-casing keel, a Create boiler (fluid tanks + steam engine + whistle), meshing Create cogwheels on the hull
 * and an Aeronautics propeller bearing turning a wooden propeller at the stern. The mechanics run: the propeller
 * spins, the cogs turn against each other, and the contrail pours out of the propeller hub.
 *
 * <p>Without Create/Aeronautics every part falls back to the vanilla block it replaces, so the preview always has a
 * ship. ~70 draws per frame, only while the preview shows. {@link #render} returns the propeller hub's screen
 * position and the screen direction of "behind", so the contrail streams out of it from any angle.
 */
public final class MiniAirship {

    /** One block-sized part: a mod item model if that item exists, else a vanilla block. */
    private record Part(float x, float y, float z, String item, BlockState fallback) {}

    private static final List<Part> PARTS = new ArrayList<>();

    static {
        BlockState spruce = Blocks.SPRUCE_PLANKS.defaultBlockState();
        for (int x = -3; x <= 3; x++) for (int z = 0; z <= 1; z++) PARTS.add(new Part(x, 0, z, null, spruce));
        for (int x = -2; x <= 2; x++) for (int z = 0; z <= 1; z++)
            PARTS.add(new Part(x, -1, z, "create:andesite_casing", Blocks.DARK_OAK_PLANKS.defaultBlockState()));
        PARTS.add(new Part(4, 0, 0, null, Blocks.SPRUCE_SLAB.defaultBlockState()));
        PARTS.add(new Part(4, 0, 1, null, Blocks.SPRUCE_SLAB.defaultBlockState()));
        // the boiler: two fluid tanks, a steam engine on the deck, a whistle on top
        PARTS.add(new Part(-2, 1, 0, "create:fluid_tank", Blocks.BARREL.defaultBlockState()));
        PARTS.add(new Part(-2, 1, 1, "create:fluid_tank", Blocks.BARREL.defaultBlockState()));
        PARTS.add(new Part(-1, 1, 1, "create:steam_engine", Blocks.SMOOTH_STONE_SLAB.defaultBlockState()));
        PARTS.add(new Part(-2, 2, 1, "create:steam_whistle", Blocks.AIR.defaultBlockState()));
        PARTS.add(new Part(2, 1, 0, "create:brass_casing", Blocks.SPRUCE_TRAPDOOR.defaultBlockState()));
        // rigging
        for (int y = 1; y <= 2; y++) {
            PARTS.add(new Part(0, y, 0, null, Blocks.CHAIN.defaultBlockState()));
            PARTS.add(new Part(2, y, 1, null, Blocks.CHAIN.defaultBlockState()));
        }
        // envelope (Aeronautics envelope blocks), red tail fin
        BlockState wool = Blocks.WHITE_WOOL.defaultBlockState();
        for (int y = 3; y <= 4; y++) for (int x = -3; x <= 3; x++) for (int z = 0; z <= 1; z++)
            PARTS.add(new Part(x, y, z, "aeronautics:white_envelope", wool));
        for (int x = -2; x <= 2; x++) for (int z = 0; z <= 1; z++) PARTS.add(new Part(x, 5, z, "aeronautics:white_envelope", wool));
        BlockState red = Blocks.RED_WOOL.defaultBlockState();
        PARTS.add(new Part(-4, 3, 0, "aeronautics:red_envelope", red));
        PARTS.add(new Part(-4, 4, 0, "aeronautics:red_envelope", red));
        PARTS.add(new Part(-4, 3, 1, "aeronautics:red_envelope", red));
    }

    /** Where the contrail starts, in screen space, and which way is "behind" (unit vector). */
    public record Tail(float x, float y, float dx, float dy, float pxPerBlock) {}

    /** The propeller hub in ship coordinates (block units): the stern, at deck height, on the centre line. */
    private static final float HUB_X = -4.7f, HUB_Y = 0.5f, HUB_Z = 1.0f;

    /**
     * @param cx,cy  centre of the preview on screen
     * @param scale  pixels per block
     * @param yawDeg the ship's heading
     * @param bob    vertical bob in blocks
     */
    public static Tail render(GuiGraphics g, float cx, float cy, float scale, float yawDeg, float bob) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 160);
        pose.scale(scale, -scale, scale);                 // GUI y points down; blocks want y up
        pose.mulPose(Axis.XP.rotationDegrees(-22));       // look slightly down on it
        pose.mulPose(Axis.YP.rotationDegrees(yawDeg));
        pose.translate(-0.5f, bob - 2f, -1f);             // centre the ship on the pivot

        Matrix4f m = new Matrix4f(pose.last().pose());
        Lighting.setupFor3DItems();
        var mc = Minecraft.getInstance();
        var dispatcher = mc.getBlockRenderer();
        var buffers = g.bufferSource();
        float secs = (System.nanoTime() % 3_600_000_000_000L) / 1e9f;
        for (Part p : PARTS) {
            ItemStack stack = p.item() == null ? null : CreateParts.item(p.item());
            pose.pushPose();
            if (stack != null) {
                pose.translate(p.x() + 0.5f, p.y() + 0.5f, p.z() + 0.5f);   // NONE context: the model is centred
                item(pose, buffers, stack);
            } else if (!p.fallback().isAir()) {
                pose.translate(p.x(), p.y(), p.z());
                dispatcher.renderSingleBlock(p.fallback(), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            }
            pose.popPose();
        }
        mechanics(pose, buffers, secs);
        g.flush();
        Lighting.setupForFlatItems();
        pose.popPose();

        // Project the hub and a point further back to get the screen-space "behind" direction.
        Vector4f tail = m.transform(new Vector4f(HUB_X - 0.2f, HUB_Y, HUB_Z, 1f));
        Vector4f back = m.transform(new Vector4f(HUB_X - 2.5f, HUB_Y, HUB_Z, 1f));
        float dx = back.x - tail.x, dy = back.y - tail.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) { dx = -1; dy = 0; len = 1; }
        return new Tail(tail.x, tail.y, dx / len, dy / len, scale);
    }

    /** The moving parts: the stern propeller on its bearing, and a pair of meshing cogs on each side of the hull. */
    private static void mechanics(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, float secs) {
        ItemStack bearing = CreateParts.item("aeronautics:propeller_bearing");
        ItemStack prop = CreateParts.item("aeronautics:wooden_propeller");
        ItemStack cog = CreateParts.item("create:cogwheel");

        // propeller bearing on the stern, its face pointing aft (the item model faces up: up -> -x)
        pose.pushPose();
        pose.translate(-3.5f, HUB_Y, HUB_Z);
        pose.mulPose(Axis.ZP.rotationDegrees(90));
        if (bearing != null) item(pose, buffers, bearing);
        else Minecraft.getInstance().getBlockRenderer().renderSingleBlock(Blocks.IRON_BLOCK.defaultBlockState(), pose, buffers,
            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        pose.popPose();

        // the propeller, spinning about the ship's long axis. Aeronautics' item model spreads the blades along X with
        // the hub (and mount plate) along Z, so the spin axis is the model's Z: point Z along the hull, plate inboard
        // (+x), and turn about Z. Spinning about Y cartwheeled the whole model instead of turning the blades.
        pose.pushPose();
        pose.translate(HUB_X + 0.2f, HUB_Y, HUB_Z);
        if (prop != null) {
            pose.mulPose(Axis.YP.rotationDegrees(90));
            pose.mulPose(Axis.ZP.rotationDegrees(secs * 720f));
        } else {
            pose.mulPose(Axis.ZP.rotationDegrees(90));
            pose.mulPose(Axis.YP.rotationDegrees(secs * 720f));
        }
        if (prop != null) {
            pose.scale(1.8f, 1.8f, 1.8f);                     // a ship-sized propeller, not a block-sized one
            item(pose, buffers, prop);
        } else {                                              // fallback: an iron-bars blade
            pose.scale(0.15f, 1.6f, 0.15f);
            pose.translate(-0.5f, -0.5f, -0.5f);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(Blocks.IRON_BARS.defaultBlockState(), pose, buffers,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
        pose.popPose();

        // meshing cogs on both sides of the hull, turning against each other (Create cogs mesh at 1 block apart)
        if (cog != null) {
            for (float z : new float[]{-0.18f, 2.18f}) {
                for (int k = 0; k < 2; k++) {
                    pose.pushPose();
                    pose.translate(-0.5f + k, 0.5f, z);
                    pose.mulPose(Axis.XP.rotationDegrees(90));                         // axle across the hull
                    pose.mulPose(Axis.YP.rotationDegrees((k == 0 ? 1 : -1) * secs * 90f + k * 22.5f));
                    item(pose, buffers, cog);
                    pose.popPose();
                }
            }
        }
    }

    private static void item(PoseStack pose, net.minecraft.client.renderer.MultiBufferSource buffers, ItemStack stack) {
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.NONE, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, pose, buffers, Minecraft.getInstance().level, 0);
    }
}
