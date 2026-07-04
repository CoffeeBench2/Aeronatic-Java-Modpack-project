package com.coffeesaerosmp.core.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.InputStream;

/**
 * Textures needed BEFORE the resource manager is ready (the launch {@code LoadingOverlay} runs
 * during the very first resource reload, when normal ResourceLocation lookups can't serve mod
 * assets yet — vanilla solves the same problem for the Mojang logo with a special preloaded
 * texture). These are read straight from this jar via the classloader and registered as dynamic
 * textures; both the loading overlay and the title screen render through these RLs.
 */
public final class EarlyAssets {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ResourceLocation BG =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "early/loading_bg");
    public static final ResourceLocation COG =
        ResourceLocation.fromNamespaceAndPath("coffeesaerosmp_core", "early/cogwheel");

    /** Source art dimensions (used for cover-scaling). */
    public static final int BG_W = 1920, BG_H = 1009;
    public static final int COG_W = 464, COG_H = 450;

    private static boolean registered;

    private EarlyAssets() {}

    public static void ensureRegistered(Minecraft mc) {
        if (registered || mc == null) return;
        registered = true;
        register(mc, BG, "/assets/coffeesaerosmp_core/textures/gui/loading_bg.png");
        register(mc, COG, "/assets/coffeesaerosmp_core/textures/gui/cogwheel.png");
    }

    private static void register(Minecraft mc, ResourceLocation rl, String classpath) {
        try (InputStream in = EarlyAssets.class.getResourceAsStream(classpath)) {
            if (in == null) throw new IllegalStateException("missing " + classpath);
            mc.getTextureManager().register(rl, new DynamicTexture(NativeImage.read(in)));
        } catch (Exception e) {
            LOGGER.error("[AeroCore] Failed to preload {}: {}", rl, e.toString());
        }
    }
}
