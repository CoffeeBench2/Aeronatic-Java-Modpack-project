package com.coffeesaerosmp.core.wardrobe;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * A little airship for the contrail preview, drawn from vanilla blocks in the GUI: spruce hull, white wool
 * balloon, chain rigging, red tail fin. It turns slowly and bobs; {@link #render} returns the screen position
 * and backwards direction of its tail so the contrail particles stream out behind it from any angle.
 *
 * <p>~70 single-block draws per frame, only while the preview is showing. No world, no entities.
 */
public final class MiniAirship {

    private record Part(int x, int y, int z, BlockState state) {}

    private static final List<Part> PARTS = new ArrayList<>();

    static {
        for (int x = -3; x <= 3; x++) for (int z = 0; z <= 1; z++) PARTS.add(new Part(x, 0, z, Blocks.SPRUCE_PLANKS.defaultBlockState()));
        for (int x = -2; x <= 2; x++) for (int z = 0; z <= 1; z++) PARTS.add(new Part(x, -1, z, Blocks.DARK_OAK_PLANKS.defaultBlockState()));
        PARTS.add(new Part(4, 0, 0, Blocks.SPRUCE_SLAB.defaultBlockState()));
        PARTS.add(new Part(4, 0, 1, Blocks.SPRUCE_SLAB.defaultBlockState()));
        for (int y = 1; y <= 2; y++) {
            PARTS.add(new Part(-2, y, 0, Blocks.CHAIN.defaultBlockState()));
            PARTS.add(new Part(2, y, 1, Blocks.CHAIN.defaultBlockState()));
        }
        for (int y = 3; y <= 4; y++) for (int x = -3; x <= 3; x++) for (int z = 0; z <= 1; z++)
            PARTS.add(new Part(x, y, z, Blocks.WHITE_WOOL.defaultBlockState()));
        for (int x = -2; x <= 2; x++) for (int z = 0; z <= 1; z++) PARTS.add(new Part(x, 5, z, Blocks.WHITE_WOOL.defaultBlockState()));
        PARTS.add(new Part(-4, 3, 0, Blocks.RED_WOOL.defaultBlockState()));
        PARTS.add(new Part(-4, 4, 0, Blocks.RED_WOOL.defaultBlockState()));
        PARTS.add(new Part(-4, 3, 1, Blocks.RED_WOOL.defaultBlockState()));
        PARTS.add(new Part(-4, 0, 0, Blocks.IRON_BARS.defaultBlockState()));
        PARTS.add(new Part(-4, 0, 1, Blocks.IRON_BARS.defaultBlockState()));
    }

    /** Where the contrail starts, in screen space, and which way is "behind" (unit vector). */
    public record Tail(float x, float y, float dx, float dy, float pxPerBlock) {}

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
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var buffers = g.bufferSource();
        for (Part p : PARTS) {
            pose.pushPose();
            pose.translate(p.x(), p.y(), p.z());
            dispatcher.renderSingleBlock(p.state(), pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        g.flush();
        Lighting.setupForFlatItems();
        pose.popPose();

        // Project the tail (behind the fin) and a point further back to get the screen-space "behind" direction.
        Vector4f tail = m.transform(new Vector4f(-4.6f, 2.2f, 1f, 1f));
        Vector4f back = m.transform(new Vector4f(-7.0f, 2.2f, 1f, 1f));
        float dx = back.x - tail.x, dy = back.y - tail.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3f) { dx = -1; dy = 0; len = 1; }
        return new Tail(tail.x, tail.y, dx / len, dy / len, scale);
    }
}
