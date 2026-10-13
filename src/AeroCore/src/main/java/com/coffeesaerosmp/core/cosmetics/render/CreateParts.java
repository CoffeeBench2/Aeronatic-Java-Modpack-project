package com.coffeesaerosmp.core.cosmetics.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Create's own art, borrowed live from the installed mods (owner 2026-10-13: "reuse the textures from Create and
 * the addons"). Sprites come straight from the block atlas the game already built: Create's shaft, sail canvas and
 * frame, brass casing, Create Propulsion's wing panels and blades, Wings Plus' angled sails. Whole parts come as
 * the mods' real item models (cogwheel, windmill bearing, propellers). Nothing is copied into AeroCore, so the
 * cosmetics always match the installed Create version and resource packs.
 *
 * <p>Every lookup has a fallback: a missing sprite returns null (callers then use AeroCore's own texture) and a
 * missing item returns null (callers draw a plain part).
 */
public final class CreateParts {

    private CreateParts() {}

    private static final Map<String, TextureAtlasSprite> SPRITES = new HashMap<>();
    private static final Map<String, ItemStack> ITEMS = new HashMap<>();
    private static Object atlasSeen;

    /** One render type for everything from the block atlas: cutout, both faces, entity lighting. */
    public static RenderType atlasType() {
        return RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
    }

    /** A block-atlas sprite such as {@code "create:block/axis"}, or null when the mod (or texture) isn't there. */
    public static TextureAtlasSprite sprite(String id) {
        var atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        if (atlas != atlasSeen) {                     // resource reload: the atlas (and every sprite) was rebuilt
            atlasSeen = atlas;
            SPRITES.clear();
        }
        return SPRITES.computeIfAbsent(id, k -> {
            TextureAtlasSprite s = atlas.getSprite(ResourceLocation.parse(k));
            return s == null || s.contents().name().equals(MissingTextureAtlasSprite.getLocation()) ? null : s;
        });
    }

    /** True when every listed sprite exists. */
    public static boolean have(String... ids) {
        for (String id : ids) if (sprite(id) == null) return false;
        return true;
    }

    /** A registered item as a stack ({@code "create:cogwheel"}), or null. */
    public static ItemStack item(String id) {
        return ITEMS.computeIfAbsent(id, k -> {
            ResourceLocation rl = ResourceLocation.tryParse(k);
            if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return ItemStack.EMPTY;
            var it = BuiltInRegistries.ITEM.get(rl);
            return it == Items.AIR ? ItemStack.EMPTY : new ItemStack(it);
        }).isEmpty() ? null : ITEMS.get(id);
    }

    /** Draw an item's real model at the origin, {@code size} blocks across. */
    public static void model(PoseStack pose, MultiBufferSource buffers, ItemStack stack, float size, int light, Level level, int seed) {
        pose.pushPose();
        pose.scale(size, size, size);
        Minecraft.getInstance().getItemRenderer().renderStatic(stack, ItemDisplayContext.FIXED, light,
            OverlayTexture.NO_OVERLAY, pose, buffers, level, seed);
        pose.popPose();
    }

    // ── textured geometry ────────────────────────────────────────────────────

    /** A rectangle of a sprite, in the sprite's own 0..1 coordinates. */
    public record Uv(float u0, float v0, float u1, float v1) {
        public static final Uv ALL = new Uv(0, 0, 1, 1);
        /** The 4-pixel shaft running down the middle of Create's axis texture. */
        public static final Uv SHAFT = new Uv(6 / 16f, 0, 10 / 16f, 1);
    }

    /**
     * An axis-aligned box from (x0,y0,z0) to (x1,y1,z1) with {@code s} on every face. The long faces map
     * {@code uv} along the box's longest axis, so a shaft texture runs the length of a bone.
     */
    public static void box(PoseStack pose, VertexConsumer vc, TextureAtlasSprite s, Uv uv,
                           float x0, float y0, float z0, float x1, float y1, float z1, int light, int rgb) {
        var m = pose.last();
        float dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), dz = Math.abs(z1 - z0);
        boolean alongX = dx >= dy && dx >= dz, alongY = !alongX && dy >= dz;
        float u0 = s.getU(uv.u0()), u1 = s.getU(uv.u1()), v0 = s.getV(uv.v0()), v1 = s.getV(uv.v1());
        // faces: -z, +z, -x, +x, -y, +y ; the texture's v runs along the long axis
        quad(vc, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, alongX, u0, v0, u1, v1, light, rgb, 0, 0, -1);
        quad(vc, m, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, !alongX, u0, v0, u1, v1, light, rgb, 0, 0, 1);
        quad(vc, m, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, alongY, u0, v0, u1, v1, light, rgb, -1, 0, 0);
        quad(vc, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, !alongY, u0, v0, u1, v1, light, rgb, 1, 0, 0);
        quad(vc, m, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, alongX, u0, v0, u1, v1, light, rgb, 0, -1, 0);
        quad(vc, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, !alongX, u0, v0, u1, v1, light, rgb, 0, 1, 0);
    }

    /**
     * A flat panel in the x–y plane at z, as a quadrilateral (lets a sail taper toward the wingtip):
     * corners a (root top), b (tip top), c (tip bottom), d (root bottom). Both faces.
     */
    public static void panel(PoseStack pose, VertexConsumer vc, TextureAtlasSprite s, Uv uv,
                             float ax, float ay, float bx, float by, float cx, float cy, float dx, float dy, float z,
                             int light, int rgb) {
        var m = pose.last();
        float u0 = s.getU(uv.u0()), u1 = s.getU(uv.u1()), v0 = s.getV(uv.v0()), v1 = s.getV(uv.v1());
        vertex(vc, m, ax, ay, z, u0, v0, light, rgb, 0, 0, -1);
        vertex(vc, m, bx, by, z, u1, v0, light, rgb, 0, 0, -1);
        vertex(vc, m, cx, cy, z, u1, v1, light, rgb, 0, 0, -1);
        vertex(vc, m, dx, dy, z, u0, v1, light, rgb, 0, 0, -1);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose m,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             boolean vAlongAB, float u0, float v0, float u1, float v1, int light, int rgb,
                             float nx, float ny, float nz) {
        if (vAlongAB) {
            vertex(vc, m, ax, ay, az, u0, v0, light, rgb, nx, ny, nz);
            vertex(vc, m, bx, by, bz, u0, v1, light, rgb, nx, ny, nz);
            vertex(vc, m, cx, cy, cz, u1, v1, light, rgb, nx, ny, nz);
            vertex(vc, m, dx, dy, dz, u1, v0, light, rgb, nx, ny, nz);
        } else {
            vertex(vc, m, ax, ay, az, u0, v0, light, rgb, nx, ny, nz);
            vertex(vc, m, bx, by, bz, u1, v0, light, rgb, nx, ny, nz);
            vertex(vc, m, cx, cy, cz, u1, v1, light, rgb, nx, ny, nz);
            vertex(vc, m, dx, dy, dz, u0, v1, light, rgb, nx, ny, nz);
        }
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose m, float x, float y, float z, float u, float v,
                               int light, int rgb, float nx, float ny, float nz) {
        vc.addVertex(m, x, y, z).setColor((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(m, nx, ny, nz);
    }
}
